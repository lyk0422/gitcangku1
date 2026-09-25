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
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发边界测试：同件行李并发装载只进入一个清单；
 * 装载与封舱并发以版本决定唯一先后；同键并发重放结果一致且只生效一次。
 */
@SpringBootTest
class BaggageConcurrencyTest {

    @Autowired
    private BaggageService baggageService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM container_occupancy");
        jdbcTemplate.update("DELETE FROM cutoff_exception");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
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
    void concurrentLoadSameBag_entersOnlyOneManifest() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> baggageService.load("LEG1", loadRequest(1, List.of("BAG1"))));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(LoadResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(3);

        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(records).isEqualTo(1);
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(2);
    }

    @Test
    void concurrentLoadAndSeal_versionDecidesSingleWinner() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG1"));
        baggageService.load("LEG1", loadRequest(1, List.of("BAG1")));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.load("LEG1", loadRequest(2, List.of("BAG2"))),
                () -> baggageService.seal("LEG1",
                        new SealRequest(UUID.randomUUID().toString(), 2)));
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

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = 'LEG1'", String.class);
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(3);
        if ("OPEN".equals(status)) {
            // 装载先成功：封舱可随后以新版本完成，清单包含两件行李
            SealResponse sealed = baggageService.seal("LEG1",
                    new SealRequest(UUID.randomUUID().toString(), 3));
            assertThat(sealed.manifest()).containsExactly("BAG1", "BAG2");
        } else {
            // 封舱先成功：清单只有 BAG1，封舱后不得加装
            assertThat(status).isEqualTo("SEALED");
            assertThat(baggageService.getManifest("LEG1").manifest()).containsExactly("BAG1");
        }
    }

    @Test
    void concurrentSameRequestId_replaysSingleEffect() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));

        String requestId = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> baggageService.load("LEG1",
                    new LoadRequest(requestId, 1, "tester", "ULD-CONCURRENT", List.of("BAG1"))));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(LoadResponse.class);
            assertThat(((LoadResponse) result).version()).isEqualTo(2);
        });
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(2);
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(records).isEqualTo(1);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
    }

    private void registerLeg(String legId, String origin, String destination) {
        baggageService.registerLeg(
                new RegisterLegRequest(UUID.randomUUID().toString(), legId, origin, destination,
                        java.time.Instant.parse("2099-01-01T00:00:00Z")));
    }

    private void registerBag(String bagTag, List<String> legIds) {
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(), bagTag, legIds));
    }

    private LoadRequest loadRequest(int expectedVersion, List<String> bagTags) {
        return new LoadRequest(UUID.randomUUID().toString(), expectedVersion,
                "tester", "ULD-CONCURRENT", bagTags);
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
