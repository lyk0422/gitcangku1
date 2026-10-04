package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CheckpointSigninRequest;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.IntakeRequest;
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
 * 受监管交接并发边界测试（真实 H2 行锁与事务）：
 * 签到与取消/接收竞争时不得产生取消后新签到或未完成路由的接收；
 * 同一 requestId 并发重复签到只落一条签到记录。
 */
@SpringBootTest
class RegulatedTransferConcurrencyTest {

    private static final LocalDateTime D1 = LocalDateTime.of(2026, 10, 6, 10, 0, 0);
    private static final LocalDateTime D2 = LocalDateTime.of(2026, 10, 6, 12, 0, 0);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 6, 9, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 10, 6, 11, 0, 0);

    @Autowired
    private EvidenceController controller;

    @Autowired
    private EvidenceService evidenceService;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = controller.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private void initiateRegulated(String actor, String evidenceKey, String toCustodian) {
        ResponseEntity<String> response = controller.initiateRegulatedTransfer(actor, evidenceKey,
                new RegulatedTransferInitiateRequest(uniqueKey("REQ"), toCustodian, List.of(D1, D2)));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    private ResponseEntity<String> signin(String actor, String evidenceKey, String requestId,
                                          int seq, LocalDateTime arrivedAt) {
        return controller.signinCheckpoint(actor, evidenceKey,
                new CheckpointSigninRequest(requestId, seq, "SEAL-1", arrivedAt));
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
    void concurrentSigninAndCancelNeverProduceSigninAfterCancel() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        initiateRegulated("alice", evidenceKey, "bob");

        List<Integer> statuses = runConcurrently(
                () -> signin("bob", evidenceKey, uniqueKey("REQ"), 1, T1),
                () -> controller.cancelTransfer("alice", evidenceKey,
                        new CommandRequest(uniqueKey("CMD"))));

        // 取消必定成功；签到要么先于取消提交（200），要么看到已取消（409）
        assertThat(statuses.get(1)).isEqualTo(200);
        assertThat(statuses.get(0)).isIn(200, 409);

        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.transfers()).hasSize(1);
        assertThat(chain.transfers().get(0).status()).isEqualTo(TransferStatus.CANCELLED);
        if (statuses.get(0) == 409) {
            // 取消先提交：不得产生取消后的新签到
            assertThat(chain.signins()).isEmpty();
        } else {
            // 签到先提交：记录保留，且之后不再有新签到
            assertThat(chain.signins()).hasSize(1);
            assertThat(chain.signins().get(0).checkpointSeq()).isEqualTo(1);
        }
        // 取消后再次签到一律拒绝
        try {
            signin("bob", evidenceKey, uniqueKey("REQ"), 1, T1);
            assertThat(false).as("取消后签到应被拒绝").isTrue();
        } catch (ApiException e) {
            assertThat(e.status().value()).isEqualTo(409);
        }
        assertThat(evidenceService.custodyChain(evidenceKey).signins())
                .hasSize(chain.signins().size());
    }

    @Test
    void concurrentLastSigninAndAcceptNeverAcceptsWithMissingRoute() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        initiateRegulated("alice", evidenceKey, "bob");
        assertThat(signin("bob", evidenceKey, uniqueKey("REQ"), 1, T1)
                .getStatusCode().value()).isEqualTo(200);

        List<Integer> statuses = runConcurrently(
                () -> signin("bob", evidenceKey, uniqueKey("REQ"), 2, T2),
                () -> controller.acceptTransfer("bob", evidenceKey,
                        new CommandRequest(uniqueKey("CMD"))));

        // 最后一个签到必定成功；接收要么看到完整路由（200），要么因签到缺失被拒（422）
        assertThat(statuses.get(0)).isEqualTo(200);
        assertThat(statuses.get(1)).isIn(200, 422);

        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.signins()).hasSize(2);
        if (statuses.get(1) == 200) {
            // 接收成功时路由必然完整：两个签到都在，保管人已切换
            assertThat(chain.transfers().get(0).status()).isEqualTo(TransferStatus.ACCEPTED);
            assertThat(chain.evidence().custodianId()).isEqualTo("bob");
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        } else {
            // 接收被拒时交接仍在途，随后可正常完成接收
            assertThat(chain.transfers().get(0).status()).isEqualTo(TransferStatus.PENDING);
            assertThat(chain.evidence().custodianId()).isEqualTo("alice");
            ResponseEntity<String> accepted = controller.acceptTransfer("bob", evidenceKey,
                    new CommandRequest(uniqueKey("CMD")));
            assertThat(accepted.getStatusCode().value()).isEqualTo(200);
            assertThat(evidenceService.custodyChain(evidenceKey).evidence().custodianId())
                    .isEqualTo("bob");
        }
    }

    @Test
    void concurrentDuplicateSigninWithSameRequestIdRecordsSingleSignin() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        initiateRegulated("alice", evidenceKey, "bob");
        String requestId = uniqueKey("REQ");

        List<Integer> statuses = runConcurrently(
                () -> signin("bob", evidenceKey, requestId, 1, T1),
                () -> signin("bob", evidenceKey, requestId, 1, T1));

        // 并发同键同参：两者都拿到首次结果，签到只落一条
        assertThat(statuses).containsOnly(200);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.signins()).hasSize(1);
        assertThat(chain.anomalies()).isEmpty();
    }
}