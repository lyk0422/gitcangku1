package com.example.starter.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.testsupport.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** 真实并发边界：并发领位不重席、同 requestId 竞态只产生一次变更、并发批准唯一生效。 */
class ConcurrencyApiTest extends AbstractIntegrationTest {

    private static final String COORDINATOR = "coord-1";
    private static final String REVIEWER = "rev-1";

    @Test
    void concurrentEnrollmentsClaimDistinctSeatsInOrder() throws Exception {
        // 8 个区组共 32 席；24 个并发登记者应恰好占满前 6 个区组
        createExperiment("EXP-CONC", 8, "req-conc-create");
        int participants = 24;
        runConcurrently(participants, index -> {
            MvcResult result = postJson("/api/experiments/EXP-CONC/enroll", COORDINATOR, "COORDINATOR",
                    Map.of("participantId", "CP-" + index, "requestId", "req-conc-enroll-" + index));
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
        });

        List<Map<String, Object>> allocations = jdbcTemplate.queryForList(
                "SELECT block_no, seat_no, participant_id FROM allocation "
                        + "WHERE experiment_id = 'EXP-CONC' ORDER BY block_no, seat_no");
        assertThat(allocations).hasSize(participants);

        List<String> positions = new ArrayList<>();
        for (int block = 1; block <= 6; block++) {
            for (int seat = 1; seat <= 4; seat++) {
                positions.add(block + ":" + seat);
            }
        }
        List<String> actual = allocations.stream()
                .map(row -> row.get("block_no") + ":" + row.get("seat_no"))
                .toList();
        assertThat(actual).containsExactlyElementsOf(positions);

        long distinctParticipants = allocations.stream()
                .map(row -> (String) row.get("participant_id"))
                .distinct().count();
        assertThat(distinctParticipants).isEqualTo(participants);
    }

    @Test
    void concurrentEnrollmentsBeyondCapacityNeverOversellSeats() throws Exception {
        // 2 个区组共 8 席，12 个并发登记者：恰好 8 个成功、4 个满额 422，无重席
        createExperiment("EXP-CAP", 2, "req-cap-create");
        int callers = 12;
        List<Future<Integer>> futures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 1; i <= callers; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                return postJson("/api/experiments/EXP-CAP/enroll", COORDINATOR, "COORDINATOR",
                        Map.of("participantId", "OVER-" + index, "requestId", "req-cap-enroll-" + index))
                        .getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        long created = statuses.stream().filter(status -> status == 201).count();
        long full = statuses.stream().filter(status -> status == 422).count();
        assertThat(created).isEqualTo(8);
        assertThat(full).isEqualTo(4);

        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'EXP-CAP'", Integer.class);
        assertThat(total).isEqualTo(8);
        Integer distinctSeats = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT DISTINCT block_no, seat_no FROM allocation "
                        + "WHERE experiment_id = 'EXP-CAP')", Integer.class);
        assertThat(distinctSeats).isEqualTo(8);
    }

    @Test
    void sameRequestIdSubmittedConcurrentlyProducesOneAllocation() throws Exception {
        createExperiment("EXP-RACE", 2, "req-race-create");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return postJson("/api/experiments/EXP-RACE/enroll", COORDINATOR, "COORDINATOR",
                        Map.of("participantId", "RACE-1", "requestId", "raced-request-id"));
            }));
        }
        start.countDown();
        List<String> responses = new ArrayList<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(30, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            responses.add(result.getResponse().getContentAsString());
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // 两个并发请求返回同一份原始结果，且只产生一条分配、一条去重记录
        assertThat(responses.get(0)).isEqualTo(responses.get(1));
        Integer allocations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'EXP-RACE'", Integer.class);
        assertThat(allocations).isEqualTo(1);
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'raced-request-id'",
                Integer.class);
        assertThat(records).isEqualTo(1);
    }

    @Test
    void concurrentApprovalsWithDifferentRequestIdsApproveExactlyOnce() throws Exception {
        long unblindRequestId = pendingRequest("EXP-APPR");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> {
            start.await();
            return postJson(
                    "/api/experiments/EXP-APPR/unblind-requests/" + unblindRequestId + "/approve",
                    REVIEWER, "REVIEWER", Map.of("requestId", "appr-concurrent-1"));
        }));
        futures.add(pool.submit(() -> {
            start.await();
            return postJson(
                    "/api/experiments/EXP-APPR/unblind-requests/" + unblindRequestId + "/approve",
                    "rev-2", "REVIEWER", Map.of("requestId", "appr-concurrent-2"));
        }));
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<MvcResult> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS).getResponse().getStatus());
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, approver_id FROM unblind_request WHERE id = ?", unblindRequestId);
        assertThat(row.get("status")).isEqualTo("APPROVED");
    }

    private long pendingRequest(String experimentId) throws Exception {
        createExperiment(experimentId, 2, "req-" + experimentId.toLowerCase() + "-create");
        postJson("/api/experiments/" + experimentId + "/enroll", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "requestId", "req-" + experimentId.toLowerCase() + "-enroll"));
        MvcResult requested = postJson(
                "/api/experiments/" + experimentId + "/unblind-requests", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "reason", "audit",
                        "requestId", "req-" + experimentId.toLowerCase() + "-ureq"));
        JsonNode body = readBody(requested);
        return body.get("unblindRequestId").asLong();
    }

    private void runConcurrently(int count, ThrowingTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(count, 12));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            final int index = i;
            Callable<Void> callable = () -> {
                start.await();
                task.run(index);
                return null;
            };
            futures.add(pool.submit(callable));
        }
        start.countDown();
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private void createExperiment(String experimentId, int blockCount, String requestId) throws Exception {
        MvcResult result = postJson("/api/experiments", COORDINATOR, "COORDINATOR",
                new HashMap<>(Map.of(
                        "experimentId", experimentId,
                        "blockCount", blockCount,
                        "requestId", requestId)));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    @FunctionalInterface
    private interface ThrowingTask {
        void run(int index) throws Exception;
    }
}
