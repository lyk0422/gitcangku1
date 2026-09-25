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

import com.example.starter.baggage.BaggageDtos.ClearOverweightRequest;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.ReweighRequest;
import com.example.starter.baggage.BaggageDtos.ReweighResponse;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复重并发裁决测试（真实 H2 MySQL 兼容库）：
 * 复重与封舱/装载按事务提交顺序裁决；装载总重判定必须读到复重后的最新重量；
 * 同件行李并发复重串行生效；同键并发重放只产生一次效果。
 */
@SpringBootTest
class ReweighConcurrencyTest {

    @Autowired
    private BaggageService baggageService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM overweight_alert");
        jdbcTemplate.update("DELETE FROM reweigh_record");
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
    void concurrentReweighAndSeal_commitOrderDecides() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", null);
        registerBag("BAG1", List.of("LEG1"), 20, 100);
        baggageService.load("LEG1", new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1")));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.reweigh("BAG1",
                        new ReweighRequest(UUID.randomUUID().toString(), "RW-1", 25, "STN-A")),
                () -> baggageService.seal("LEG1",
                        new SealRequest(UUID.randomUUID().toString(), 2)));
        List<Object> results = runConcurrently(tasks);

        // 封舱不依赖重量且版本无人改动，必然成功
        long sealed = results.stream().filter(SealResponse.class::isInstance).count();
        assertThat(sealed).isEqualTo(1);
        long reweighOk = results.stream().filter(ReweighResponse.class::isInstance).count();
        long reweighConflict = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(reweighOk + reweighConflict).isEqualTo(1);

        Integer weight = jdbcTemplate.queryForObject(
                "SELECT weight_kg FROM bag WHERE bag_tag = 'BAG1'", Integer.class);
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reweigh_record WHERE bag_tag = 'BAG1'", Integer.class);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = 'LEG1'", String.class);
        assertThat(status).isEqualTo("SEALED");
        if (reweighOk == 1) {
            // 复重先提交：封舱清单中行李携带最新重量
            assertThat(weight).isEqualTo(25);
            assertThat(historyCount).isEqualTo(1);
        } else {
            // 封舱先提交：复重 409，重量与历史不变
            assertThat(weight).isEqualTo(20);
            assertThat(historyCount).isZero();
        }
    }

    @Test
    void concurrentReweighAndLoad_loadSeesLatestWeight() throws Exception {
        // 上限 50：已装 BAG1=20，待装 BAG2=20；BAG1 并发复重到 40
        registerLeg("LEG1", "PEK", "SHA", 50);
        registerBag("BAG1", List.of("LEG1"), 20, 100);
        registerBag("BAG2", List.of("LEG1"), 20, 100);
        baggageService.load("LEG1", new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1")));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.reweigh("BAG1",
                        new ReweighRequest(UUID.randomUUID().toString(), "RW-1", 40, "STN-A")),
                () -> baggageService.load("LEG1",
                        new LoadRequest(UUID.randomUUID().toString(), 2, List.of("BAG2"))));
        List<Object> results = runConcurrently(tasks);

        long reweighOk = results.stream().filter(ReweighResponse.class::isInstance).count();
        assertThat(reweighOk).isEqualTo(1);
        long loadOk = results.stream().filter(LoadResponse.class::isInstance).count();
        long loadRejected = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 422)
                .count();
        assertThat(loadOk + loadRejected).isEqualTo(1);

        Integer weight = jdbcTemplate.queryForObject(
                "SELECT weight_kg FROM bag WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(weight).isEqualTo(40);
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        if (loadOk == 1) {
            // 装载先于复重提交：按旧重量 20 合计 40 放行；复重随后在 OPEN 航段完成
            assertThat(version).isEqualTo(3);
            Integer aboard = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM load_record WHERE leg_id = 'LEG1'", Integer.class);
            assertThat(aboard).isEqualTo(2);
        } else {
            // 复重先提交：装载必须读到最新重量 40，合计 60 > 50，整批拒绝
            assertThat(version).isEqualTo(2);
            Integer aboard = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM load_record WHERE leg_id = 'LEG1'", Integer.class);
            assertThat(aboard).isEqualTo(1);
            String bag2Leg = jdbcTemplate.queryForObject(
                    "SELECT loaded_leg_id FROM bag WHERE bag_tag = 'BAG2'", String.class);
            assertThat(bag2Leg).isNull();
        }
    }

    @Test
    void concurrentReweighSameBag_allSerializeWithConsistentHistory() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", null);
        registerBag("BAG1", List.of("LEG1"), 20, 100);

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> baggageService.reweigh("BAG1",
                    new ReweighRequest(UUID.randomUUID().toString(), "RW-SAME", 25, "STN-A")));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(ReweighResponse.class));
        Integer weight = jdbcTemplate.queryForObject(
                "SELECT weight_kg FROM bag WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(weight).isEqualTo(25);
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reweigh_record WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(historyCount).isEqualTo(4);
        // 仅首条改变重量（20 -> 25），其余为同值事件
        Integer changed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reweigh_record WHERE bag_tag = 'BAG1' AND weight_changed = TRUE",
                Integer.class);
        assertThat(changed).isEqualTo(1);
        Integer firstNew = jdbcTemplate.queryForObject(
                "SELECT new_weight_kg FROM reweigh_record WHERE bag_tag = 'BAG1' AND seq = 0",
                Integer.class);
        Integer firstOld = jdbcTemplate.queryForObject(
                "SELECT old_weight_kg FROM reweigh_record WHERE bag_tag = 'BAG1' AND seq = 0",
                Integer.class);
        assertThat(firstNew).isEqualTo(25);
        assertThat(firstOld).isEqualTo(20);
    }

    @Test
    void concurrentSameRequestIdReweigh_replaysSingleEffect() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", null);
        registerBag("BAG1", List.of("LEG1"), 20, 100);

        String requestId = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> baggageService.reweigh("BAG1",
                    new ReweighRequest(requestId, "RW-1", 25, "STN-A")));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(ReweighResponse.class);
            assertThat(((ReweighResponse) result).seq()).isZero();
            assertThat(((ReweighResponse) result).weightKg()).isEqualTo(25);
        });
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reweigh_record WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(historyCount).isEqualTo(1);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
    }

    @Test
    void concurrentClearAndReweigh_alertLifecycleStaysConsistent() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", null);
        registerBag("BAG1", List.of("LEG1"), 20, 23);
        // 先制造一个活动提醒
        baggageService.reweigh("BAG1",
                new ReweighRequest(UUID.randomUUID().toString(), "RW-0", 30, "STN-A"));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.clearOverweight("BAG1",
                        new ClearOverweightRequest(UUID.randomUUID().toString(), "补缴费用")),
                () -> baggageService.reweigh("BAG1",
                        new ReweighRequest(UUID.randomUUID().toString(), "RW-1", 31, "STN-A")));
        List<Object> results = runConcurrently(tasks);

        // 清除必成功（开始时确有活动提醒）；持续超重的复重始终成功
        assertThat(results).hasSize(2);
        boolean active = jdbcTemplate.queryForObject(
                "SELECT overweight_active FROM bag WHERE bag_tag = 'BAG1'", Boolean.class);
        Integer activeAlerts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM overweight_alert WHERE bag_tag = 'BAG1' AND status = 'ACTIVE'",
                Integer.class);
        Integer clearedAlerts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM overweight_alert WHERE bag_tag = 'BAG1' AND status = 'CLEARED'",
                Integer.class);
        if (active) {
            // 清除先提交：原提醒 CLEARED，随后复重重超限额生成一条新的 ACTIVE 提醒
            assertThat(activeAlerts).isEqualTo(1);
            assertThat(clearedAlerts).isEqualTo(1);
        } else {
            // 复重先提交（活动提醒已存在，不重复生成），清除随后生效
            assertThat(activeAlerts).isZero();
            assertThat(clearedAlerts).isEqualTo(1);
        }
        // 袋上活动标志与提醒表始终一致，不存在被清除两次的提醒
        Integer updateCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM overweight_alert WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(updateCount).isEqualTo(active ? 2 : 1);
    }

    private void registerLeg(String legId, String origin, String destination, Integer maxLoadWeightKg) {
        baggageService.registerLeg(new RegisterLegRequest(
                UUID.randomUUID().toString(), legId, origin, destination, maxLoadWeightKg));
    }

    private void registerBag(String bagTag, List<String> legIds, int weightKg, int freeAllowanceKg) {
        baggageService.registerBag(new RegisterBagRequest(
                UUID.randomUUID().toString(), bagTag, legIds, weightKg, freeAllowanceKg));
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
