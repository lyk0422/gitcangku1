package com.example.starter.restitution.service;

import com.example.starter.restitution.AbstractH2IntegrationTest;
import com.example.starter.restitution.error.ApiException;
import com.example.starter.restitution.error.IdempotentReplayException;
import com.example.starter.restitution.web.dto.AddEvidenceRequest;
import com.example.starter.restitution.web.dto.CaseResponse;
import com.example.starter.restitution.web.dto.DecideRequest;
import com.example.starter.restitution.web.dto.DecisionResponse;
import com.example.starter.restitution.web.dto.RegisterClaimRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实并发竞争测试：线程经 CyclicBarrier 同时放行，竞争由 H2 行锁与案件版本裁决，
 * 断言最终数据而非打印成功，全部带超时。
 */
class ConcurrencyServiceTest extends AbstractH2IntegrationTest {

    @Autowired
    private RestitutionService service;

    private final AtomicLong seq = new AtomicLong();

    private String rid() {
        return "creq-" + seq.incrementAndGet();
    }

    /** 并发执行结果：SUCCESS(201)/REPLAY/或错误 HTTP 状态码。 */
    private record Outcome(String kind, int status) {
    }

    private interface ConcurrentCall {
        void run() throws Exception;
    }

    private List<Outcome> runConcurrently(ConcurrentCall first, ConcurrentCall second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<Outcome> f1 = pool.submit(() -> executeOne(barrier, first));
            Future<Outcome> f2 = pool.submit(() -> executeOne(barrier, second));
            return List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Outcome executeOne(CyclicBarrier barrier, ConcurrentCall call) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            call.run();
            return new Outcome("SUCCESS", 200);
        } catch (IdempotentReplayException replay) {
            return new Outcome("REPLAY", replay.getHttpStatus());
        } catch (ApiException api) {
            return new Outcome("ERROR", api.getStatus());
        } catch (Exception other) {
            throw new RuntimeException("并发用例出现非预期异常", other);
        }
    }

    private CaseResponse prepareReadyCase() {
        CaseResponse c = service.createCase("officer", rid(), List.of("A"));
        service.registerClaim("officer", rid(), c.caseKey(),
                new RegisterClaimRequest("CL-1", "alice", "说明", List.of("A")));
        service.addEvidence("officer", rid(), c.caseKey(), "CL-1",
                new AddEvidenceRequest("E-1", "摘要一"));
        service.approve("r1", rid(), c.caseKey(), "CL-1");
        service.approve("r2", rid(), c.caseKey(), "CL-1");
        return c;
    }

    @Test
    void twoConcurrentDecisionsExactlyOneWins() throws Exception {
        CaseResponse c = prepareReadyCase();
        long version = service.getCase(c.caseKey()).version();

        List<Outcome> outcomes = runConcurrently(
                () -> service.decide("judge", rid(), c.caseKey(),
                        new DecideRequest(version, List.of("CL-1"))),
                () -> service.decide("judge", rid(), c.caseKey(),
                        new DecideRequest(version, List.of("CL-1"))));

        long successCount = outcomes.stream().filter(o -> o.kind().equals("SUCCESS")).count();
        long conflictCount = outcomes.stream()
                .filter(o -> o.kind().equals("ERROR") && o.status() == 409).count();
        assertThat(successCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(1);

        // 最终恰有一份裁决，案件终态版本正确。
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from frozen_decision", Long.class)).isEqualTo(1L);
        assertThat(service.getCase(c.caseKey()).status()).isEqualTo("DECIDED");
        assertThat(service.getCase(c.caseKey()).version()).isEqualTo(version + 1);
    }

    @Test
    void decideRacingWithEvidenceAppendNeverFreezesStaleState() throws Exception {
        CaseResponse c = prepareReadyCase();
        long version = service.getCase(c.caseKey()).version();

        List<Outcome> outcomes = runConcurrently(
                () -> service.decide("judge", rid(), c.caseKey(),
                        new DecideRequest(version, List.of("CL-1"))),
                () -> service.addEvidence("officer", rid(), c.caseKey(), "CL-1",
                        new AddEvidenceRequest("E-2", "摘要二")));

        long decideWins = outcomes.stream()
                .filter(o -> o.kind().equals("SUCCESS"))
                .count();
        // 恰好一方成功；另一方 409（终态拒绝写入或版本冲突）。
        assertThat(decideWins).isEqualTo(1);
        assertThat(outcomes).filteredOn(o -> o.kind().equals("ERROR"))
                .extracting(Outcome::status).containsExactly(409);

        boolean decided = "DECIDED".equals(service.getCase(c.caseKey()).status());
        if (decided) {
            // 裁决先赢：冻结时证据版本为 1，冻结证据只含 E-1，冻结批准恰好两名评审人。
            DecisionResponse decision = service.getDecision(c.caseKey());
            assertThat(decision.version()).isEqualTo(version + 1);
            assertThat(decision.evidence()).extracting("evidenceKey").containsExactly("E-1");
            assertThat(decision.approvals()).extracting("reviewer")
                    .containsExactlyInAnyOrder("r1", "r2");
            // 追加证据不得在终态后落库。
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from evidence where evidence_key = 'E-2'", Long.class)).isZero();
        } else {
            // 证据先赢：版本被推高，裁决未发生；新证据落库且旧批准不被冻结。
            assertThat(service.getCase(c.caseKey()).version()).isEqualTo(version + 1);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from frozen_decision", Long.class)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from evidence where evidence_key = 'E-2' and status = 'ACTIVE'",
                    Long.class)).isEqualTo(1L);
        }
    }

    @Test
    void sameRequestIdConcurrentCreatesExactlyOneCase() throws Exception {
        List<Outcome> outcomes = runConcurrently(
                () -> service.createCase("officer", "SHARED-RID", List.of("A", "B")),
                () -> service.createCase("officer", "SHARED-RID", List.of("A", "B")));

        long success = outcomes.stream().filter(o -> o.kind().equals("SUCCESS")).count();
        long replays = outcomes.stream()
                .filter(o -> o.kind().equals("REPLAY") && o.status() == 201).count();
        assertThat(success).isEqualTo(1);
        assertThat(replays).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from restitution_case", Long.class)).isEqualTo(1L);
    }
}
