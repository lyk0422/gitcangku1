package com.example.starter.baggage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.PositionRequest;
import com.example.starter.baggage.BaggageDtos.RearrangeRequest;
import com.example.starter.baggage.BaggageDtos.RearrangeResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平面舱位并发边界测试：装载/重排/封舱共享航段 expectedVersion 与行锁，
 * 同版本竞争恰一胜一 409；同 requestId 重放竞争只生效一次且不报唯一键 500。
 */
@SpringBootTest
class FloorLayoutConcurrencyTest {

    @Autowired
    private BaggageService baggageService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag_size");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM floor_layout");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void concurrentLoadAndSealOnFloor_versionDecidesSingleWinner() throws Exception {
        registerFloorLeg("F1", 10, 10);
        registerSizedBag("BA", 5, 5, "F1");
        registerSizedBag("BB", 5, 5, "F1");
        baggageService.load("F1", new LoadRequest(UUID.randomUUID().toString(), 1,
                List.of("BA"), List.of(new PositionRequest("BA", false, 0, 0)), "alice"));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.load("F1", new LoadRequest(UUID.randomUUID().toString(), 2,
                        List.of("BB"), List.of(new PositionRequest("BB", false, 5, 0)), "alice")),
                () -> baggageService.seal("F1",
                        new SealRequest(UUID.randomUUID().toString(), 2, "alice")));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(r -> r instanceof LoadResponse || r instanceof SealResponse)
                .count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'F1'", Integer.class);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = 'F1'", String.class);
        assertThat(version).isEqualTo(3);
        if ("OPEN".equals(status)) {
            // 装载先成功：封舱后冻结两件行李的完整身份
            SealResponse sealed = baggageService.seal("F1",
                    new SealRequest(UUID.randomUUID().toString(), 3, "alice"));
            assertThat(sealed.manifest()).containsExactly("BA", "BB");
            assertThat(sealed.placements()).hasSize(2);
            assertThat(sealed.placements().stream().filter(p -> p.bagTag().equals("BB")).findFirst()
                    .orElseThrow().x()).isEqualTo(5);
        } else {
            // 封舱先成功：冻结快照只有 BA，之后不得加装
            assertThat(status).isEqualTo("SEALED");
            var manifest = baggageService.getManifest("F1");
            assertThat(manifest.manifest()).containsExactly("BA");
            assertThat(manifest.placements()).hasSize(1);
            assertThat(manifest.placements().get(0).bagTag()).isEqualTo("BA");
        }
    }

    @Test
    void concurrentRearrangesSameVersion_onlyOneTakesEffect() throws Exception {
        registerFloorLeg("F1", 10, 10);
        registerSizedBag("BA", 5, 10, "F1");
        registerSizedBag("BB", 5, 10, "F1");
        baggageService.load("F1", new LoadRequest(UUID.randomUUID().toString(), 1,
                List.of("BA", "BB"),
                List.of(new PositionRequest("BA", false, 0, 0),
                        new PositionRequest("BB", false, 5, 0)), "alice"));

        List<Callable<Object>> swapTasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            swapTasks.add(() -> baggageService.rearrange("F1", new RearrangeRequest(
                    UUID.randomUUID().toString(), 2,
                    List.of(new PositionRequest("BA", false, 5, 0),
                            new PositionRequest("BB", false, 0, 0)), "alice")));
        }
        List<Object> results = runConcurrently(swapTasks);

        long successes = results.stream().filter(RearrangeResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'F1'", Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pos_x FROM load_record WHERE bag_tag = 'BA'", Integer.class)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pos_x FROM load_record WHERE bag_tag = 'BB'", Integer.class)).isEqualTo(0);
        // 无几何违规：不能出现 422
        assertThat(results).noneMatch(r -> r instanceof ApiException ex && ex.getStatus().value() == 422);
    }

    @Test
    void concurrentSameRequestIdRearrange_replaysSingleEffectWithout500() throws Exception {
        registerFloorLeg("F1", 10, 10);
        registerSizedBag("BA", 5, 10, "F1");
        registerSizedBag("BB", 5, 10, "F1");
        baggageService.load("F1", new LoadRequest(UUID.randomUUID().toString(), 1,
                List.of("BA", "BB"),
                List.of(new PositionRequest("BA", false, 0, 0),
                        new PositionRequest("BB", false, 5, 0)), "alice"));

        String requestId = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> baggageService.rearrange("F1", new RearrangeRequest(requestId, 2,
                    List.of(new PositionRequest("BA", false, 5, 0),
                            new PositionRequest("BB", false, 0, 0)), "alice")));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(RearrangeResponse.class);
            assertThat(((RearrangeResponse) result).version()).isEqualTo(3);
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'F1'", Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pos_x FROM load_record WHERE bag_tag = 'BA'", Integer.class)).isEqualTo(5);
    }

    private void registerFloorLeg(String legId, int width, int height) {
        baggageService.registerLeg(new RegisterLegRequest(UUID.randomUUID().toString(),
                legId, "PEK", "SHA", width, height, "setup"));
    }

    private void registerSizedBag(String bagTag, int width, int height, String legId) {
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(),
                bagTag, width, height, List.of(legId), "setup"));
    }

    /** 同步起跑并发执行任务，结果按提交顺序返回（异常包装为返回值）。 */
    private List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    return task.call();
                } catch (ApiException ex) {
                    return ex;
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }
}
