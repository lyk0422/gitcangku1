package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.DestructionCreateRequest;
import com.example.starter.evidence.dto.HoldCreateRequest;
import com.example.starter.evidence.dto.HoldReleaseRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冻结/解除/销毁并发与幂等边界测试（真实 H2，MODE=MySQL，行锁 FOR UPDATE）。
 * 核心不变量：并发结束后不能出现“冻结已生效而证物同时被销毁”：
 * 若证物为 DESTROYED，则不存在有效冻结；若存在有效冻结，则证物仍 SEALED 且销毁被 DENIED。
 */
@SpringBootTest
class HoldConcurrencyTest {

    private static final LocalDateTime NOON = LocalDateTime.of(2026, 10, 1, 12, 0, 0);
    private static final LocalDateTime BASE = NOON.minusMinutes(1);

    @Autowired
    private HoldController holdController;

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private EvidenceService evidenceService;

    @Autowired
    private EvidenceClock evidenceClock;

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void fixClock() {
        evidenceClock.setClock(Clock.fixed(BASE.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    private void intake(String actor, String evidenceKey, String caseKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, caseKey, "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    /**
     * 记录一次并发调用的 HTTP 状态码（业务异常取其携带状态码）。
     */
    private static final class StatusCall implements Callable<Integer> {
        private final CountDownLatch ready;
        private final CountDownLatch start;
        private final Callable<ResponseEntity<String>> call;

        StatusCall(CountDownLatch ready, CountDownLatch start, Callable<ResponseEntity<String>> call) {
            this.ready = ready;
            this.start = start;
            this.call = call;
        }

        @Override
        public Integer call() throws Exception {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                return call.call().getStatusCode().value();
            } catch (ApiException e) {
                return e.status().value();
            }
        }
    }

    private List<Integer> runConcurrently(Callable<ResponseEntity<String>> first,
                                          Callable<ResponseEntity<String>> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> f1 = executor.submit(new StatusCall(ready, start, first));
            Future<Integer> f2 = executor.submit(new StatusCall(ready, start, second));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentEvidenceHoldAndDestroyNeverLeavesEffectiveHoldOnDestroyedEvidence() throws Exception {
        fixClock();
        String caseKey = uniqueKey("CASE");
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, caseKey);

        String holdRequestId = uniqueKey("HOLD");
        List<Integer> statuses = runConcurrently(
                () -> holdController.createEvidenceHold("bob", evidenceKey,
                        new HoldCreateRequest(holdRequestId, NOON, "并发冻结")),
                () -> evidenceController.destroy("alice", evidenceKey,
                        new DestructionCreateRequest(uniqueKey("DES"), "并发销毁")));

        var chain = evidenceService.custodyChain(evidenceKey);
        boolean destroyed = chain.evidence().status() == EvidenceStatus.DESTROYED;
        boolean effectiveHold = chain.holds().stream().anyMatch(hold -> hold.effective());

        if (destroyed) {
            // 销毁先提交：冻结创建随后必须被拒绝（证物已销毁），不存在有效冻结
            assertThat(statuses).contains(200);
            assertThat(effectiveHold).isFalse();
            assertThat(chain.holds()).isEmpty();
            assertThat(chain.destructions()).hasSize(1);
            assertThat(chain.destructions().get(0).status()).isEqualTo(DestructionStatus.ALLOWED);
        } else {
            // 冻结先提交：证物必须仍 SEALED，销毁被 DENIED 留痕
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(effectiveHold).isTrue();
            assertThat(statuses).contains(409);
            assertThat(chain.destructions()).hasSize(1);
            assertThat(chain.destructions().get(0).status()).isEqualTo(DestructionStatus.DENIED);
            assertThat(chain.destructions().get(0).blockingHoldId()).isNotNull();
        }
    }

    @Test
    void concurrentCaseHoldAndDestroySerializeAndHoldStillCoversOtherEvidence() throws Exception {
        fixClock();
        String caseKey = uniqueKey("CASE");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, caseKey);
        intake("alice", ev2, caseKey);

        List<Integer> statuses = runConcurrently(
                () -> holdController.createCaseHold("bob", caseKey,
                        new HoldCreateRequest(uniqueKey("HOLD"), NOON, "并发案件冻结")),
                () -> evidenceController.destroy("alice", ev1,
                        new DestructionCreateRequest(uniqueKey("DES"), "并发销毁")));

        var chain1 = evidenceService.custodyChain(ev1);
        boolean destroyed = chain1.evidence().status() == EvidenceStatus.DESTROYED;
        if (destroyed) {
            // 销毁先提交（200），案件冻结随后建立（201）：合法的串行顺序
            assertThat(statuses).containsExactlyInAnyOrder(200, 201);
        } else {
            // 案件冻结先提交：ev1 销毁必须被 DENIED，证物保持 SEALED
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertThat(chain1.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(chain1.destructions().get(0).status()).isEqualTo(DestructionStatus.DENIED);
        }

        // 无论哪个先提交，案件冻结建立之后，案件下另一件证物的销毁必须被阻断
        // （若 ev1 已销毁，新时钟仍在 11:59，案件冻结对 ev2 有效）
        ResponseEntity<String> destroyEv2 = evidenceController.destroy("alice", ev2,
                new DestructionCreateRequest(uniqueKey("DES"), "销毁同案件另一件"));
        assertThat(destroyEv2.getStatusCode().value()).isEqualTo(409);
        assertThat(evidenceService.custodyChain(ev2).evidence().status())
                .isEqualTo(EvidenceStatus.SEALED);
    }

    @Test
    void concurrentDuplicateHoldWithSameRequestIdCreatesSingleHold() throws Exception {
        fixClock();
        String caseKey = uniqueKey("CASE");
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, caseKey);
        HoldCreateRequest request = new HoldCreateRequest(uniqueKey("HOLD"), NOON, "重复冻结");

        List<Integer> statuses = runConcurrently(
                () -> holdController.createEvidenceHold("bob", evidenceKey, request),
                () -> holdController.createEvidenceHold("bob", evidenceKey, request));

        assertThat(statuses).containsOnly(201);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.holds()).hasSize(1);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
    }

    @Test
    void concurrentDuplicateDestroyWithSameRequestIdUnderHoldRecordsSingleDenied() throws Exception {
        fixClock();
        String caseKey = uniqueKey("CASE");
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, caseKey);
        ResponseEntity<String> hold = holdController.createCaseHold("bob", caseKey,
                new HoldCreateRequest(uniqueKey("HOLD"), NOON, "案件冻结"));
        assertThat(hold.getStatusCode().value()).isEqualTo(201);

        DestructionCreateRequest request = new DestructionCreateRequest(uniqueKey("DES"), "重复销毁");
        List<Integer> statuses = runConcurrently(
                () -> evidenceController.destroy("alice", evidenceKey, request),
                () -> evidenceController.destroy("alice", evidenceKey, request));

        assertThat(statuses).containsOnly(409);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        // 只留一条 DENIED 记录，重放不产生第二条
        assertThat(chain.destructions()).hasSize(1);
        assertThat(chain.destructions().get(0).status()).isEqualTo(DestructionStatus.DENIED);
    }

