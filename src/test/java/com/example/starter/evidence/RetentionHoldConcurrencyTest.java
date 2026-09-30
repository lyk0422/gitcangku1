package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.DestructionRequest;
import com.example.starter.evidence.dto.HoldCreateRequest;
import com.example.starter.evidence.dto.HoldReleaseRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
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
 * 保留冻结与销毁并发/幂等边界测试（真实 H2 行锁）：
 * 冻结与销毁并发时不能出现冻结已生效而证物同时被销毁；
 * 同 requestId 竞争写入重放首次结果；解除与销毁并发的结果须与某种串行顺序一致。
 */
@SpringBootTest
class RetentionHoldConcurrencyTest {

    private static final Instant BASE = Instant.parse("2026-09-30T11:00:00Z");
    private static final LocalDateTime EXPIRE_LATE = LocalDateTime.parse("2026-09-30T18:00:00");

    @Autowired
    private RetentionHoldController controller;

    @Autowired
    private EvidenceService evidenceService;

    @Autowired
    private RetentionHoldService holdService;

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
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
    }

    @Autowired
    private EvidenceController evidenceController;

    private String intake(String actor, String evidenceKey, String caseKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, caseKey, "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return evidenceKey;
    }

    /**
     * 记录一次并发调用的 HTTP 状态码（业务异常取其携带状态码）。
     */
    private static final class StatusCall implements Callable<Integer> {
        private final CountDownLatch ready;
        private final CountDownLatch start;
        private final Callable<ResponseEntity<String>> call;

        StatusCall(CountDownLatch ready, CountDownLatch start,
                   Callable<ResponseEntity<String>> call) {
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
            return List.of(f1.get(20, TimeUnit.SECONDS), f2.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentEvidenceHoldAndDestructionNeverLeaveActiveHoldOnDestroyedEvidence()
            throws Exception {
        fixClock();
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, uniqueKey("CASE"));
        String holdKey = uniqueKey("HOLD");

        List<Integer> statuses = runConcurrently(
                () -> controller.createEvidenceHold("alice", evidenceKey,
                        new HoldCreateRequest(uniqueKey("CMD"), holdKey, EXPIRE_LATE, "并发冻结")),
                () -> controller.requestDestruction("alice", evidenceKey,
                        new DestructionRequest(uniqueKey("CMD"), "并发销毁")));

        // 证物行锁串行化：冻结先提交则销毁 409；销毁先提交则冻结 409。
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        var chain = evidenceService.custodyChain(evidenceKey);
        if (chain.evidence().status() == EvidenceStatus.DESTROYED) {
            // 销毁先提交：冻结不得生效
            assertThat(chain.evidenceHolds()).isEmpty();
            assertThat(chain.destructions()).hasSize(1);
            assertThat(chain.destructions().get(0).status()).isEqualTo(DestructionStatus.APPROVED);
        } else {
            // 冻结先提交：证物必须仍为 SEALED，销毁以 REJECTED 留档
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(chain.evidenceHolds()).hasSize(1);
            assertThat(chain.evidenceHolds().get(0).active()).isTrue();
            assertThat(chain.destructions()).hasSize(1);
            assertThat(chain.destructions().get(0).status()).isEqualTo(DestructionStatus.REJECTED);
            assertThat(chain.destructions().get(0).blockingHoldKey()).isEqualTo(holdKey);
        }
    }

    @Test
    void concurrentCaseHoldAndDestructionNeverLeaveActiveCaseHoldOnDestroyedEvidence()
            throws Exception {
        fixClock();
        String caseKey = uniqueKey("CASE");
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, caseKey);
        String holdKey = uniqueKey("HOLD");

        List<Integer> statuses = runConcurrently(
                () -> controller.createCaseHold("alice", caseKey,
                        new HoldCreateRequest(uniqueKey("CMD"), holdKey, EXPIRE_LATE, "案件并发冻结")),
                () -> controller.requestDestruction("alice", evidenceKey,
                        new DestructionRequest(uniqueKey("CMD"), "并发销毁")));

        // 案件冻结创建锁案件全部证物行，与该证物销毁互斥：恰好一方成功。
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        var chain = evidenceService.custodyChain(evidenceKey);
        if (chain.evidence().status() == EvidenceStatus.DESTROYED) {
            assertThat(chain.caseHolds()).isEmpty();
            assertThat(chain.destructions().get(0).status()).isEqualTo(DestructionStatus.APPROVED);
        } else {
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(chain.caseHolds()).hasSize(1);
            assertThat(chain.caseHolds().get(0).active()).isTrue();
            assertThat(chain.destructions()).hasSize(1);
            assertThat(chain.destructions().get(0).status()).isEqualTo(DestructionStatus.REJECTED);
            assertThat(chain.destructions().get(0).blockingHoldKey()).isEqualTo(holdKey);
        }
    }

    @Test
    void concurrentDuplicateEvidenceHoldWithSameRequestIdReplaysSingleResult() throws Exception {
        fixClock();
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, uniqueKey("CASE"));
        HoldCreateRequest request = new HoldCreateRequest(uniqueKey("CMD"), uniqueKey("HOLD"),
                EXPIRE_LATE, "竞争重放冻结");

        List<Integer> statuses = runConcurrently(
                () -> controller.createEvidenceHold("alice", evidenceKey, request),
                () -> controller.createEvidenceHold("alice", evidenceKey, request));

        assertThat(statuses).containsOnly(200);
        assertThat(holdService.listEvidenceHolds(evidenceKey).evidenceHolds()).hasSize(1);
    }

    @Test
    void concurrentDuplicateReleaseWithSameRequestIdReleasesExactlyOnce() throws Exception {
        fixClock();
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, uniqueKey("CASE"));
        String holdKey = uniqueKey("HOLD");
        assertThat(controller.createEvidenceHold("alice", evidenceKey,
                new HoldCreateRequest(uniqueKey("CMD"), holdKey, EXPIRE_LATE, "先建冻结"))
                .getStatusCode().value()).isEqualTo(200);
        // 保管权转给 bob，由不同于创建人 alice 的保管人解除
        assertThat(evidenceController.initiateTransfer("alice", evidenceKey,
                new com.example.starter.evidence.dto.TransferInitiateRequest(uniqueKey("CMD"), "bob"))
                .getStatusCode().value()).isEqualTo(200);
        assertThat(evidenceController.acceptTransfer("bob", evidenceKey,
                new com.example.starter.evidence.dto.CommandRequest(uniqueKey("CMD")))
                .getStatusCode().value()).isEqualTo(200);

        HoldReleaseRequest request = new HoldReleaseRequest(uniqueKey("CMD"), "并发竞争解除");
        List<Integer> statuses = runConcurrently(
                () -> controller.releaseHold("bob", holdKey, request),
                () -> controller.releaseHold("bob", holdKey, request));

        assertThat(statuses).containsOnly(200);
        var holds = holdService.listEvidenceHolds(evidenceKey).evidenceHolds();
        assertThat(holds).hasSize(1);
        assertThat(holds.get(0).state()).isEqualTo(HoldState.RELEASED);
        assertThat(holds.get(0).releasedBy()).isEqualTo("bob");
    }

    @Test
    void concurrentReleaseAndDestructionProduceSerializableOutcome() throws Exception {
        fixClock();
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, uniqueKey("CASE"));
        String holdKey = uniqueKey("HOLD");
        assertThat(controller.createEvidenceHold("alice", evidenceKey,
                new HoldCreateRequest(uniqueKey("CMD"), holdKey, EXPIRE_LATE, "先建冻结"))
                .getStatusCode().value()).isEqualTo(200);

        List<Integer> statuses = runConcurrently(
                () -> controller.releaseHold("alice", holdKey,
                        new HoldReleaseRequest(uniqueKey("CMD"), "创建人不能解除，必失败")),
                () -> controller.requestDestruction("alice", evidenceKey,
                        new DestructionRequest(uniqueKey("CMD"), "与解除并发")));

        // 创建人解除恒为 409，冻结未解除，销毁因此必为 409；证物保持 SEALED 并留一条 REJECTED。
        assertThat(statuses).containsOnly(409);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(chain.evidenceHolds().get(0).state()).isEqualTo(HoldState.ACTIVE);
        assertThat(chain.destructions()).hasSize(1);
        assertThat(chain.destructions().get(0).status()).isEqualTo(DestructionStatus.REJECTED);
    }

    @Test
    void concurrentTwoDestructionRequestsOnHeldEvidenceBothRejectedAndEvidenceStaysSealed()
            throws Exception {
        fixClock();
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, uniqueKey("CASE"));
        assertThat(controller.createEvidenceHold("alice", evidenceKey,
                new HoldCreateRequest(uniqueKey("CMD"), uniqueKey("HOLD"), EXPIRE_LATE, "冻结"))
                .getStatusCode().value()).isEqualTo(200);

        List<Integer> statuses = runConcurrently(
                () -> controller.requestDestruction("alice", evidenceKey,
                        new DestructionRequest(uniqueKey("CMD"), "销毁申请一")),
                () -> controller.requestDestruction("alice", evidenceKey,
                        new DestructionRequest(uniqueKey("CMD"), "销毁申请二")));

        // 两次申请各自留档 REJECTED（行锁串行判定，冻结对二者均生效）。
        assertThat(statuses).containsOnly(409);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(chain.destructions()).hasSize(2);
        assertThat(chain.destructions()).allMatch(d -> d.status() == DestructionStatus.REJECTED);
    }
}
