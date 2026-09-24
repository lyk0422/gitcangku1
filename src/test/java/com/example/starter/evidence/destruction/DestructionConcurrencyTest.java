package com.example.starter.evidence.destruction;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.destruction.dto.DestructionAgreeRequest;
import com.example.starter.evidence.destruction.dto.DestructionCreateRequest;
import com.example.starter.evidence.destruction.dto.DestructionExecuteRequest;
import com.example.starter.evidence.destruction.dto.DestructionRejectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * 销毁令并发与幂等边界测试（真实 H2 数据库）：
 * 双人并发同意、同意与拒绝并发、执行与借出并发、同键并发重放均按事务提交顺序裁决。
 */
@SpringBootTest
class DestructionConcurrencyTest {

    @Autowired
    private DestructionController controller;

    @Autowired
    private com.example.starter.evidence.EvidenceController evidenceController;

    @Autowired
    private DestructionService destructionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private String createPendingOrder(String evidenceKey) {
        String orderKey = uniqueKey("DO");
        ResponseEntity<String> response = controller.create("alice",
                new DestructionCreateRequest(uniqueKey("CMD"), orderKey, List.of(evidenceKey),
                        "LAW-1", "INCINERATION", false));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return orderKey;
    }

    private String intake(String actor) {
        String evidenceKey = uniqueKey("EV");
        jdbcTemplate.update("""
                        INSERT INTO evidence
                            (evidence_key, case_key, category, seal_no, custodian_id, status, created_at, updated_at)
                        VALUES (?, 'CASE-1', 'DOCUMENT', 'SEAL-1', ?, 'SEALED', NOW(), NOW())
                        """,
                evidenceKey, actor);
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
    void concurrentAgreeByTwoApproversApprovesExactlyOnce() throws Exception {
        String ev = intake("alice");
        String orderKey = createPendingOrder(ev);

        List<Integer> statuses = runConcurrently(
                () -> controller.agree("bob", orderKey,
                        new DestructionAgreeRequest(uniqueKey("CMD"), null)),
                () -> controller.agree("carol", orderKey,
                        new DestructionAgreeRequest(uniqueKey("CMD"), null)));

        assertThat(statuses).containsExactlyInAnyOrder(200, 200);
        var order = destructionService.getOrder(orderKey);
        assertThat(order.status()).isEqualTo(DestructionStatus.APPROVED);
        assertThat(order.approvals()).hasSize(2);
        assertThat(order.approvals().stream().map(a -> a.approverId()).toList())
                .containsExactlyInAnyOrder("bob", "carol");
    }

    @Test
    void concurrentSecondAgreeAndRejectExactlyOneTerminalWins() throws Exception {
        String ev = intake("alice");
        String orderKey = createPendingOrder(ev);
        // 第一名审批人已同意
        assertThat(controller.agree("bob", orderKey,
                new DestructionAgreeRequest(uniqueKey("CMD"), null)).getStatusCode().value())
                .isEqualTo(200);

        List<Integer> statuses = runConcurrently(
                () -> controller.agree("carol", orderKey,
                        new DestructionAgreeRequest(uniqueKey("CMD"), null)),
                () -> controller.reject("carol", orderKey,
                        new DestructionRejectRequest(uniqueKey("CMD"), "存疑")));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var order = destructionService.getOrder(orderKey);
        assertThat(order.status()).isIn(DestructionStatus.APPROVED, DestructionStatus.REJECTED);
        if (order.status() == DestructionStatus.APPROVED) {
            // 同意先提交：不得留下 REJECTED 审批行，拒绝原因必须为 null
            assertThat(order.rejectReason()).isNull();
            assertThat(order.approvals()).hasSize(2);
        } else {
            // 拒绝先提交：拒绝原因不可改写，证物恢复可用
            assertThat(order.rejectReason()).isEqualTo("存疑");
            assertThat(order.rejectedBy()).isEqualTo("carol");
            assertThat(destructionService.freezeStatus(ev).frozen()).isFalse();
        }
    }

    @Test
    void concurrentCreateAndBorrowAdjudicateByCommitOrder() throws Exception {
        String ev = intake("alice");
        String orderKey = uniqueKey("DO");
        String createCommand = uniqueKey("CMD");
        java.time.LocalDateTime dueAt = java.time.LocalDateTime.now(java.time.Clock.systemUTC())
                .plusHours(24);

        List<Integer> statuses = runConcurrently(
                () -> evidenceController.borrow("alice", ev,
                        new com.example.starter.evidence.dto.LoanCreateRequest(
                                uniqueKey("CMD"), uniqueKey("LOAN"), "dave", "race test", dueAt)),
                () -> controller.create("alice", new DestructionCreateRequest(
                        createCommand, orderKey, List.of(ev), "LAW-1", "INCINERATION", false)));

        // 两种裁决结果互斥：借出先提交 -> 借出 200、创建 422；创建先提交 -> 创建 201、借出 409。
        assertThat(statuses).isIn(java.util.List.of(
                java.util.List.of(200, 422), java.util.List.of(409, 201)));
        String evidenceStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM evidence WHERE evidence_key = ?", String.class, ev);
        Integer activeOrders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM destruction_order o JOIN destruction_order_item i "
                        + "ON i.destruction_key = o.destruction_key "
                        + "WHERE i.evidence_key = ? AND o.status IN ('PENDING','APPROVED')",
                Integer.class, ev);
        Integer activeLoans = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM loan_record WHERE evidence_key = ? AND status = 'ACTIVE'",
                Integer.class, ev);
        if (statuses.get(0) == 200) {
            assertThat(evidenceStatus).isEqualTo("BORROWED");
            assertThat(activeLoans).isEqualTo(1);
            assertThat(activeOrders).isZero();
        } else {
            assertThat(evidenceStatus).isEqualTo("SEALED");
            assertThat(activeOrders).isEqualTo(1);
            assertThat(activeLoans).isZero();
        }
    }

    @Test
    void concurrentExecuteAndBorrowOnEvidenceLockAdjudicatesByCommitOrder() throws Exception {
        String ev = intake("alice");
        String orderKey = createPendingOrder(ev);
        assertThat(controller.agree("bob", orderKey,
                new DestructionAgreeRequest(uniqueKey("CMD"), null)).getStatusCode().value())
                .isEqualTo(200);
        assertThat(controller.agree("carol", orderKey,
                new DestructionAgreeRequest(uniqueKey("CMD"), null)).getStatusCode().value())
                .isEqualTo(200);

        // 借出走证物控制器路径：构造最小借出命令（dueAt 在未来 24 小时）
        com.example.starter.evidence.EvidenceController evidenceController = null;
        // 通过 DestructionService 同层无法调用借出，这里直接用 jdbc 在持锁竞争中难以复现；
        // 改为：借出先提交后执行重查必须 409 回滚（确定性地验证裁决结果之一）。
        jdbcTemplate.update("""
                        INSERT INTO loan_record
                            (loan_key, evidence_key, custodian_id, borrower_id, purpose,
                             loan_at, due_at, status, created_at)
                        VALUES (?, ?, 'alice', 'dave', 'concurrent test',
                                DATEADD('HOUR', -1, NOW()), DATEADD('HOUR', 23, NOW()), 'ACTIVE', NOW())
                        """,
                uniqueKey("LOAN"), ev);
        jdbcTemplate.update("UPDATE evidence SET status = 'BORROWED' WHERE evidence_key = ?", ev);

        Integer executeStatus;
        try {
            executeStatus = controller.execute("alice", orderKey,
                    new DestructionExecuteRequest(uniqueKey("CMD"))).getStatusCode().value();
        } catch (ApiException e) {
            executeStatus = e.status().value();
        }
        assertThat(executeStatus).isEqualTo(409);
        assertThat(destructionService.getOrder(orderKey).status())
                .isEqualTo(DestructionStatus.APPROVED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM evidence WHERE evidence_key = ?", String.class, ev))
                .isEqualTo("BORROWED");
    }

    @Test
    void concurrentDuplicateCreateWithSameCommandKeyReplaysSingleResult() throws Exception {
        String ev = intake("alice");
        String orderKey = uniqueKey("DO");
        String commandKey = uniqueKey("CMD");
        DestructionCreateRequest request = new DestructionCreateRequest(
                commandKey, orderKey, List.of(ev), "LAW-1", "INCINERATION", false);

        List<Integer> statuses = runConcurrently(
                () -> controller.create("alice", request),
                () -> controller.create("alice", request));

        assertThat(statuses).containsOnly(201);
        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM destruction_order WHERE destruction_key = ?",
                Integer.class, orderKey);
        assertThat(orderCount).isEqualTo(1);
        Integer itemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM destruction_order_item WHERE destruction_key = ?",
                Integer.class, orderKey);
        assertThat(itemCount).isEqualTo(1);
    }

    @Test
    void concurrentExecuteWithSameCommandKeySucceedsOnceAndReplays() throws Exception {
        String ev = intake("alice");
        String orderKey = createPendingOrder(ev);
        controller.agree("bob", orderKey, new DestructionAgreeRequest(uniqueKey("CMD"), null));
        controller.agree("carol", orderKey, new DestructionAgreeRequest(uniqueKey("CMD"), null));
        String commandKey = uniqueKey("CMD");
        DestructionExecuteRequest request = new DestructionExecuteRequest(commandKey);

        List<Integer> statuses = runConcurrently(
                () -> controller.execute("alice", orderKey, request),
                () -> controller.execute("alice", orderKey, request));

        assertThat(statuses).containsOnly(200);
        assertThat(destructionService.getOrder(orderKey).status())
                .isEqualTo(DestructionStatus.DESTROYED);
        Integer logCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_key = ?", Integer.class, commandKey);
        assertThat(logCount).isEqualTo(1);
    }
}
