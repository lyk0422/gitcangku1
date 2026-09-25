package com.example.starter.container;

import com.example.starter.container.dto.ContainerCreateRequest;
import com.example.starter.container.dto.ContainerInspectRequest;
import com.example.starter.container.dto.ContainerItemsRequest;
import com.example.starter.container.dto.ContainerReviewRequest;
import com.example.starter.error.ApiException;
import com.example.starter.evidence.EvidenceStatus;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.TransferInitiateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

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
 * 容器巡检并发与幂等边界测试，全部使用真实 H2(MODE=MySQL) 内存库与真实事务：
 * FAIL 巡检与借出/迁移并发按事务提交顺序裁决；同 inspectKey 并发重放只产生一次完整结果。
 */
@SpringBootTest
class ContainerConcurrencyTest {

    @Autowired
    private ContainerController containerController;

    @Autowired
    private ContainerService containerService;

    @Autowired
    private com.example.starter.evidence.EvidenceController evidenceController;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private LocalDateTime utc(int plusHours) {
        return LocalDateTime.now(ZoneOffset.UTC).plusHours(plusHours);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-C", "DOCUMENT",
                        "SEAL-" + evidenceKey));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private String createContainer() {
        String containerId = uniqueKey("BOX");
        ResponseEntity<String> response = containerController.create("alice",
                new ContainerCreateRequest(uniqueKey("CMD"), containerId, utc(24 * 30)));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return containerId;
    }

    private void load(String containerId, List<String> evidenceKeys) {
        ResponseEntity<String> response = containerController.load("alice", containerId,
                new ContainerItemsRequest(uniqueKey("CMD"), evidenceKeys));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
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
    void concurrentFailInspectionAndBorrowDecidedByCommitOrder() throws Exception {
        String containerId = createContainer();
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        load(containerId, List.of(ev1));

        ContainerInspectRequest fail = new ContainerInspectRequest(uniqueKey("INSP"), 1L,
                utc(0), utc(48), "FAIL", "strap cut");
        LoanCreateRequest loan = new LoanCreateRequest(uniqueKey("LOAN"), uniqueKey("LOAN"),
                "bob", "lab", utc(2));

        List<Integer> statuses = runConcurrently(
                () -> containerController.inspect("alice", containerId, fail),
                () -> evidenceController.borrow("alice", ev1, loan));

        assertThat(statuses).allMatch(s -> s == 200 || s == 409);
        long successCount = statuses.stream().filter(s -> s == 200).count();
        assertThat(successCount).isEqualTo(1);

        var detail = containerService.containerDetail(containerId);
        var evidence = containerService.blockReason(ev1);
        if (detail.container().status().equals("INSPECTION_FAILED")) {
            // FAIL 先提交：借出必须被阻断，证物待核验，巡检与快照各一条
            assertThat(statuses).contains(200, 409);
            assertThat(evidence.blocked()).isTrue();
            assertThat(detail.inspections()).hasSize(1);
            assertThat(detail.snapshots()).hasSize(1);
            var pending = containerService.listPendingVerification(null);
            assertThat(pending).anyMatch(v -> v.evidenceKey().equals(ev1)
                    && v.status().equals(EvidenceStatus.PENDING_VERIFICATION.name()));
        } else {
            // 借出先提交：FAIL 整单回滚，容器仍 SEALED，无巡检与快照
            assertThat(detail.container().status()).isEqualTo("SEALED");
            assertThat(detail.inspections()).isEmpty();
            assertThat(detail.snapshots()).isEmpty();
            assertThat(evidence.blocked()).isFalse();
        }
    }

    @Test
    void concurrentFailInspectionAndTransferDecidedByCommitOrder() throws Exception {
        String containerId = createContainer();
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        load(containerId, List.of(ev1));

        ContainerInspectRequest fail = new ContainerInspectRequest(uniqueKey("INSP"), 1L,
                utc(0), utc(48), "FAIL", "strap cut");
        TransferInitiateRequest transfer = new TransferInitiateRequest(uniqueKey("CMD"), "bob");

        List<Integer> statuses = runConcurrently(
                () -> containerController.inspect("alice", containerId, fail),
                () -> evidenceController.initiateTransfer("alice", ev1, transfer));

        assertThat(statuses).allMatch(s -> s == 200 || s == 409);
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);

        var detail = containerService.containerDetail(containerId);
        if ("INSPECTION_FAILED".equals(detail.container().status())) {
            assertThat(containerService.blockReason(ev1).blocked()).isTrue();
        } else {
            assertThat(detail.inspections()).isEmpty();
            assertThat(detail.snapshots()).isEmpty();
        }
    }

    @Test
    void concurrentDuplicateInspectWithSameKeyCommitsSingleResult() throws Exception {
        String containerId = createContainer();
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        load(containerId, List.of(ev1, ev2));

        ContainerInspectRequest request = new ContainerInspectRequest(uniqueKey("INSP"), 1L,
                utc(0), utc(48), "FAIL", "strap cut");

        List<Integer> statuses = runConcurrently(
                () -> containerController.inspect("alice", containerId, request),
                () -> containerController.inspect("alice", containerId, request));

        assertThat(statuses).containsOnly(200);
        var detail = containerService.containerDetail(containerId);
        assertThat(detail.container().status()).isEqualTo("INSPECTION_FAILED");
        // 并发同键重放：只有一条巡检记录、每件证物只有一张快照
        assertThat(detail.inspections()).hasSize(1);
        assertThat(detail.snapshots()).hasSize(2);
    }

    @Test
    void concurrentReviewsByTwoCustodiansRestoreExactlyOnce() throws Exception {
        String containerId = createContainer();
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        load(containerId, List.of(ev1));
        ResponseEntity<String> failed = containerController.inspect("alice", containerId,
                new ContainerInspectRequest(uniqueKey("INSP"), 1L, utc(0), utc(48),
                        "FAIL", "cut"));
        assertThat(failed.getStatusCode().value()).isEqualTo(200);

        // 两名不同保管人几乎同时复核：恰好一人触发恢复
        List<Integer> statuses = runConcurrently(
                () -> containerController.review("alice", containerId,
                        new ContainerReviewRequest(uniqueKey("REV"), "a")),
                () -> containerController.review("bob", containerId,
                        new ContainerReviewRequest(uniqueKey("REV"), "b")));
        assertThat(statuses).containsOnly(200);

        var detail = containerService.containerDetail(containerId);
        assertThat(detail.container().status()).isEqualTo("SEALED");
        assertThat(detail.reviews()).hasSize(2);
        // 恢复后再次复核抛出 409（容器已非失败态；直接控制器调用不经 MVC 异常映射）
        org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> containerController.review("carol", containerId,
                        new ContainerReviewRequest(uniqueKey("REV"), "c")));
    }

    @Test
    void concurrentReviewAndBorrowStaysBlockedUntilBothReviewsCommit() throws Exception {
        String containerId = createContainer();
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        load(containerId, List.of(ev1));
        containerController.inspect("alice", containerId,
                new ContainerInspectRequest(uniqueKey("INSP"), 1L, utc(0), utc(48),
                        "FAIL", "cut"));
        containerController.review("alice", containerId,
                new ContainerReviewRequest(uniqueKey("REV"), "a"));

        // 仅一名保管人复核时，并发借出与第二名复核：复核可能先/后提交，
        // 但借出在仅一名复核的状态下必然 409；若复核先提交，借出得到 200。
        ContainerReviewRequest secondReview = new ContainerReviewRequest(uniqueKey("REV"), "b");
        LoanCreateRequest loan = new LoanCreateRequest(uniqueKey("LOAN"), uniqueKey("LOAN"),
                "bob", "lab", utc(2));
        List<Integer> statuses = runConcurrently(
                () -> containerController.review("bob", containerId, secondReview),
                () -> evidenceController.borrow("alice", ev1, loan));

        int borrowStatus = statuses.get(1);
        if (borrowStatus == 200) {
            // 复核先提交：门禁解除，容器必须已恢复
            assertThat(containerService.containerDetail(containerId).container().status())
                    .isEqualTo("SEALED");
        } else {
            // 借出先裁决：容器仍处于失败待复核
            assertThat(borrowStatus).isEqualTo(409);
            assertThat(containerService.blockReason(ev1).blocked()).isTrue();
        }
    }

    @Test
    void failedInspectionRollsBackAtomicallyUnderConcurrentLoan() throws Exception {
        // 与借出并发、FAIL 回滚时：任何证物都不得残留 PENDING_VERIFICATION
        String containerId = createContainer();
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        load(containerId, List.of(ev1, ev2));

        ContainerInspectRequest fail = new ContainerInspectRequest(uniqueKey("INSP"), 1L,
                utc(0), utc(48), "FAIL", "cut");
        LoanCreateRequest loan = new LoanCreateRequest(uniqueKey("LOAN"), uniqueKey("LOAN"),
                "bob", "lab", utc(2));

        List<Integer> statuses = runConcurrently(
                () -> containerController.inspect("alice", containerId, fail),
                () -> evidenceController.borrow("alice", ev1, loan));

        var detail = containerService.containerDetail(containerId);
        var pendingEv1 = containerService.listPendingVerification("alice").stream()
                .filter(v -> v.evidenceKey().equals(ev1)).findFirst();
        if ("INSPECTION_FAILED".equals(detail.container().status())) {
            assertThat(statuses.get(1)).isEqualTo(409);
            assertThat(pendingEv1).isPresent();
        } else {
            assertThat(statuses.get(0)).isEqualTo(409);
            assertThat(pendingEv1).isEmpty();
            // ev2 也不得被误标记
            assertThat(containerService.listPendingVerification("alice").stream()
                    .filter(v -> v.evidenceKey().equals(ev2)).findFirst()).isEmpty();
        }
    }
}