    @Test
    void concurrentReleaseAndDestroyAreConsistent() throws Exception {
        fixClock();
        String caseKey = uniqueKey("CASE");
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, caseKey);
        String holdId = uniqueKey("HOLD");
        ResponseEntity<String> created = holdController.createCaseHold("bob", caseKey,
                new HoldCreateRequest(holdId, NOON, "待解除冻结"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        List<Integer> statuses = runConcurrently(
                () -> holdController.releaseHold("carol", holdId,
                        new HoldReleaseRequest(uniqueKey("REL"), "并发解除")),
                () -> evidenceController.destroy("alice", evidenceKey,
                        new DestructionCreateRequest(uniqueKey("DES"), "并发销毁")));

        var chain = evidenceService.custodyChain(evidenceKey);
        if (chain.evidence().status() == EvidenceStatus.DESTROYED) {
            // 解除先提交，销毁看到的已无有效冻结
            assertThat(statuses).contains(200);
            assertThat(chain.destructions().get(chain.destructions().size() - 1).status())
                    .isEqualTo(DestructionStatus.ALLOWED);
        } else {
            // 销毁先于解除提交：被 DENIED（409），解除成功（200），证物仍 SEALED
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(chain.destructions()).hasSize(1);
            assertThat(chain.destructions().get(0).status()).isEqualTo(DestructionStatus.DENIED);
            // 解除后新申请可以销毁
            ResponseEntity<String> after = evidenceController.destroy("alice", evidenceKey,
                    new DestructionCreateRequest(uniqueKey("DES"), "解除后销毁"));
            assertThat(after.getStatusCode().value()).isEqualTo(200);
        }
    }
}
