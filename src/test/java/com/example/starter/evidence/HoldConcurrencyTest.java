package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.error.HoldBlockedException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.DestructionSubmitRequest;
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
 * 冻结与销毁并发边界测试（真实 H2 行锁与事务）：
 * 并发冻结按提交顺序裁决（重叠仅一笔成功）、同键并发重放单一结果、
 * 销毁申请与冻结创建并发时不产生半阻断状态、并发完成销毁仅一次生效。
 */
@SpringBootTest
class HoldConcurrencyTest {

    private static final Instant BASE = Instant.parse("2026-09-23T08:00:00Z");

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private HoldController holdController;

    @Autowired
    private HoldService holdService;

    @Autowired
    private EvidenceClock evidenceClock;

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private LocalDateTime utc(long seconds) {
        return LocalDateTime.ofInstant(BASE.plusSeconds(seconds), ZoneOffset.UTC);
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private Callable<ResponseEntity<String>> createHold(String actor, String holdKey,
                                                        String evidenceKey) {
        return () -> holdController.createHold(actor,
                new HoldCreateRequest(holdKey, "CASE-9", List.of(evidenceKey),
                        utc(0), utc(3600), "并发冻结"));
    }

    private Callable<ResponseEntity<String>> submit(String actor, String requestKey,
                                                    String evidenceKey) {
        return () -> holdController.submitDestruction(actor,
                new DestructionSubmitRequest(requestKey, List.of(evidenceKey), "并发销毁"));
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
            } catch (HoldBlockedException e) {
                return e.status().value();
            }
        }
    }

    private List<Integer> runConcurrently(Callable<ResponseEntity<String>> first,
                                          Callable<ResponseEntity<String>> second)
            throws Exception {
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
    void concurrentOverlappingHoldsOnlyOneCreated() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        List<Integer> statuses = runConcurrently(
                createHold("alice", uniqueKey("HOLD"), evidenceKey),
                createHold("alice", uniqueKey("HOLD"), evidenceKey));

        // 同一证物重叠有效冻结：恰一笔成功，另一笔按提交顺序被拒绝
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(holdService.listHoldHistory(evidenceKey)).hasSize(1);
        assertThat(holdService.listEffectiveHolds(evidenceKey)).hasSize(1);
    }

    @Test
    void concurrentSameHoldKeyReplaysSingleResult() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        String holdKey = uniqueKey("HOLD");
        intake("alice", evidenceKey);
        HoldCreateRequest request = new HoldCreateRequest(holdKey, "CASE-9",
                List.of(evidenceKey), utc(0), utc(3600), "幂等并发冻结");

        List<Integer> statuses = runConcurrently(
                () -> holdController.createHold("alice", request),
                () -> holdController.createHold("alice", request));

        assertThat(statuses).containsOnly(201);
        assertThat(holdService.listHoldHistory(evidenceKey)).hasSize(1);
    }

    @Test
    void concurrentSubmitAndHoldCreateResolveByCommitOrder() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        String requestKey = uniqueKey("REQ");
        intake("alice", evidenceKey);

        List<Integer> statuses = runConcurrently(
                submit("alice", requestKey, evidenceKey),
                createHold("alice", uniqueKey("HOLD"), evidenceKey));

        // 冻结创建必成功；销毁申请按提交顺序：先于冻结则 201，晚于冻结则 422
        assertThat(statuses.get(1)).isEqualTo(201);
        assertThat(statuses.get(0)).isIn(201, 422);

        var requests = holdService.listDestructionRequests(evidenceKey);
        if (statuses.get(0) == 201) {
            // 申请先提交：冻结生效后同事务转为 HOLD_BLOCKED，不留待审漏网
            assertThat(requests).hasSize(1);
            assertThat(requests.get(0).status()).isEqualTo(DestructionStatus.HOLD_BLOCKED);
            assertThat(requests.get(0).blockedHoldKeys()).hasSize(1);
        } else {
            // 冻结先提交：申请被 422 拒绝，不生成任何申请记录
            assertThat(requests).isEmpty();
        }
        assertThat(holdService.listEffectiveHolds(evidenceKey)).hasSize(1);
    }

    @Test
    void concurrentReleaseAndSubmitResolveByCommitOrder() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        String holdKey = uniqueKey("HOLD");
        String requestKey = uniqueKey("REQ");
        intake("alice", evidenceKey);
        ResponseEntity<String> created = holdController.createHold("alice",
                new HoldCreateRequest(holdKey, "CASE-9", List.of(evidenceKey),
                        utc(0), utc(3600), "冻结"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        List<Integer> statuses = runConcurrently(
                submit("alice", requestKey, evidenceKey),
                () -> holdController.releaseHolds("alice",
                        new HoldReleaseRequest(uniqueKey("CMD"),
                                List.of(new HoldReleaseRequest.HoldReleaseItem(holdKey, 1)))));

        // 解除必成功；申请按提交顺序：解除先提交则 201，否则 422
        assertThat(statuses.get(1)).isEqualTo(200);
        assertThat(statuses.get(0)).isIn(201, 422);

        var requests = holdService.listDestructionRequests(evidenceKey);
        if (statuses.get(0) == 201) {
            assertThat(requests).hasSize(1);
            assertThat(requests.get(0).status()).isEqualTo(DestructionStatus.PENDING);
        } else {
            assertThat(requests).isEmpty();
        }
        assertThat(holdService.listEffectiveHolds(evidenceKey)).isEmpty();
    }

    @Test
    void concurrentCompletesOnlyOneSucceeds() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        String requestKey = uniqueKey("REQ");
        intake("alice", evidenceKey);
        ResponseEntity<String> submitted = holdController.submitDestruction("alice",
                new DestructionSubmitRequest(requestKey, List.of(evidenceKey), "销毁"));
        assertThat(submitted.getStatusCode().value()).isEqualTo(201);

        // 两个独立命令并发完成同一申请（不同 commandKey，非幂等重放）
        List<Integer> statuses = runConcurrently(
                () -> holdController.completeDestruction("alice", requestKey,
                        new CommandRequest(uniqueKey("CMD"))),
                () -> holdController.completeDestruction("alice", requestKey,
                        new CommandRequest(uniqueKey("CMD"))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var requests = holdService.listDestructionRequests(evidenceKey);
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).status()).isEqualTo(DestructionStatus.COMPLETED);
        assertThat(evidenceController.custodyChain(evidenceKey).evidence().status())
                .isEqualTo(EvidenceStatus.DESTROYED);
    }
}
