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

import com.example.starter.baggage.BaggageDtos.ClaimHoldFreezeRequest;
import com.example.starter.baggage.BaggageDtos.ClaimHoldResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认领冻结并发边界测试：同一行李并发冻结只生效一个；
 * 冻结与装载、冻结与封舱按提交顺序裁决，最终状态一致且无半成品。
 */
@SpringBootTest
class BaggageClaimHoldConcurrencyTest {

    @Autowired
    private BaggageService baggageService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM claim_hold_event");
        jdbcTemplate.update("DELETE FROM claim_hold");
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
    void concurrentFreezeSameBag_singleActiveHold() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String claimKey = "CK_" + i;
            tasks.add(() -> baggageService.freezeClaimHold("BAG1",
                    new ClaimHoldFreezeRequest(UUID.randomUUID().toString(), claimKey,
                            "AGENT_A", "DIGEST-1", "并发冻结")));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(ClaimHoldResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(3);

        Integer holds = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold WHERE bag_tag = 'BAG1' AND status = 'ACTIVE'",
                Integer.class);
        assertThat(holds).isEqualTo(1);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
        assertThat(status).isEqualTo("CLAIM_HOLD");
    }

    @Test
    void concurrentFreezeAndLoad_submissionOrderDecides() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.load("LEG1",
                        new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1"))),
                () -> baggageService.freezeClaimHold("BAG1",
                        new ClaimHoldFreezeRequest(UUID.randomUUID().toString(), "CK1",
                                "AGENT_A", "DIGEST-1", "并发裁决")));
        List<Object> results = runConcurrently(tasks);

        Object loadResult = results.get(0);
        Object freezeResult = results.get(1);
        // 冻结必然成功；装载按提交顺序裁决：冻结先提交则装载 409，装载先提交则冻结移出清单
        assertThat(freezeResult).isInstanceOf(ClaimHoldResponse.class);
        if (loadResult instanceof ApiException ex) {
            assertThat(ex.getStatus().value()).isEqualTo(409);
        } else {
            assertThat(loadResult).isInstanceOf(LoadResponse.class);
        }

        // 最终状态一致：行李冻结，不在任何清单中，无半成品
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
        assertThat(status).isEqualTo("CLAIM_HOLD");
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(records).isZero();
        String loadedLeg = jdbcTemplate.queryForObject(
                "SELECT loaded_leg_id FROM bag WHERE bag_tag = 'BAG1'", String.class);
        assertThat(loadedLeg).isNull();
    }

    @Test
    void concurrentFreezeAndSeal_sealFirstRejectsFreeze() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));
        baggageService.load("LEG1", new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1")));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.seal("LEG1",
                        new SealRequest(UUID.randomUUID().toString(), 2)),
                () -> baggageService.freezeClaimHold("BAG1",
                        new ClaimHoldFreezeRequest(UUID.randomUUID().toString(), "CK1",
                                "AGENT_A", "DIGEST-1", "并发裁决")));
        List<Object> results = runConcurrently(tasks);

        Object sealResult = results.get(0);
        Object freezeResult = results.get(1);
        // 封舱先提交则冻结 409；冻结先提交则封舱版本冲突 409，恰一个成功
        long successes = results.stream()
                .filter(r -> r instanceof SealResponse || r instanceof ClaimHoldResponse)
                .count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        if (sealResult instanceof SealResponse) {
            // 封舱先成功：清单含 BAG1，冻结被 409 拒绝，行李仍在清单中
            assertThat(freezeResult).isInstanceOf(ApiException.class);
            assertThat(baggageService.getManifest("LEG1").manifest()).containsExactly("BAG1");
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
            assertThat(status).isEqualTo("IN_TRANSIT");
            Integer holds = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM claim_hold WHERE bag_tag = 'BAG1'", Integer.class);
            assertThat(holds).isZero();
        } else {
            // 冻结先成功：行李已移出清单，封舱版本冲突；以新版本封舱得到空清单
            assertThat(freezeResult).isInstanceOf(ClaimHoldResponse.class);
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
            assertThat(status).isEqualTo("CLAIM_HOLD");
            SealResponse sealed = baggageService.seal("LEG1",
                    new SealRequest(UUID.randomUUID().toString(), 3));
            assertThat(sealed.manifest()).isEmpty();
        }
    }

    private void registerLeg(String legId, String origin, String destination) {
        baggageService.registerLeg(
                new RegisterLegRequest(UUID.randomUUID().toString(), legId, origin, destination));
    }

    private void registerBag(String bagTag, List<String> legIds) {
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(), bagTag, legIds));
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
