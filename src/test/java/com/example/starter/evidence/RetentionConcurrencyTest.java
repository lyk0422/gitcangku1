package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.DestructionSubmitRequest;
import com.example.starter.evidence.dto.HoldBatchReleaseRequest;
import com.example.starter.evidence.dto.HoldCreateRequest;
import com.example.starter.evidence.dto.HoldReleaseItem;
import com.example.starter.evidence.dto.IntakeRequest;
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
 * 保全冻结/销毁并发与幂等边界测试（真实 H2 数据库）：
 * 冻结、销毁申请、完成销毁、解除并发时按证物行锁上的提交顺序裁决，
 * 不允许出现冻结与已销毁并存、或待审申请漏阻断等半成品状态。
 */
@SpringBootTest
class RetentionConcurrencyTest {

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private RetentionController retentionController;

    @Autowired
    private RetentionService retentionService;

    @Autowired
    private EvidenceClock evidenceClock;

    private LocalDateTime now;
    private LocalDateTime farFuture;

    @BeforeEach
    void setUp() {
        Instant fixed = Instant.parse("2026-09-26T00:00:00Z");
        evidenceClock.setClock(Clock.fixed(fixed, ZoneOffset.UTC));
        now = LocalDateTime.ofInstant(fixed, ZoneOffset.UTC);
        farFuture = now.plusDays(30);
    }

    @AfterEach
    void tearDown() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private HoldCreateRequest holdRequest(String holdId, List<String> evidenceKeys) {
        return new HoldCreateRequest(uniqueKey("CMD"), holdId, "CASE-9",
                "litigation-hold", now, farFuture, evidenceKeys);
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
    void concurrentOverlappingHoldsExactlyOneCreated() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String hold1 = uniqueKey("HOLD");
        String hold2 = uniqueKey("HOLD");

        List<Integer> statuses = runConcurrently(
                () -> retentionController.createHold("alice", holdRequest(hold1, List.of(ev))),
                () -> retentionController.createHold("alice", holdRequest(hold2, List.of(ev))));

        assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        assertThat(retentionService.listEffectiveHolds(ev)).hasSize(1);
    }

    @Test
    void concurrentDuplicateHoldWithSameCommandKeyCreatesSingleHold() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        HoldCreateRequest request = holdRequest(uniqueKey("HOLD"), List.of(ev));

        List<Integer> statuses = runConcurrently(
                () -> retentionController.createHold("alice", request),
                () -> retentionController.createHold("alice", request));

        assertThat(statuses).containsOnly(201);
        assertThat(retentionService.listEffectiveHolds(ev)).hasSize(1);
    }

    @Test
    void concurrentHoldCreateAndDestructionSubmitArbitratedWithoutInconsistentState() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String holdId = uniqueKey("HOLD");
        String requestKey = uniqueKey("DREQ");
        DestructionSubmitRequest submit = new DestructionSubmitRequest(
                uniqueKey("CMD"), requestKey, List.of(ev));

        List<Integer> statuses = runConcurrently(
                () -> retentionController.createHold("alice", holdRequest(holdId, List.of(ev))),
                () -> retentionController.submitDestruction("alice", submit));

        assertThat(statuses).allMatch(s -> s == 201 || s == 422);
        // 冻结必定建立成功
        assertThat(retentionService.listEffectiveHolds(ev)).hasSize(1);

        // 申请要么未生成（销毁先读到冻结 -> 422），要么已被阻断（销毁先提交 -> 冻结随后阻断）；
        // 绝不允许存在仍为 PENDING 的申请与有效冻结并存。
        var history = retentionService.listDestructionHistory(ev);
        if (history.isEmpty()) {
            assertThat(statuses).contains(422);
        } else {
            assertThat(history).hasSize(1);
            assertThat(history.get(0).status()).isEqualTo(DestructionStatus.HOLD_BLOCKED);
            assertThat(history.get(0).blockedHolds()).hasSize(1);
            assertThat(history.get(0).blockedHolds().get(0).holdId()).isEqualTo(holdId);
        }
    }

    @Test
    void concurrentCompleteAndHoldCreateNeverDestroysUnderHold() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String requestKey = uniqueKey("DREQ");
        ResponseEntity<String> submitted = retentionController.submitDestruction("alice",
                new DestructionSubmitRequest(uniqueKey("CMD"), requestKey, List.of(ev)));
        assertThat(submitted.getStatusCode().value()).isEqualTo(201);
        String holdId = uniqueKey("HOLD");

        List<Integer> statuses = runConcurrently(
                () -> retentionController.completeDestruction("alice", requestKey,
                        new CommandRequest(uniqueKey("CMD"))),
                () -> retentionController.createHold("alice", holdRequest(holdId, List.of(ev))));

        // 完成销毁可能先读到 PENDING（随后命中冻结返回 422），或先读到已阻断（409），均为可区分结果。
        assertThat(statuses).allMatch(s -> s == 200 || s == 201 || s == 422 || s == 409);
        var chain = retentionService;
        var destructionHistory = chain.listDestructionHistory(ev);
        var effectiveHolds = chain.listEffectiveHolds(ev);

        boolean destroyed = destructionHistory.stream()
                .anyMatch(d -> d.status() == DestructionStatus.DESTROYED);
        if (destroyed) {
            // 完成销毁先提交：冻结不得补建到已销毁证物
            assertThat(effectiveHolds).isEmpty();
            assertThat(statuses).contains(422);
        } else {
            // 冻结先提交：完成销毁被 422，且待审申请已转 HOLD_BLOCKED
            assertThat(effectiveHolds).hasSize(1);
            assertThat(destructionHistory.get(0).status())
                    .isEqualTo(DestructionStatus.HOLD_BLOCKED);
        }
    }

    @Test
    void concurrentReleaseAndDestructionSubmitLeavesNoPendingRequestUnderActiveHold() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String holdId = uniqueKey("HOLD");
        ResponseEntity<String> held = retentionController.createHold(
                "alice", holdRequest(holdId, List.of(ev)));
        assertThat(held.getStatusCode().value()).isEqualTo(201);
        String requestKey = uniqueKey("DREQ");

        HoldBatchReleaseRequest release = new HoldBatchReleaseRequest(
                uniqueKey("CMD"), List.of(new HoldReleaseItem(holdId, 1)));
        DestructionSubmitRequest submit = new DestructionSubmitRequest(
                uniqueKey("CMD"), requestKey, List.of(ev));

        List<Integer> statuses = runConcurrently(
                () -> retentionController.batchRelease("alice", release),
                () -> retentionController.submitDestruction("alice", submit));

        assertThat(statuses.get(0)).isEqualTo(200);
        assertThat(statuses.get(1)).isIn(201, 422);

        var history = retentionService.listDestructionHistory(ev);
        if (statuses.get(1) == 201) {
            // 解除先于冻结检查生效：申请存在且为 PENDING，阻断快照为空，冻结当前已解除
            assertThat(history).hasSize(1);
            assertThat(history.get(0).status()).isEqualTo(DestructionStatus.PENDING);
            assertThat(history.get(0).blockedHolds()).isEmpty();
        } else {
            assertThat(history).isEmpty();
        }
        // 冻结最终一定已解除
        assertThat(retentionService.listEffectiveHolds(ev)).isEmpty();
    }
}
