package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.ContainerInspectionRequest;
import com.example.starter.evidence.dto.ContainerReviewRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
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
 * 容器巡检并发边界测试（真实 H2 行锁与事务）：
 * FAIL 巡检与新借出、装载并发按事务提交顺序裁决；同 inspectKey 并发只产生一次首次结果；
 * 两名复核人并发恰好促成一次恢复；同一复核人并发只允许一次。
 */
@SpringBootTest
class ContainerConcurrencyTest {

    private static final Instant BASE = Instant.parse("2026-09-26T08:00:00Z");

    @Autowired
    private ContainerController containerController;

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private ContainerService containerService;

    @Autowired
    private EvidenceService evidenceService;

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
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-C", "DOCUMENT", "SEAL-C"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private String setupContainerWithEvidence(String evidenceKey) {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        intake("alice", evidenceKey);
        String containerKey = uniqueKey("BOX");
        assertThat(containerController.create("alice",
                new com.example.starter.evidence.dto.ContainerCreateRequest(
                        uniqueKey("CMD"), containerKey, utc(86400))).getStatusCode().value())
                .isEqualTo(201);
        ResponseEntity<String> loaded = containerController.load("alice", containerKey,
                new com.example.starter.evidence.dto.ContainerLoadRequest(
                        uniqueKey("CMD"), List.of(evidenceKey)));
        assertThat(loaded.getStatusCode().value()).isEqualTo(200);
        return containerKey;
    }

    private Callable<ResponseEntity<String>> failInspect(String actor, String containerKey,
                                                         String commandKey) {
        ContainerInspectionRequest request = new ContainerInspectionRequest(
                commandKey, utc(100), "FAIL", "并发封签撕裂", utc(90000));
        return () -> containerController.inspect(actor, containerKey, request);
    }

    private Callable<ResponseEntity<String>> passInspect(String actor, String containerKey,
                                                         String commandKey) {
        ContainerInspectionRequest request = new ContainerInspectionRequest(
                commandKey, utc(100), "PASS", "例行", utc(90000));
        return () -> containerController.inspect(actor, containerKey, request);
    }

    private Callable<ResponseEntity<String>> borrow(String actor, String evidenceKey,
                                                    String borrower) {
        return () -> evidenceController.borrow(actor, evidenceKey,
                new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("LOAN"), borrower,
                        "鉴定用", utc(3600)));
    }

    private Callable<ResponseEntity<String>> review(String reviewer, String containerKey,
                                                    String commandKey) {
        ContainerReviewRequest request = new ContainerReviewRequest(
                commandKey, "并发复核封签完好", utc(2000));
        return () -> containerController.review(reviewer, containerKey, request);
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
            return List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 直接执行一次调用并返回其 HTTP 状态码（业务异常取携带状态码），用于并发之后的串行断言。
     */
    private int statusOf(Callable<ResponseEntity<String>> call) throws Exception {
        try {
            return call.call().getStatusCode().value();
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    @Test
    void concurrentFailInspectionAndBorrowResolvedByCommitOrder() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String containerKey = setupContainerWithEvidence(evidenceKey);

        List<Integer> statuses = runConcurrently(
                failInspect("alice", containerKey, uniqueKey("CMD")),
                borrow("alice", evidenceKey, "bob"));

        // 按提交顺序：恰一个成功（200），另一个被阻断/回滚（409）
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        var container = containerService.getContainer(containerKey);
        var pending = containerService.listPendingVerification(containerKey);
        var inspections = containerService.listInspections(containerKey);
        var chain = evidenceStatus(evidenceKey);
        if (container.status() == ContainerStatus.INSPECTION_FAILED) {
            // 巡检先提交：证物待核验，无 ACTIVE 借出，有一批快照
            assertThat(chain).isEqualTo(EvidenceStatus.PENDING_VERIFICATION);
            assertThat(pending).hasSize(1);
            assertThat(inspections).hasSize(1);
            assertThat(inspections.get(0).snapshots()).hasSize(1);
            assertThat(activeLoans(evidenceKey)).isZero();
        } else {
            // 借出先提交：FAIL 巡检发现借出证物不可标记而整单回滚，无记录无快照
            assertThat(container.status()).isEqualTo(ContainerStatus.SEALED);
            assertThat(chain).isEqualTo(EvidenceStatus.BORROWED);
            assertThat(pending).isEmpty();
            assertThat(inspections).isEmpty();
            assertThat(activeLoans(evidenceKey)).isEqualTo(1);
        }
    }

    @Test
    void concurrentSameInspectKeyReplaysSingleFirstResult() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String containerKey = setupContainerWithEvidence(evidenceKey);
        String inspectKey = uniqueKey("CMD");

        List<Integer> statuses = runConcurrently(
                failInspect("alice", containerKey, inspectKey),
                failInspect("alice", containerKey, inspectKey));

        assertThat(statuses).containsOnly(200);

        var container = containerService.getContainer(containerKey);
        assertThat(container.status()).isEqualTo(ContainerStatus.INSPECTION_FAILED);
        var inspections = containerService.listInspections(containerKey);
        assertThat(inspections).hasSize(1);
        assertThat(inspections.get(0).snapshots()).hasSize(1);
        assertThat(containerService.listPendingVerification(containerKey)).hasSize(1);
    }

    @Test
    void concurrentTwoDifferentReviewersRestoreExactlyOnce() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String containerKey = setupContainerWithEvidence(evidenceKey);
        assertThat(failInspect("alice", containerKey, uniqueKey("CMD")).call()
                .getStatusCode().value()).isEqualTo(200);

        List<Integer> statuses = runConcurrently(
                review("bob", containerKey, uniqueKey("CMD")),
                review("carol", containerKey, uniqueKey("CMD")));

        assertThat(statuses).containsOnly(200);

        var container = containerService.getContainer(containerKey);
        assertThat(container.status()).isEqualTo(ContainerStatus.SEALED);
        assertThat(containerService.listReviews(containerKey)).hasSize(2);
        assertThat(containerService.listPendingVerification(containerKey)).isEmpty();
        assertThat(evidenceStatus(evidenceKey)).isEqualTo(EvidenceStatus.SEALED);
        // 恢复后借出持续阻断解除
        assertThat(borrow("alice", evidenceKey, "dave").call().getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    void concurrentSameReviewerOnlyOneAcceptedAndContainerStaysFailed() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String containerKey = setupContainerWithEvidence(evidenceKey);
        assertThat(failInspect("alice", containerKey, uniqueKey("CMD")).call()
                .getStatusCode().value()).isEqualTo(200);

        // 同一复核人两个独立命令并发：唯一约束 + 持锁复查只允许一次
        List<Integer> statuses = runConcurrently(
                review("bob", containerKey, uniqueKey("CMD")),
                review("bob", containerKey, uniqueKey("CMD")));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        var container = containerService.getContainer(containerKey);
        assertThat(container.status()).isEqualTo(ContainerStatus.INSPECTION_FAILED);
        assertThat(containerService.listReviews(containerKey)).hasSize(1);
        // 单人复核后借出门禁仍持续
        assertThat(statusOf(borrow("alice", evidenceKey, "carol"))).isEqualTo(409);
    }

    @Test
    void concurrentFailInspectionAndLoadResolvedByCommitOrder() throws Exception {
        String evIn = uniqueKey("EV");
        String evOut = uniqueKey("EV");
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        intake("alice", evIn);
        intake("alice", evOut);
        String containerKey = uniqueKey("BOX");
        containerController.create("alice",
                new com.example.starter.evidence.dto.ContainerCreateRequest(
                        uniqueKey("CMD"), containerKey, utc(86400)));
        containerController.load("alice", containerKey,
                new com.example.starter.evidence.dto.ContainerLoadRequest(
                        uniqueKey("CMD"), List.of(evIn)));

        Callable<ResponseEntity<String>> load = () -> containerController.load("alice",
                containerKey,
                new com.example.starter.evidence.dto.ContainerLoadRequest(
                        uniqueKey("CMD"), List.of(evIn, evOut)));

        List<Integer> statuses = runConcurrently(
                failInspect("alice", containerKey, uniqueKey("CMD")), load);

        var container = containerService.getContainer(containerKey);
        if (statuses.contains(409)) {
            // FAIL 巡检先提交：装载看到 INSPECTION_FAILED 被拒，集合未变
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(container.status()).isEqualTo(ContainerStatus.INSPECTION_FAILED);
            assertThat(container.evidenceKeys()).containsExactly(evIn);
            assertThat(containerService.listPendingVerification(containerKey))
                    .extracting(v -> v.evidenceKey()).containsExactly(evIn);
        } else {
            // 装载先提交：FAIL 巡检随后成功，两件证物待核验，容器 FAIL
            assertThat(statuses).containsOnly(200);
            assertThat(container.status()).isEqualTo(ContainerStatus.INSPECTION_FAILED);
            assertThat(container.evidenceKeys()).containsExactlyInAnyOrder(evIn, evOut);
            assertThat(containerService.listPendingVerification(containerKey))
                    .extracting(v -> v.evidenceKey()).containsExactlyInAnyOrder(evIn, evOut);
        }
    }

    @Test
    void concurrentPassAndFailWithDifferentKeysResolvedByCommitOrder() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String containerKey = setupContainerWithEvidence(evidenceKey);

        List<Integer> statuses = runConcurrently(
                passInspect("alice", containerKey, uniqueKey("CMD")),
                failInspect("alice", containerKey, uniqueKey("CMD")));

        // 容器行锁串行裁决：FAIL 先提交则 PASS 在 FAIL 容器上被拒（409）；
        // PASS 先提交则 FAIL 随后仍可在 SEALED 容器执行成功（200）。最终容器必然 FAIL。
        var inspections = containerService.listInspections(containerKey);
        var container = containerService.getContainer(containerKey);
        assertThat(container.status()).isEqualTo(ContainerStatus.INSPECTION_FAILED);
        if (inspections.size() == 1) {
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(inspections.get(0).result()).isEqualTo(SealResult.FAIL);
        } else {
            assertThat(inspections).hasSize(2);
            assertThat(statuses).containsOnly(200);
            assertThat(inspections.get(0).result()).isEqualTo(SealResult.PASS);
            assertThat(inspections.get(1).result()).isEqualTo(SealResult.FAIL);
        }
    }

    private EvidenceStatus evidenceStatus(String evidenceKey) {
        return evidenceService.custodyChain(evidenceKey).evidence().status();
    }

    private long activeLoans(String evidenceKey) {
        return evidenceService.custodyChain(evidenceKey).loans().stream()
                .filter(loan -> loan.status() == LoanStatus.ACTIVE)
                .count();
    }
}
