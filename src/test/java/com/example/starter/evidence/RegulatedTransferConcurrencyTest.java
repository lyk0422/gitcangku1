package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.RegulatedCheckinRequest;
import com.example.starter.evidence.dto.RegulatedDecisionRequest;
import com.example.starter.evidence.dto.RegulatedTransferInitiateRequest;
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
 * 受监管交接并发边界测试：签到与取消/接收竞争时按事务提交顺序生效，
 * 不允许出现取消后新签到、未完成路由的接收或同一检查点重复签到。
 */
@SpringBootTest
class RegulatedTransferConcurrencyTest {

    private static final LocalDateTime D1 = LocalDateTime.of(2030, 6, 1, 10, 0);
    private static final LocalDateTime D2 = LocalDateTime.of(2030, 6, 1, 12, 0);

    @Autowired
    private RegulatedTransferController controller;

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private RegulatedTransferService regulatedTransferService;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private String initiate(String actor, String evidenceKey) {
        ResponseEntity<String> response = controller.initiate(actor, evidenceKey,
                new RegulatedTransferInitiateRequest(uniqueKey("REQ"), "bob", "ted",
                        List.of(new RegulatedTransferInitiateRequest.CheckpointSpec(D1),
                                new RegulatedTransferInitiateRequest.CheckpointSpec(D2))));
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
    void concurrentCheckinAndCancelNeverProduceCheckinAfterCancel() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        initiate("alice", evidenceKey);

        List<Integer> statuses = runConcurrently(
                () -> controller.checkin("ted", evidenceKey,
                        new RegulatedCheckinRequest(uniqueKey("REQ"), 1, "SEAL-1",
                                LocalDateTime.of(2030, 6, 1, 9, 0), 0L)),
                () -> controller.cancel("alice", evidenceKey,
                        new RegulatedDecisionRequest(uniqueKey("REQ"), 0L)));

        // 恰好一个成功：取消先提交则签到 409，签到先提交则取消版本不匹配 409
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var history = regulatedTransferService.listByEvidence(evidenceKey);
        assertThat(history).hasSize(1);
        var transfer = history.get(0);
        if (transfer.status() == RegulatedTransferStatus.CANCELLED) {
            // 取消先提交：不得出现取消后的新签到
            assertThat(transfer.checkins()).isEmpty();
        } else {
            // 签到先提交：交接仍在途，签到已留痕，版本已递增
            assertThat(transfer.status()).isEqualTo(RegulatedTransferStatus.IN_TRANSIT);
            assertThat(transfer.checkins()).hasSize(1);
            assertThat(transfer.version()).isEqualTo(1);
        }
    }

    @Test
    void concurrentAcceptAndFinalCheckinNeverAcceptsIncompleteRoute() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        initiate("alice", evidenceKey);
        ResponseEntity<String> c1 = controller.checkin("ted", evidenceKey,
                new RegulatedCheckinRequest(uniqueKey("REQ"), 1, "SEAL-1",
                        LocalDateTime.of(2030, 6, 1, 9, 0), 0L));
        assertThat(c1.getStatusCode().value()).isEqualTo(200);

        List<Integer> statuses = runConcurrently(
                () -> controller.checkin("ted", evidenceKey,
                        new RegulatedCheckinRequest(uniqueKey("REQ"), 2, "SEAL-1", D2, 1L)),
                () -> controller.accept("bob", evidenceKey,
                        new RegulatedDecisionRequest(uniqueKey("REQ"), 1L)));

        // 接收先提交则签到缺失 422；签到先提交则接收版本不匹配 409；接收绝不可能以未完成路由成功
        assertThat(statuses.get(0)).isEqualTo(200);
        assertThat(statuses.get(1)).isIn(409, 422);
        var transfer = regulatedTransferService.listByEvidence(evidenceKey).get(0);
        assertThat(transfer.checkins()).hasSize(2);
        assertThat(transfer.status()).isEqualTo(RegulatedTransferStatus.IN_TRANSIT);
        // 接收方随后携带当前版本重试可成功完成
        ResponseEntity<String> accepted = controller.accept("bob", evidenceKey,
                new RegulatedDecisionRequest(uniqueKey("REQ"), 2L));
        assertThat(accepted.getStatusCode().value()).isEqualTo(200);
        assertThat(regulatedTransferService.listByEvidence(evidenceKey).get(0).status())
                .isEqualTo(RegulatedTransferStatus.COMPLETED);
    }

    @Test
    void concurrentCheckinsForSameCheckpointExactlyOneWins() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        initiate("alice", evidenceKey);

        List<Integer> statuses = runConcurrently(
                () -> controller.checkin("ted", evidenceKey,
                        new RegulatedCheckinRequest(uniqueKey("REQ"), 1, "SEAL-1",
                                LocalDateTime.of(2030, 6, 1, 9, 0), 0L)),
                () -> controller.checkin("ted", evidenceKey,
                        new RegulatedCheckinRequest(uniqueKey("REQ"), 1, "SEAL-1",
                                LocalDateTime.of(2030, 6, 1, 9, 0), 0L)));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var transfer = regulatedTransferService.listByEvidence(evidenceKey).get(0);
        assertThat(transfer.checkins()).hasSize(1);
        assertThat(transfer.version()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateRequestIdCheckinReplaysSingleResult() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        initiate("alice", evidenceKey);
        String requestId = uniqueKey("REQ");
        RegulatedCheckinRequest request = new RegulatedCheckinRequest(requestId, 1, "SEAL-1",
                LocalDateTime.of(2030, 6, 1, 9, 0), 0L);

        List<Integer> statuses = runConcurrently(
                () -> controller.checkin("ted", evidenceKey, request),
                () -> controller.checkin("ted", evidenceKey, request));

        assertThat(statuses).containsOnly(200);
        var transfer = regulatedTransferService.listByEvidence(evidenceKey).get(0);
        assertThat(transfer.checkins()).hasSize(1);
        assertThat(transfer.version()).isEqualTo(1);
    }
}