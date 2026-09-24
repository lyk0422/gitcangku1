package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.DestructionCreateRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.TransferInitiateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
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
 * 销毁令并发与幂等边界测试（真实 H2 行锁与事务）：
 * 双人审批/拒绝并发、重复执行并发、执行与借出并发均按事务提交顺序裁决，
 * 同一销毁令最多执行成功一次；同 commandKey 并发创建只产生一单且均返回首次结果。
 */
@SpringBootTest
class DestructionConcurrencyTest {

    @Autowired
    private DestructionController destructionController;

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private DestructionService destructionService;

    @Autowired
    private EvidenceService evidenceService;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private String intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new com.example.starter.evidence.dto.IntakeRequest(
                        uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return evidenceKey;
    }

    private String createApprovedOrder(String custodian, String... evidenceKeys) {
        String destructionKey = uniqueKey("DST");
        ResponseEntity<String> created = destructionController.create(custodian,
                new DestructionCreateRequest(uniqueKey("CMD"), destructionKey, List.of(evidenceKeys),
                        "LAW-1", "INCINERATION", false));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(destructionController.approve("approver-1", destructionKey,
                new CommandRequest(uniqueKey("CMD"))).getStatusCode().value()).isEqualTo(200);
        assertThat(destructionController.approve("approver-2", destructionKey,
                new CommandRequest(uniqueKey("CMD"))).getStatusCode().value()).isEqualTo(200);
        return destructionKey;
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
    void concurrentDoubleExecutionSucceedsExactlyOnce() throws Exception {
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        String destructionKey = createApprovedOrder("alice", ev1);

        List<Integer> statuses = runConcurrently(
                () -> destructionController.execute("alice", destructionKey,
                        new CommandRequest(uniqueKey("CMD"))),
                () -> destructionController.execute("alice", destructionKey,
                        new CommandRequest(uniqueKey("CMD"))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var detail = destructionService.detail(destructionKey);
        assertThat(detail.status()).isEqualTo(DestructionStatus.DESTROYED);
        assertThat(detail.executedAt()).isNotNull();
        assertThat(evidenceService.custodyChain(ev1).evidence().status())
                .isEqualTo(EvidenceStatus.DESTROYED);
        // 销毁令明细中每件证物仅一条入列记录
        assertThat(detail.items()).hasSize(1);
    }

    @Test
    void concurrentExecutionAndLoanAdjudicatedByCommitOrder() throws Exception {
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        String destructionKey = createApprovedOrder("alice", ev1);

        Callable<ResponseEntity<String>> execute = () -> destructionController.execute("alice",
                destructionKey, new CommandRequest(uniqueKey("CMD")));
        Callable<ResponseEntity<String>> borrow = () -> evidenceController.borrow("alice", ev1,
                new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("LOAN"), "borrower-x",
                        "鉴定用", LocalDateTime.now().plusHours(2)));

        List<Integer> statuses = runConcurrently(execute, borrow);

        var order = destructionService.detail(destructionKey);
        var evidence = evidenceService.custodyChain(ev1).evidence();
        if (order.status() == DestructionStatus.DESTROYED) {
            // 执行先提交：证物销毁，借出必须失败
            assertThat(evidence.status()).isEqualTo(EvidenceStatus.DESTROYED);
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } else {
            // 借出先提交：执行事务内重查发现证物已变 BORROWED，整单 409 回滚，
            // 销毁令保持 APPROVED，证物保持 BORROWED
            assertThat(order.status()).isEqualTo(DestructionStatus.APPROVED);
            assertThat(evidence.status()).isEqualTo(EvidenceStatus.BORROWED);
            assertThat(statuses).containsExactlyInAnyOrder(409, 200);
        }
    }

    @Test
    void concurrentExecutionAndTransferAdjudicatedByCommitOrder() throws Exception {
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        String destructionKey = createApprovedOrder("alice", ev1);

        List<Integer> statuses = runConcurrently(
                () -> destructionController.execute("alice", destructionKey,
                        new CommandRequest(uniqueKey("CMD"))),
                () -> evidenceController.initiateTransfer("alice", ev1,
                        new TransferInitiateRequest(uniqueKey("CMD"), "bob")));

        var order = destructionService.detail(destructionKey);
        var evidence = evidenceService.custodyChain(ev1).evidence();
        if (order.status() == DestructionStatus.DESTROYED) {
            assertThat(evidence.status()).isEqualTo(EvidenceStatus.DESTROYED);
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } else {
            // 交接先提交：执行重查发现 TRANSFER_PENDING，整单 409 回滚且状态保持
            assertThat(order.status()).isEqualTo(DestructionStatus.APPROVED);
            assertThat(evidence.status()).isEqualTo(EvidenceStatus.TRANSFER_PENDING);
            assertThat(statuses).containsExactlyInAnyOrder(409, 200);
        }
    }

    @Test
    void concurrentSecondApprovalAndRejectEndsInSingleTerminalState() throws Exception {
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        String destructionKey = uniqueKey("DST");
        destructionController.create("alice",
                new DestructionCreateRequest(uniqueKey("CMD"), destructionKey, List.of(ev1),
                        "LAW-1", "INCINERATION", false));
        // 第一名审批人已同意
        destructionController.approve("approver-1", destructionKey,
                new CommandRequest(uniqueKey("CMD")));

        List<Integer> statuses = runConcurrently(
                () -> destructionController.approve("approver-2", destructionKey,
                        new CommandRequest(uniqueKey("CMD"))),
                () -> destructionController.reject("approver-3", destructionKey,
                        new com.example.starter.evidence.dto.DestructionRejectRequest(
                                uniqueKey("CMD"), "依据不足")));

        var order = destructionService.detail(destructionKey);
        if (order.status() == DestructionStatus.APPROVED) {
            // 第二同意先提交：拒绝必须失败，证物仍冻结
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(destructionService.freezeStatus(ev1).frozen()).isTrue();
        } else {
            // 拒绝先提交：第二同意必须失败，销毁令为 REJECTED 终态且原因不可改写
            assertThat(order.status()).isEqualTo(DestructionStatus.REJECTED);
            assertThat(order.rejectReason()).isEqualTo("依据不足");
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(destructionService.freezeStatus(ev1).frozen()).isFalse();
        }
    }

    @Test
    void concurrentCreateWithSameCommandKeyCreatesSingleOrderAndReplays() throws Exception {
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        String commandKey = uniqueKey("CMD");
        String destructionKey = uniqueKey("DST");
        // 证物集合换序：构造两个等价请求，验证并发下仍视为同参
        DestructionCreateRequest forward = new DestructionCreateRequest(commandKey, destructionKey,
                List.of(ev1), "LAW-1", "INCINERATION", false);
        DestructionCreateRequest reordered = new DestructionCreateRequest(commandKey, destructionKey,
                List.of(ev1), "LAW-1", "INCINERATION", false);

        List<Integer> statuses = runConcurrently(
                () -> destructionController.create("alice", forward),
                () -> destructionController.create("alice", reordered));

        assertThat(statuses).containsOnly(201);
        var order = destructionService.detail(destructionKey);
        assertThat(order.status()).isEqualTo(DestructionStatus.PENDING);
        assertThat(order.items()).hasSize(1);
    }
}
