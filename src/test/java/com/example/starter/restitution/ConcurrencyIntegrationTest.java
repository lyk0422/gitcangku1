package com.example.starter.restitution;

import com.example.starter.restitution.dto.CreateCaseRequest;
import com.example.starter.restitution.dto.DecisionRequest;
import com.example.starter.restitution.dto.DecisionResponse;
import com.example.starter.restitution.service.IdempotentExecutor;
import com.example.starter.restitution.service.RestitutionService;
import com.example.starter.restitution.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实并发边界（真实线程 + H2 FOR UPDATE 行锁与事务，全部带超时）：
 * 1) 两个裁决同版本竞争，恰一个成功，另一个 409，不留部分冻结；
 * 2) 裁决与证据撤销竞争，不会冻结失效批准；
 * 3) 裁决与主张撤回竞争；
 * 4) 同 requestId+操作者并发建案，恰一份数据、全部重放同一结果；
 * 5) 证据 20 份上限在并发下不被突破。
 */
class ConcurrencyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private RestitutionService service;

    @Autowired
    private IdempotentExecutor executor;

    private record Result<T>(T value, ApiException error, boolean success) {
    }

    private <T> List<Result<T>> runConcurrently(int threads, java.util.function.Function<Integer, T> task)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger index = new AtomicInteger();
        List<Future<Result<T>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    try {
                        return new Result<>(task.apply(index.getAndIncrement()), null, true);
                    } catch (ApiException ex) {
                        return new Result<>(null, ex, false);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Result<T>> results = new ArrayList<>();
            for (Future<Result<T>> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void twoDecisionsSameVersionExactlyOneWins() throws Exception {
        String caseId = createCase("clerk", List.of("Vase"));
        registerClaim("clerk", caseId, "c1", "museum-A", List.of("Vase"));
        addEvidence("clerk", caseId, "c1", "ev-1", "s");
        approve("reviewer-1", caseId, "c1");
        approve("reviewer-2", caseId, "c1");
        long version = getJson("/api/cases/" + caseId, 200).get("version").asLong();

        List<Result<DecisionResponse>> results = runConcurrently(2, i -> service.decide(caseId,
                new DecisionRequest(version, List.of("c1"))));

        long success = results.stream().filter(Result::success).count();
        long conflicts = results.stream().filter(r -> !r.success() && r.error().getStatus() == 409).count();
        assertThat(success).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        assertThat(getJson("/api/cases/" + caseId, 200).get("status").asText()).isEqualTo("DECIDED");
        assertThat(getJson("/api/cases/" + caseId, 200).get("version").asLong()).isEqualTo(version + 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM frozen_claim WHERE case_id = ?", Integer.class, caseId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM frozen_item WHERE case_id = ?", Integer.class, caseId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM frozen_evidence WHERE case_id = ?", Integer.class, caseId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM frozen_approval WHERE case_id = ?", Integer.class, caseId))
                .isEqualTo(2);
    }

    @Test
    void decisionRacesEvidenceRevocationNeverFreezesStaleApproval() throws Exception {
        String caseId = createCase("clerk", List.of("Vase"));
        registerClaim("clerk", caseId, "c1", "museum-A", List.of("Vase"));
        addEvidence("clerk", caseId, "c1", "ev-1", "s");
        approve("reviewer-1", caseId, "c1");
        approve("reviewer-2", caseId, "c1");
        long version = getJson("/api/cases/" + caseId, 200).get("version").asLong();

        List<Result<Object>> results = runConcurrently(2, (Integer i) -> {
            if (i == 0) {
                return executor.execute(UUID.randomUUID().toString(), "judge", "DECIDE",
                        new DecisionRequest(version, List.of("c1")), DecisionResponse.class, 200,
                        () -> service.decide(caseId, new DecisionRequest(version, List.of("c1"))))
                        .body();
            }
            return executor.execute(UUID.randomUUID().toString(), "clerk", "REVOKE",
                    null, com.example.starter.restitution.dto.CaseResponse.class, 200,
                    () -> service.revokeEvidence(caseId, "c1", "ev-1")).body();
        });

        String finalStatus = getJson("/api/cases/" + caseId, 200).get("status").asText();

        if ("DECIDED".equals(finalStatus)) {
            // 裁决先提交：撤销必被终态拒绝，冻结证据与两名评审人批准完整
            Result<Object> revokeResult = results.get(1);
            assertThat(revokeResult.success()).isFalse();
            assertThat(revokeResult.error().getStatus()).isEqualTo(409);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM frozen_evidence WHERE case_id = ?", Integer.class, caseId))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT active FROM evidence WHERE evidence_key = 'ev-1'", Boolean.class))
                    .isTrue();
        } else {
            // 撤销先提交：裁决必版本冲突，案件仍 OPEN，无任何冻结数据
            assertThat(finalStatus).isEqualTo("OPEN");
            Result<Object> decideResult = results.get(0);
            assertThat(decideResult.success()).isFalse();
            assertThat(decideResult.error().getStatus()).isEqualTo(409);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM frozen_claim WHERE case_id = ?", Integer.class, caseId))
                    .isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT active FROM evidence WHERE evidence_key = 'ev-1'", Boolean.class))
                    .isFalse();
        }
    }

    @Test
    void decisionRacesClaimWithdrawal() throws Exception {
        String caseId = createCase("clerk", List.of("Vase"));
        registerClaim("clerk", caseId, "c1", "museum-A", List.of("Vase"));
        addEvidence("clerk", caseId, "c1", "ev-1", "s");
        approve("reviewer-1", caseId, "c1");
        approve("reviewer-2", caseId, "c1");
        long version = getJson("/api/cases/" + caseId, 200).get("version").asLong();

        List<Result<Object>> results = runConcurrently(2, (java.util.function.Function<Integer, Object>) i -> {
            if (i == 0) {
                return service.decide(caseId, new DecisionRequest(version, List.of("c1")));
            }
            return service.withdrawClaim(caseId, "c1");
        });

        String status = getJson("/api/cases/" + caseId, 200).get("status").asText();
        if ("DECIDED".equals(status)) {
            Result<Object> w = results.get(1);
            assertThat(w.success()).isFalse();
            assertThat(w.error().getStatus()).isEqualTo(409);
            assertThat(jdbc.queryForObject(
                    "SELECT withdrawn FROM claim WHERE claim_key = 'c1'", Boolean.class))
                    .isFalse();
        } else {
            assertThat(status).isEqualTo("OPEN");
            Result<Object> d = results.get(0);
            assertThat(d.success()).isFalse();
            assertThat(d.error().getStatus()).isEqualTo(409);
            assertThat(jdbc.queryForObject(
                    "SELECT withdrawn FROM claim WHERE claim_key = 'c1'", Boolean.class))
                    .isTrue();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM frozen_claim WHERE case_id = ?", Integer.class, caseId))
                    .isZero();
        }
    }

    @Test
    void concurrentSameRequestIdCreatesExactlyOneCase() throws Exception {
        String sharedRequestId = UUID.randomUUID().toString();
        int threads = 4;
        List<Result<com.example.starter.restitution.dto.CaseResponse>> results =
                runConcurrently(threads, i -> executor.execute(
                        sharedRequestId, "clerk", "POST /api/cases",
                        new CreateCaseRequest(List.of("Vase", "Scroll")),
                        com.example.starter.restitution.dto.CaseResponse.class, 201,
                        () -> service.createCase(new CreateCaseRequest(List.of("Scroll", "Vase"))))
                        .body());

        assertThat(results).allMatch(Result::success);
        String caseId = results.get(0).value().caseId();
        assertThat(results).extracting(r -> r.value().caseId()).containsOnly(caseId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restitution_case", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM case_item", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void evidenceLimitHoldsUnderConcurrency() throws Exception {
        String caseId = createCase("clerk", List.of("Vase"));
        registerClaim("clerk", caseId, "c1", "museum-A", List.of("Vase"));
        for (int i = 1; i <= 19; i++) {
            addEvidence("clerk", caseId, "c1", "ev-" + i, "s-" + i);
        }

        List<Result<Object>> results = runConcurrently(2,
                (java.util.function.Function<Integer, Object>) i -> {
                    String key = i == 0 ? "ev-20" : "ev-21";
                    return service.addEvidence(caseId, "c1",
                            new com.example.starter.restitution.dto.AddEvidenceRequest(key, "s"));
                });

        long accepted = results.stream().filter(Result::success).count();
        long rejected = results.stream()
                .filter(r -> !r.success() && r.error().getStatus() == 409).count();
        assertThat(accepted).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence e JOIN claim c ON e.claim_id = c.id "
                        + "WHERE c.claim_key = 'c1'", Integer.class)).isEqualTo(20);
    }
}
