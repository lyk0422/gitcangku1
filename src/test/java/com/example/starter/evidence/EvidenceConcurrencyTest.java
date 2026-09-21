package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.SealInspectionRequest;
import com.example.starter.evidence.dto.TransferInitiateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

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
 * 并发与幂等边界测试：交接接受/取消/失败核验并发时按事务提交顺序生效，
 * 不允许出现已取消交接仍改变保管人、或封条已异常仍完成交接的结果。
 */
@SpringBootTest
class EvidenceConcurrencyTest {

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

    private void initiate(String actor, String evidenceKey, String toCustodian) {
        ResponseEntity<String> response = controller.initiateTransfer(actor, evidenceKey,
                new TransferInitiateRequest(uniqueKey("CMD"), toCustodian));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
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
    void concurrentAcceptAndCancelExactlyOneWins() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        initiate("alice", evidenceKey, "bob");

        List<Integer> statuses = runConcurrently(
                () -> controller.acceptTransfer("bob", evidenceKey,
                        new CommandRequest(uniqueKey("CMD"))),
                () -> controller.cancelTransfer("alice", evidenceKey,
                        new CommandRequest(uniqueKey("CMD"))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.transfers()).hasSize(1);
        var transfer = chain.transfers().get(0);
        if (transfer.status() == TransferStatus.ACCEPTED) {
            // 接受先提交：保管人必须已切换
            assertThat(chain.evidence().custodianId()).isEqualTo("bob");
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        } else {
            // 取消先提交：保管人不得被已取消的交接改变
            assertThat(transfer.status()).isEqualTo(TransferStatus.CANCELLED);
            assertThat(chain.evidence().custodianId()).isEqualTo("alice");
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        }
    }

    @Test
    void concurrentFailedInspectionAndAcceptNeverCompleteTransferOnBrokenSeal() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        initiate("alice", evidenceKey, "bob");

        List<Integer> statuses = runConcurrently(
                () -> controller.acceptTransfer("bob", evidenceKey,
                        new CommandRequest(uniqueKey("CMD"))),
                () -> controller.inspectSeal("alice", evidenceKey,
                        new SealInspectionRequest(uniqueKey("CMD"), false, "cracked")));

        // 核验先提交则接受得到 422；接受先提交则旧保管人核验得到 409
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409 || s == 422);

        var chain = evidenceService.custodyChain(evidenceKey);
        var transfer = chain.transfers().get(0);
        if (transfer.status() == TransferStatus.ACCEPTED) {
            // 接受先提交：交接完成时封条尚未异常，保管人已切换
            assertThat(chain.evidence().custodianId()).isEqualTo("bob");
        } else {
            // 失败核验先提交：封条异常，交接不得完成，保管人不变
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEAL_BROKEN);
            assertThat(chain.evidence().custodianId()).isEqualTo("alice");
            assertThat(transfer.status()).isEqualTo(TransferStatus.PENDING);
        }
    }

    @Test
    void concurrentDuplicateIntakeWithSameCommandKeyReplaysSingleResult() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String commandKey = uniqueKey("CMD");
        IntakeRequest request = new IntakeRequest(commandKey, evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1");

        List<Integer> statuses = runConcurrently(
                () -> controller.intake("alice", request),
                () -> controller.intake("alice", request));

        assertThat(statuses).containsOnly(201);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.evidence().custodianId()).isEqualTo("alice");
        assertThat(chain.transfers()).isEmpty();
    }
}
