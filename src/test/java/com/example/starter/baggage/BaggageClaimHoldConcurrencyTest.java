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
import com.example.starter.baggage.BaggageDtos.ClaimReviewRequest;
import com.example.starter.baggage.BaggageDtos.ClaimReviewResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认领冻结并发边界测试：冻结与封舱/装载按提交顺序裁决（封舱先则冻结 409，
 * 冻结先则装载 409）；同一行李并发冻结只有一条生效；同键并发重放只生效一次；
 * 并发复核只有一人成功。全部基于 H2 MySQL 兼容模式真实数据库。
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
    void concurrentHoldAndSeal_commitOrderDecidesSingleWinner() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));
        baggageService.load("LEG1", new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1")));

        List<Object> results = runConcurrently(List.of(
                () -> baggageService.hold("BAG1",
                        new ClaimHoldRequest(UUID.randomUUID().toString(), "CK-1",
                                "agent-a", "digest-1", "乘客认领争议")),
                () -> baggageService.seal("LEG1",
                        new SealRequest(UUID.randomUUID().toString(), 2))));

        long holds = results.stream().filter(ClaimHoldResponse.class::isInstance).count();
        long seals = results.stream().filter(SealResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        // 按提交顺序裁决：恰一方成功，另一方 409
        assertThat(holds + seals).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        String legStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = 'LEG1'", String.class);
        String bagStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
        if (seals == 1) {
            // 封舱先提交：冻结 409，行李仍在封舱清单中，无冻结记录
            assertThat(legStatus).isEqualTo("SEALED");
            assertThat(bagStatus).isEqualTo("IN_TRANSIT");
            assertThat(baggageService.getManifest("LEG1").manifest()).containsExactly("BAG1");
            Integer holdRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM claim_hold", Integer.class);
            assertThat(holdRows).isZero();
        } else {
            // 冻结先提交：行李移出清单并转 CLAIM_HOLD，封舱因版本冲突 409
            assertThat(legStatus).isEqualTo("OPEN");
            assertThat(bagStatus).isEqualTo("CLAIM_HOLD");
            Integer loadRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
            assertThat(loadRows).isZero();
            // 封舱可以新版本重提，清单为空
            SealResponse sealed = baggageService.seal("LEG1",
                    new SealRequest(UUID.randomUUID().toString(), 3));
            assertThat(sealed.manifest()).isEmpty();
        }
    }

    @Test
    void concurrentHoldAndLoad_freezeAlwaysWinsEventually() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));

        List<Object> results = runConcurrently(List.of(
                () -> baggageService.hold("BAG1",
                        new ClaimHoldRequest(UUID.randomUUID().toString(), "CK-1",
                                "agent-a", "digest-1", "乘客认领争议")),
                () -> baggageService.load("LEG1",
                        new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1")))));

        // 冻结先提交则装载 409；装载先提交则冻结同事务移出清单，两种顺序冻结都成功
        long holds = results.stream().filter(ClaimHoldResponse.class::isInstance).count();
        assertThat(holds).isEqualTo(1);
        Object other = results.stream().filter(r -> !(r instanceof ClaimHoldResponse)).findFirst().orElseThrow();
        if (other instanceof ApiException ex) {
            assertThat(ex.getStatus().value()).isEqualTo(409);
        } else {
            assertThat(other).isInstanceOf(LoadResponse.class);
        }

        // 最终状态一致：行李 CLAIM_HOLD 且不在任何清单中
        String bagStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
        assertThat(bagStatus).isEqualTo("CLAIM_HOLD");
        Integer loadRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(loadRows).isZero();
        Integer activeHolds = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold WHERE active_bag_tag = 'BAG1'", Integer.class);
        assertThat(activeHolds).isEqualTo(1);
    }

    @Test
    void concurrentDoubleHold_singleActiveHold() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int n = i;
            tasks.add(() -> baggageService.hold("BAG1",
                    new ClaimHoldRequest(UUID.randomUUID().toString(), "CK-" + n,
                            "agent-" + n, "digest-" + n, "乘客认领争议")));
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
        Integer activeHolds = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold WHERE active_bag_tag = 'BAG1'", Integer.class);
        assertThat(activeHolds).isEqualTo(1);
        Integer chainRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold_event WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(chainRows).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdHold_replaysSingleEffect() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));

        String requestId = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> baggageService.hold("BAG1",
                    new ClaimHoldRequest(requestId, "CK-1", "agent-a", "digest-1", "乘客认领争议")));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> assertThat(result)
                .isInstanceOf(ClaimHoldResponse.class));
        long distinctHoldIds = results.stream()
                .map(ClaimHoldResponse.class::cast)
                .map(ClaimHoldResponse::holdId)
                .distinct()
                .count();
        assertThat(distinctHoldIds).isEqualTo(1);
        Integer holdRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold", Integer.class);
        assertThat(holdRows).isEqualTo(1);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
    }

    @Test
    void concurrentReview_singleReviewerWins() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));
        baggageService.hold("BAG1", new ClaimHoldRequest(UUID.randomUUID().toString(),
                "CK-1", "agent-a", "digest-1", "乘客认领争议"));

        List<Object> results = runConcurrently(List.of(
                () -> baggageService.review("BAG1",
                        new ClaimReviewRequest(UUID.randomUUID().toString(), "agent-b", "digest-1")),
                () -> baggageService.review("BAG1",
                        new ClaimReviewRequest(UUID.randomUUID().toString(), "agent-c", "digest-1"))));

        long successes = results.stream().filter(ClaimReviewResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM claim_hold WHERE bag_tag = 'BAG1'", String.class);
        assertThat(status).isEqualTo("REVIEWED");
        Integer reviewEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold_event WHERE bag_tag = 'BAG1' AND event_type = 'REVIEW'",
                Integer.class);
        assertThat(reviewEvents).isEqualTo(1);
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
