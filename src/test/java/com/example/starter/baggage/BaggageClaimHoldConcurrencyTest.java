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

import com.example.starter.baggage.BaggageDtos.ClaimHoldRequest;
import com.example.starter.baggage.BaggageDtos.ClaimHoldResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认领冻结并发边界测试：同一行李并发冻结只有一个生效；
 * 冻结与装载/封舱按提交顺序裁决，最终状态一致；
 * 同 requestId 并发重放只生效一次。
 */
@SpringBootTest
class BaggageClaimHoldConcurrencyTest {

    @Autowired
    private BaggageService baggageService;

    @Autowired
    private ClaimHoldService claimHoldService;

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
    void concurrentHoldSameBag_onlyOneActiveHold() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String claimKey = "CK-" + i;
            tasks.add(() -> claimHoldService.hold("BAG1",
                    new ClaimHoldRequest(UUID.randomUUID().toString(), claimKey, "D", "r", "agent-" + claimKey)));
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
    void concurrentHoldAndLoad_submissionOrderDecides() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.load("LEG1",
                        new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1"))),
                () -> claimHoldService.hold("BAG1",
                        new ClaimHoldRequest(UUID.randomUUID().toString(), "CK1", "D", "r", "agent-a")));
        List<Object> results = runConcurrently(tasks);

        Object loadResult = results.get(0);
        Object holdResult = results.get(1);
        // 冻结先提交则装载 409；装载先提交则冻结成功并同事务移出清单。两种次序最终状态一致：
        // 行李处于 CLAIM_HOLD 且不在任何装载清单中
        assertThat(holdResult).isInstanceOf(ClaimHoldResponse.class);
        if (loadResult instanceof ApiException ex) {
            assertThat(ex.getStatus().value()).isEqualTo(409);
        } else {
            assertThat(loadResult).isInstanceOf(LoadResponse.class);
            assertThat(((ClaimHoldResponse) holdResult).removedLegId()).isEqualTo("LEG1");
        }
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
        assertThat(status).isEqualTo("CLAIM_HOLD");
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(records).isZero();
    }

    @Test
    void concurrentHoldAndSeal_sealFirstThenHold409() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));
        baggageService.load("LEG1", new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1")));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.seal("LEG1", new SealRequest(UUID.randomUUID().toString(), 2)),
                () -> claimHoldService.hold("BAG1",
                        new ClaimHoldRequest(UUID.randomUUID().toString(), "CK1", "D", "r", "agent-a")));
        List<Object> results = runConcurrently(tasks);

        Object sealResult = results.get(0);
        Object holdResult = results.get(1);
        if (holdResult instanceof ClaimHoldResponse hold) {
            // 冻结先提交：BAG1 移出清单且航段版本递增，旧版本封舱 409；
            // 以当前版本重提封舱成功，清单为空
            assertThat(hold.removedLegId()).isEqualTo("LEG1");
            assertThat(sealResult).isInstanceOf(ApiException.class);
            assertThat(((ApiException) sealResult).getStatus().value()).isEqualTo(409);
            SealResponse sealed = baggageService.seal("LEG1",
                    new SealRequest(UUID.randomUUID().toString(), 3));
            assertThat(sealed.manifest()).isEmpty();
            String bagStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
            assertThat(bagStatus).isEqualTo("CLAIM_HOLD");
        } else {
            // 封舱先提交：封舱成功且清单保留 BAG1，冻结 409
            assertThat(sealResult).isInstanceOf(SealResponse.class);
            assertThat(holdResult).isInstanceOf(ApiException.class);
            assertThat(((ApiException) holdResult).getStatus().value()).isEqualTo(409);
            assertThat(baggageService.getManifest("LEG1").manifest()).containsExactly("BAG1");
        }
        String legStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = 'LEG1'", String.class);
        assertThat(legStatus).isEqualTo("SEALED");
    }

    @Test
    void concurrentSameRequestIdHold_replaysSingleEffect() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));

        String requestId = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> claimHoldService.hold("BAG1",
                    new ClaimHoldRequest(requestId, "CK1", "D", "r", "agent-a")));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(ClaimHoldResponse.class);
            assertThat(((ClaimHoldResponse) result).claimKey()).isEqualTo("CK1");
        });
        Integer holds = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold WHERE claim_key = 'CK1'", Integer.class);
        assertThat(holds).isEqualTo(1);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
        Integer events = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold_event WHERE claim_key = 'CK1'", Integer.class);
        assertThat(events).isEqualTo(1);
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
