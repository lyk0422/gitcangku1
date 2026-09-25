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

import com.example.starter.baggage.BaggageDtos.CustomsHoldRequest;
import com.example.starter.baggage.BaggageDtos.CustomsHoldResponse;
import com.example.starter.baggage.BaggageDtos.CustomsReleaseRequest;
import com.example.starter.baggage.BaggageDtos.CustomsReleaseResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 海关暂扣并发边界测试：暂扣与封舱/装载按事务提交顺序裁决唯一结果；
 * 解除双人并发确认只生效一次；同键并发重放只产生一条暂扣记录。
 */
@SpringBootTest
class BaggageCustomsHoldConcurrencyTest {

    @Autowired
    private BaggageService baggageService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM customs_hold");
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
                () -> baggageService.hold(new CustomsHoldRequest(
                        UUID.randomUUID().toString(), "BAG1", "HOLD1", "PEK", "查验")),
                () -> baggageService.seal("LEG1", new SealRequest(UUID.randomUUID().toString(), 2))));

        long successes = results.stream()
                .filter(r -> r instanceof CustomsHoldResponse || r instanceof SealResponse)
                .count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        String legStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = 'LEG1'", String.class);
        String bagStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
        if (results.get(0) instanceof CustomsHoldResponse) {
            // 暂扣先提交：行李已转出清单，封舱因版本冲突 409
            assertThat(bagStatus).isEqualTo("CUSTOMS_HOLD");
            assertThat(legStatus).isEqualTo("OPEN");
            Integer records = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
            assertThat(records).isZero();
        } else {
            // 封舱先提交：行李处于 SEALED 航段，暂扣 409
            assertThat(legStatus).isEqualTo("SEALED");
            assertThat(bagStatus).isEqualTo("IN_TRANSIT");
            Integer holds = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM customs_hold", Integer.class);
            assertThat(holds).isZero();
        }
    }

    @Test
    void concurrentHoldAndLoad_holdAlwaysWinsEventually() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));

        List<Object> results = runConcurrently(List.of(
                () -> baggageService.hold(new CustomsHoldRequest(
                        UUID.randomUUID().toString(), "BAG1", "HOLD1", "PEK", "查验")),
                () -> baggageService.load("LEG1",
                        new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1")))));

        // 暂扣必然成功；装载在暂扣先提交时 409，在装载先提交时成功但随后被暂扣移出清单
        assertThat(results.get(0)).isInstanceOf(CustomsHoldResponse.class);
        Object loadResult = results.get(1);
        if (loadResult instanceof ApiException ex) {
            assertThat(ex.getStatus().value()).isEqualTo(409);
            assertThat(ex.getMessage()).contains("PEK");
        } else {
            assertThat(loadResult).isInstanceOf(LoadResponse.class);
        }
        // 最终状态一致：行李暂扣且不在任何清单中
        String bagStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
        assertThat(bagStatus).isEqualTo("CUSTOMS_HOLD");
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(records).isZero();
    }

    @Test
    void concurrentReleaseConfirm_twoOperatorsReleaseExactlyOnce() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));
        baggageService.hold(new CustomsHoldRequest(
                UUID.randomUUID().toString(), "BAG1", "HOLD1", "PEK", "查验"));

        List<Object> results = runConcurrently(List.of(
                () -> baggageService.confirmRelease("HOLD1",
                        new CustomsReleaseRequest(UUID.randomUUID().toString(), "OP_A")),
                () -> baggageService.confirmRelease("HOLD1",
                        new CustomsReleaseRequest(UUID.randomUUID().toString(), "OP_B"))));

        assertThat(results).allSatisfy(result -> assertThat(result)
                .isInstanceOf(CustomsReleaseResponse.class));
        List<String> statuses = results.stream()
                .map(CustomsReleaseResponse.class::cast)
                .map(CustomsReleaseResponse::status)
                .sorted()
                .toList();
        // 恰一个第一人确认、一个第二人解除
        assertThat(statuses).containsExactly("PENDING_SECOND_CONFIRM", "RELEASED");

        String bagStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
        assertThat(bagStatus).isEqualTo("IN_TRANSIT");
        String holdStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM customs_hold WHERE hold_key = 'HOLD1'", String.class);
        assertThat(holdStatus).isEqualTo("RELEASED");
        List<String> operators = jdbcTemplate.query(
                "SELECT first_operator, second_operator FROM customs_hold WHERE hold_key = 'HOLD1'",
                (rs, rowNum) -> List.of(rs.getString(1), rs.getString(2))).get(0);
        assertThat(operators).containsExactlyInAnyOrder("OP_A", "OP_B");
    }

    @Test
    void concurrentSameOperatorConfirm_onlyOneFirstConfirm() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));
        baggageService.hold(new CustomsHoldRequest(
                UUID.randomUUID().toString(), "BAG1", "HOLD1", "PEK", "查验"));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> baggageService.confirmRelease("HOLD1",
                    new CustomsReleaseRequest(UUID.randomUUID().toString(), "OP_A")));
        }
        List<Object> results = runConcurrently(tasks);

        long firstConfirms = results.stream()
                .filter(CustomsReleaseResponse.class::isInstance)
                .count();
        long duplicates = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 422)
                .count();
        assertThat(firstConfirms).isEqualTo(1);
        assertThat(duplicates).isEqualTo(2);
        String holdStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM customs_hold WHERE hold_key = 'HOLD1'", String.class);
        assertThat(holdStatus).isEqualTo("ACTIVE");
    }

    @Test
    void concurrentHoldSameRequestId_replaysSingleEffect() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));

        String requestId = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> baggageService.hold(new CustomsHoldRequest(
                    requestId, "BAG1", "HOLD1", "PEK", "查验")));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> assertThat(result)
                .isInstanceOf(CustomsHoldResponse.class));
        Integer holds = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customs_hold WHERE hold_key = 'HOLD1'", Integer.class);
        assertThat(holds).isEqualTo(1);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
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
