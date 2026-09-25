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

import com.example.starter.baggage.BaggageDtos.ClearanceResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterClearanceRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 海关放行并发与幂等边界测试：同 clearanceKey 并发重放只生效一次；
 * 拦截与装载并发按提交顺序裁决且不留半成品；同版本并发登记只产生一个终态。
 * 全部基于 H2 MySQL 兼容模式真实数据库，不使用 mock。
 */
@SpringBootTest
class CustomsConcurrencyTest {

    @Autowired
    private BaggageService baggageService;

    @Autowired
    private CustomsService customsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM customs_hold");
        jdbcTemplate.update("DELETE FROM customs_clearance");
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
    void concurrentSameClearanceContent_replaysSingleRecord() throws Exception {
        registerLeg("LEG_CN_KR", "PEK", "ICN", "CN", "KR");
        registerBag("BAG1", List.of("LEG_CN_KR"));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> customsService.registerClearance(new RegisterClearanceRequest(
                    UUID.randomUUID().toString(), "BAG1", 1, "RELEASED", "KR", null)));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(ClearanceResponse.class);
            assertThat(((ClearanceResponse) result).inspectionVersion()).isEqualTo(1);
        });
        String key = ((ClearanceResponse) results.get(0)).clearanceKey();
        assertThat(results).allSatisfy(result ->
                assertThat(((ClearanceResponse) result).clearanceKey()).isEqualTo(key));
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customs_clearance WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentHoldAndLoad_submissionOrderDecidesConsistentOutcome() throws Exception {
        registerLeg("LEG_CN_KR", "PEK", "ICN", "CN", "KR");
        registerBag("BAG1", List.of("LEG_CN_KR"));
        customsService.registerClearance(new RegisterClearanceRequest(
                UUID.randomUUID().toString(), "BAG1", 1, "RELEASED", "KR", null));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.load("LEG_CN_KR",
                        new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1"))),
                () -> customsService.registerClearance(new RegisterClearanceRequest(
                        UUID.randomUUID().toString(), "BAG1", 2, "HELD", "KR", "布控")));
        List<Object> results = runConcurrently(tasks);

        Object loadResult = results.get(0);
        Object holdResult = results.get(1);
        // 拦截登记始终成功
        assertThat(holdResult).isInstanceOf(ClearanceResponse.class);

        Integer loadCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
        if (loadResult instanceof LoadResponse) {
            // 装载先裁决：装载成功，拦截随后标记未起飞航段
            assertThat(loadCount).isEqualTo(1);
            Integer holds = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM customs_hold WHERE bag_tag = 'BAG1'"
                            + " AND status = 'CUSTOMS_HOLD'", Integer.class);
            assertThat(holds).isEqualTo(1);
        } else {
            // 拦截先裁决：装载 422 海关拦截，且不留任何装载半成品
            assertThat(loadResult).isInstanceOf(ApiException.class);
            assertThat(((ApiException) loadResult).getStatus().value()).isEqualTo(422);
            assertThat(((ApiException) loadResult).getMessage()).contains("海关拦截");
            assertThat(loadCount).isZero();
            Integer version = jdbcTemplate.queryForObject(
                    "SELECT version FROM leg WHERE leg_id = 'LEG_CN_KR'", Integer.class);
            assertThat(version).isEqualTo(1);
        }
    }

    @Test
    void concurrentSameVersionDifferentContent_singleTerminalState() throws Exception {
        registerLeg("LEG_CN_KR", "PEK", "ICN", "CN", "KR");
        registerBag("BAG1", List.of("LEG_CN_KR"));

        List<Callable<Object>> tasks = List.of(
                () -> customsService.registerClearance(new RegisterClearanceRequest(
                        UUID.randomUUID().toString(), "BAG1", 1, "RELEASED", "KR", null)),
                () -> customsService.registerClearance(new RegisterClearanceRequest(
                        UUID.randomUUID().toString(), "BAG1", 1, "HELD", "KR", "查验")));
        List<Object> results = runConcurrently(tasks);

        long created = results.stream().filter(ClearanceResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customs_clearance WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    private void registerLeg(String legId, String origin, String destination,
                             String originCountry, String destinationCountry) {
        baggageService.registerLeg(new RegisterLegRequest(UUID.randomUUID().toString(),
                legId, origin, destination, originCountry, destinationCountry));
    }

    private void registerBag(String bagTag, List<String> legIds) {
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(),
                bagTag, legIds));
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
