package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.DestructionApplyRequest;
import com.example.starter.evidence.dto.HoldCreateRequest;
import com.example.starter.evidence.dto.HoldReleaseRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * 冻结与销毁并发/竞争写入测试（真实 H2，SELECT ... FOR UPDATE 行锁）：
 * 冻结与销毁并发时不允许留下"冻结已生效而证物同时被销毁"；同 requestId 并发重放只产生一条首记录。
 */
@SpringBootTest
class RetentionConcurrencyTest {

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private RetentionController controller;

    @Autowired
    private EvidenceService evidenceService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EvidenceClock evidenceClock;

    @BeforeEach
    void fixClock() {
        evidenceClock.setClock(Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey, String caseKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, caseKey, "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

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
            start.await(10, TimeUnit.SECONDS);
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
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentEvidenceHoldAndDestructionNeverLeaveEffectiveHoldWithDestroyedEvidence()
            throws Exception {
        String evidenceKey = uniqueKey("EV");
        String caseKey = uniqueKey("CASE");
        intake("alice", evidenceKey, caseKey);

        HoldCreateRequest holdRequest =
                new HoldCreateRequest(uniqueKey("HOLD"),
                        LocalDateTime.parse("2026-10-01T18:00:00"), "并发冻结");
        DestructionApplyRequest destroyRequest =
                new DestructionApplyRequest(uniqueKey("DEST"), "并发销毁");

        List<Integer> statuses = runConcurrently(
                () -> controller.placeEvidenceHold("alice", evidenceKey, holdRequest),
                () -> controller.applyDestruction("alice", evidenceKey, destroyRequest));

        // 冻结先提交则冻结 201、销毁被拒 409；销毁先提交则销毁 200、冻结因证物已销毁 409。
        // 任何提交顺序下都恰有一个成功、另一个冲突，绝不双双成功。
        long successCount = statuses.stream().filter(s -> s == 200 || s == 201).count();
        assertThat(successCount).as("恰一个操作成功").isEqualTo(1);
        assertThat(statuses).contains(409);

        var chain = evidenceService.custodyChain(evidenceKey);
        boolean destroyed = chain.evidence().status() == EvidenceStatus.DESTROYED;
        boolean effectiveHoldExists = chain.holds().stream().anyMatch(h -> h.effective());
        // 核心不变式：冻结生效与证物销毁不可同时成立
        assertThat(destroyed && effectiveHoldExists)
                .as("不得同时存在有效冻结与已销毁证物").isFalse();
        if (destroyed) {
            // 销毁先提交：冻结必须被拒，无冻结记录
            assertThat(chain.holds()).isEmpty();
            assertThat(chain.destructions()).hasSize(1);
            assertThat(chain.destructions().get(0).status()).isEqualTo(DestructionStatus.APPROVED);
        } else {
            // 冻结先提交：销毁必须被拒且证物仍 SEALED
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(chain.holds()).hasSize(1);
            assertThat(chain.destructions()).hasSize(1);
            assertThat(chain.destructions().get(0).status()).isEqualTo(DestructionStatus.REJECTED);
        }
    }

    @Test
    void concurrentCaseHoldAndDestructionAreSerializable() throws Exception {
        String caseKey = uniqueKey("CASE");
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, caseKey);

        HoldCreateRequest holdRequest =
                new HoldCreateRequest(uniqueKey("HOLD"),
                        LocalDateTime.parse("2026-10-01T18:00:00"), "案件并发冻结");
        DestructionApplyRequest destroyRequest =
                new DestructionApplyRequest(uniqueKey("DEST"), "并发销毁");

        List<Integer> statuses = runConcurrently(
                () -> controller.placeCaseHold("alice", caseKey, holdRequest),
                () -> controller.applyDestruction("alice", evidenceKey, destroyRequest));

        long successCount = statuses.stream().filter(s -> s == 200 || s == 201).count();
        assertThat(successCount).as("恰一个操作成功").isEqualTo(1);
        assertThat(statuses).contains(409);

        var chain = evidenceService.custodyChain(evidenceKey);
        boolean destroyed = chain.evidence().status() == EvidenceStatus.DESTROYED;
        assertThat(destroyed && chain.holds().stream().anyMatch(h -> h.effective()))
                .as("案件级冻结与销毁不得同时生效").isFalse();
    }

    @Test
    void concurrentDuplicateHoldWithSameRequestIdCreatesSingleHold() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String caseKey = uniqueKey("CASE");
        intake("alice", evidenceKey, caseKey);

        HoldCreateRequest request = new HoldCreateRequest(uniqueKey("HOLD"),
                LocalDateTime.parse("2026-10-01T18:00:00"), "并发重复冻结");

        List<Integer> statuses = runConcurrently(
                () -> controller.placeEvidenceHold("alice", evidenceKey, request),
                () -> controller.placeEvidenceHold("alice", evidenceKey, request));

        assertThat(statuses).containsOnly(201);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.holds()).hasSize(1);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
    }

    @Test
    void concurrentDuplicateDestructionWithSameRequestIdApprovesOnce() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String caseKey = uniqueKey("CASE");
        intake("alice", evidenceKey, caseKey);

        DestructionApplyRequest request =
                new DestructionApplyRequest(uniqueKey("DEST"), "并发重复销毁");

        List<Integer> statuses = runConcurrently(
                () -> controller.applyDestruction("alice", evidenceKey, request),
                () -> controller.applyDestruction("alice", evidenceKey, request));

        // 无冻结：首个批准（200），并发重复重放首个 200
        assertThat(statuses).containsOnly(200);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.DESTROYED);
        assertThat(chain.destructions()).hasSize(1);
        assertThat(chain.destructions().get(0).status()).isEqualTo(DestructionStatus.APPROVED);
    }

    @Test
    void concurrentReleasesOfSameHoldExactlyOneSucceeds() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String caseKey = uniqueKey("CASE");
        intake("alice", evidenceKey, caseKey);

        ResponseEntity<String> holdResponse = controller.placeEvidenceHold("alice", evidenceKey,
                new HoldCreateRequest(uniqueKey("HOLD"),
                        LocalDateTime.parse("2026-10-01T18:00:00"), "冻结"));
        assertThat(holdResponse.getStatusCode().value()).isEqualTo(201);
        long holdId = objectMapper.readTree(holdResponse.getBody()).get("id").asLong();

        List<Integer> statuses = runConcurrently(
                () -> controller.releaseHold("bob", holdId,
                        new HoldReleaseRequest(uniqueKey("REL"), "解除一")),
                () -> controller.releaseHold("carol", holdId,
                        new HoldReleaseRequest(uniqueKey("REL2"), "解除二")));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.holds()).hasSize(1);
        assertThat(chain.holds().get(0).status()).isEqualTo(HoldStatus.RELEASED);
    }

    @Test
    void concurrentDuplicateReleaseWithSameRequestIdReplaysFirstRelease() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String caseKey = uniqueKey("CASE");
        intake("alice", evidenceKey, caseKey);

        ResponseEntity<String> holdResponse = controller.placeEvidenceHold("alice", evidenceKey,
                new HoldCreateRequest(uniqueKey("HOLD"),
                        LocalDateTime.parse("2026-10-01T18:00:00"), "冻结"));
        long holdId = objectMapper.readTree(holdResponse.getBody()).get("id").asLong();

        HoldReleaseRequest request = new HoldReleaseRequest(uniqueKey("REL"), "同一解除原因");

        List<Integer> statuses = runConcurrently(
                () -> controller.releaseHold("bob", holdId, request),
                () -> controller.releaseHold("bob", holdId, request));

        // 同 requestId 并发解除：首次 200，等待行锁者重放首次 200，只解除一次
        assertThat(statuses).containsOnly(200);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.holds()).hasSize(1);
        assertThat(chain.holds().get(0).status()).isEqualTo(HoldStatus.RELEASED);
        assertThat(chain.holds().get(0).releasedBy()).isEqualTo("bob");
    }
}
