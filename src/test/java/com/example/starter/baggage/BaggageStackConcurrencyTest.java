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

import com.example.starter.baggage.BaggageDtos.DeclareStackRequest;
import com.example.starter.baggage.BaggageDtos.DeclareStackResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;
import com.example.starter.baggage.BaggageDtos.StackEntryInput;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 堆栈声明并发边界测试：并发声明以版本决定唯一先后；同键并发重放结果一致且只生效一次；
 * 声明与封舱并发只有一个胜者。全部基于 H2 MySQL 兼容模式真实数据库。
 */
@SpringBootTest
class BaggageStackConcurrencyTest {

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
    void concurrentDeclare_versionDecidesSingleWinner() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("A", 800, 1000);
        registerBag("B", 600, 2000);

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> baggageService.declareStacks("LEG1",
                    new DeclareStackRequest(UUID.randomUUID().toString(), "loader-1", 1,
                            List.of(new StackEntryInput("A", 1, 1),
                                    new StackEntryInput("B", 1, 2)))));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(DeclareStackResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(3);

        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(2);
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(records).isEqualTo(2);
    }

    @Test
    void concurrentSameRequestId_replaysSingleEffect() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("A", 800, 1000);

        String requestId = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> baggageService.declareStacks("LEG1",
                    new DeclareStackRequest(requestId, "loader-1", 1,
                            List.of(new StackEntryInput("A", 1, 1)))));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(DeclareStackResponse.class);
            assertThat(((DeclareStackResponse) result).version()).isEqualTo(2);
        });
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(2);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
    }

    @Test
    void concurrentDeclareAndSeal_versionDecidesSingleWinner() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("A", 800, 1000);

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.declareStacks("LEG1",
                        new DeclareStackRequest(UUID.randomUUID().toString(), "loader-1", 1,
                                List.of(new StackEntryInput("A", 1, 1)))),
                () -> baggageService.seal("LEG1",
                        new SealRequest(UUID.randomUUID().toString(), 1)));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(r -> r instanceof DeclareStackResponse || r instanceof SealResponse)
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
        assertThat(version).isEqualTo(2);
        if ("OPEN".equals(status)) {
            // 声明先成功：封舱可随后以新版本完成，冻结快照含 A 的布局
            baggageService.seal("LEG1", new SealRequest(UUID.randomUUID().toString(), 2));
            assertThat(baggageService.getStackLayout("LEG1").entries()).hasSize(1);
        } else {
            // 封舱先成功：空布局被冻结，此后禁止新排堆栈
            assertThat(status).isEqualTo("SEALED");
            assertThat(baggageService.getStackLayout("LEG1").entries()).isEmpty();
        }
    }

    private void registerLeg(String legId, String origin, String destination) {
        baggageService.registerLeg(
                new RegisterLegRequest(UUID.randomUUID().toString(), legId, origin, destination));
    }

    private void registerBag(String bagTag, int selfWeightGrams, int maxLoadAboveGrams) {
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(),
                bagTag, List.of("LEG1"), selfWeightGrams, maxLoadAboveGrams));
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
