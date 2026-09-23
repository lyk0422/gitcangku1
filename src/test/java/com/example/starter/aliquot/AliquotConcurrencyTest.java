package com.example.starter.aliquot;

import com.example.starter.aliquot.dto.AliquotApplyRequest;
import com.example.starter.aliquot.dto.AliquotItemInput;
import com.example.starter.aliquot.dto.AliquotSecondConfirmRequest;
import com.example.starter.aliquot.dto.SampleBalanceView;
import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.IntakeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多母样联合取样并发边界测试（真实 H2 MySQL 兼容库）：
 * 重叠母样申请、二次确认并发以及申请与原交接并发时，不得超额或半生成。
 */
@SpringBootTest
class AliquotConcurrencyTest {

    @Autowired
    private AliquotController controller;

    @Autowired
    private AliquotService service;

    @Autowired
    private com.example.starter.evidence.EvidenceController evidenceController;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String key) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), key, "CASE-C", "BLOOD", "SEAL-" + key));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private void register(String actor, String key, long total) {
        ResponseEntity<String> response = controller.registerSample(actor, key,
                new com.example.starter.aliquot.dto.SampleRegisterRequest(
                        uniqueKey("CMD"), total, "ML"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private AliquotApplyRequest applyRequest(String aliquotKey, Map.Entry<String, Long> a,
                                             Map.Entry<String, Long> b) {
        return new AliquotApplyRequest(uniqueKey("REQ"), aliquotKey,
                List.of(new AliquotItemInput(a.getKey(), a.getValue()),
                        new AliquotItemInput(b.getKey(), b.getValue())));
    }

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
            return List.of(f1.get(20, TimeUnit.SECONDS), f2.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentOverlappingApplicationsNeverOverReserve() throws Exception {
        // s1 总量 5，两张单各需 3，仅一张可成功
        String s1 = uniqueKey("SMP");
        String s2 = uniqueKey("SMP");
        String s3 = uniqueKey("SMP");
        intake("alice", s1);
        intake("alice", s2);
        intake("alice", s3);
        register("alice", s1, 5);
        register("alice", s2, 10);
        register("alice", s3, 10);

        List<Integer> statuses = runConcurrently(
                () -> controller.apply("alice", applyRequest(uniqueKey("ALQ"),
                        Map.entry(s1, 3L), Map.entry(s2, 1L))),
                () -> controller.apply("alice", applyRequest(uniqueKey("ALQ"),
                        Map.entry(s1, 3L), Map.entry(s3, 1L))));

        assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        SampleBalanceView balance = service.getBalance(s1);
        assertThat(balance.reserved()).isEqualTo(3);
        assertThat(balance.consumed()).isZero();
        assertThat(balance.available()).isEqualTo(2);
        // 失败单据不得半生成：s2/s3 中恰有一个被预留 1
        assertThat(service.getBalance(s2).reserved() + service.getBalance(s3).reserved())
                .isEqualTo(1);
    }

    @Test
    void concurrentApplyAndTransferNeverLeaveHalfGeneratedState() throws Exception {
        String s1 = uniqueKey("SMP");
        String s2 = uniqueKey("SMP");
        intake("alice", s1);
        intake("alice", s2);
        register("alice", s1, 10);
        register("alice", s2, 10);

        AliquotApplyRequest aliquotRequest = new AliquotApplyRequest(
                uniqueKey("REQ"), uniqueKey("ALQ"),
                List.of(new AliquotItemInput(s1, 2L), new AliquotItemInput(s2, 2L)));

        List<Integer> statuses = runConcurrently(
                () -> controller.apply("alice", aliquotRequest),
                () -> evidenceController.initiateTransfer("alice", s1,
                        new com.example.starter.evidence.dto.TransferInitiateRequest(
                                uniqueKey("CMD"), "bob")));

        assertThat(statuses).allMatch(s -> s == 200 || s == 201 || s == 422 || s == 409);
        long applyStatus = statuses.get(0);
        long transferStatus = statuses.get(1);

        SampleBalanceView b1 = service.getBalance(s1);
        SampleBalanceView b2 = service.getBalance(s2);
        if (applyStatus == 201) {
            // 申请先提交：预留完整可见；交接若随后成功，二次确认时仍会因状态变化被拦
            assertThat(b1.reserved()).isEqualTo(2);
            assertThat(b2.reserved()).isEqualTo(2);
            // 明细恰好 2 条，无耗用半生成
            var detail = service.getDetail(aliquotRequest.aliquotKey());
            assertThat(detail.items()).hasSize(2);
            assertThat(detail.consumptions()).isEmpty();
        } else {
            // 交接先提交：申请必须整单失败，两个母样都无预留
            assertThat(applyStatus).isEqualTo(422);
            assertThat(b1.reserved()).isZero();
            assertThat(b2.reserved()).isZero();
        }
        // 总量与已耗用永不因并发而错乱
        assertThat(b1.consumed()).isZero();
        assertThat(b2.consumed()).isZero();
        assertThat(transferStatus).isIn(200L, 409L, 422L);
    }

    @Test
    void concurrentSecondConfirmationsConsumeExactlyOnce() throws Exception {
        String s1 = uniqueKey("SMP");
        String s2 = uniqueKey("SMP");
        intake("alice", s1);
        intake("alice", s2);
        register("alice", s1, 10);
        register("alice", s2, 10);
        String aliquotKey = uniqueKey("ALQ");
        ResponseEntity<String> applied = controller.apply("alice", new AliquotApplyRequest(
                uniqueKey("REQ"), aliquotKey,
                List.of(new AliquotItemInput(s1, 2L), new AliquotItemInput(s2, 3L))));
        assertThat(applied.getStatusCode().value()).isEqualTo(201);
        controller.firstConfirm("bob", aliquotKey,
                new com.example.starter.aliquot.dto.AliquotFirstConfirmRequest(uniqueKey("CMD")));

        AliquotSecondConfirmRequest request = new AliquotSecondConfirmRequest(
                uniqueKey("CMD"), 1L, Map.of(s1, 0L, s2, 0L));
        List<Integer> statuses = runConcurrently(
                () -> controller.secondConfirm("carol", aliquotKey, request),
                () -> controller.secondConfirm("dave", aliquotKey, request));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var detail = service.getDetail(aliquotKey);
        assertThat(detail.status()).isEqualTo(AliquotStatus.CONSUMED);
        assertThat(detail.consumptions()).hasSize(2);
        assertThat(detail.reviews()).hasSize(2);
        assertThat(service.getBalance(s1).consumed()).isEqualTo(2);
        assertThat(service.getBalance(s2).consumed()).isEqualTo(3);
        assertThat(service.getBalance(s1).reserved()).isZero();
        assertThat(service.getBalance(s2).reserved()).isZero();
    }

    @Test
    void concurrentFirstConfirmationSameCommandKeyReplaysSingleReview() throws Exception {
        String s1 = uniqueKey("SMP");
        String s2 = uniqueKey("SMP");
        intake("alice", s1);
        intake("alice", s2);
        register("alice", s1, 10);
        register("alice", s2, 10);
        String aliquotKey = uniqueKey("ALQ");
        controller.apply("alice", new AliquotApplyRequest(
                uniqueKey("REQ"), aliquotKey,
                List.of(new AliquotItemInput(s1, 1L), new AliquotItemInput(s2, 1L))));

        var request = new com.example.starter.aliquot.dto.AliquotFirstConfirmRequest(
                uniqueKey("CMD"));
        List<Integer> statuses = runConcurrently(
                () -> controller.firstConfirm("bob", aliquotKey, request),
                () -> controller.firstConfirm("bob", aliquotKey, request));

        assertThat(statuses).containsOnly(200);
        var detail = service.getDetail(aliquotKey);
        assertThat(detail.reviews()).hasSize(1);
        assertThat(detail.status()).isEqualTo(AliquotStatus.RESERVED);
        assertThat(detail.version()).isEqualTo(1);
    }
}
