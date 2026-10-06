package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.MergeRequest;
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
 * 证物合并并发测试：合并与交接/借出共用两证物行锁的原子边界，
 * 并发下按事务提交顺序生效，不允许出现合并与借出/交接同时生效的中间态。
 */
@SpringBootTest
class MergeConcurrencyTest {

    @Autowired
    private EvidenceController controller;

    @Autowired
    private EvidenceService evidenceService;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey, String sealNo) {
        ResponseEntity<String> response = controller.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", sealNo));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
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
    void concurrentMergeAndTransferExactlyOneWins() throws Exception {
        String primary = uniqueKey("EV");
        String alias = uniqueKey("EV");
        intake("alice", primary, "SEAL-9");
        intake("alice", alias, "SEAL-9");

        List<Integer> statuses = runConcurrently(
                () -> controller.merge("alice",
                        new MergeRequest(uniqueKey("REQ"), primary, alias, 1L, 1L)),
                () -> controller.initiateTransfer("alice", alias,
                        new TransferInitiateRequest(uniqueKey("CMD"), "bob")));

        // 合并先提交则交接对别名返回 409；交接先提交则合并因待接收交接返回 422
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409 || s == 422);

        var aliasChain = evidenceService.custodyChain(alias);
        if (statuses.get(0) == 200) {
            // 合并先提交：副号转 ALIAS，交接不得生效
            assertThat(aliasChain.evidence().status()).isEqualTo(EvidenceStatus.ALIAS);
            assertThat(aliasChain.merges()).hasSize(1);
            assertThat(aliasChain.transfers()).isEmpty();
        } else {
            // 交接先提交：合并不生效，副号保持待接收且无合并事件
            assertThat(statuses.get(1)).isEqualTo(200);
            assertThat(aliasChain.evidence().status()).isEqualTo(EvidenceStatus.TRANSFER_PENDING);
            assertThat(aliasChain.merges()).isEmpty();
        }
    }

    @Test
    void concurrentMergeAndBorrowExactlyOneWins() throws Exception {
        String primary = uniqueKey("EV");
        String alias = uniqueKey("EV");
        intake("alice", primary, "SEAL-9");
        intake("alice", alias, "SEAL-9");

        List<Integer> statuses = runConcurrently(
                () -> controller.merge("alice",
                        new MergeRequest(uniqueKey("REQ"), primary, alias, 1L, 1L)),
                () -> controller.borrow("alice", alias,
                        new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("LOAN"), "borrower-1",
                                "court", LocalDateTime.now(ZoneOffset.UTC).plusHours(1))));

        // 合并先提交则借出对别名返回 409；借出先提交则合并因未归还借出返回 422
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409 || s == 422);

        var aliasChain = evidenceService.custodyChain(alias);
        if (statuses.get(0) == 200) {
            assertThat(aliasChain.evidence().status()).isEqualTo(EvidenceStatus.ALIAS);
            assertThat(aliasChain.merges()).hasSize(1);
            assertThat(aliasChain.loans()).isEmpty();
        } else {
            assertThat(statuses.get(1)).isEqualTo(200);
            assertThat(aliasChain.evidence().status()).isEqualTo(EvidenceStatus.BORROWED);
            assertThat(aliasChain.merges()).isEmpty();
        }
    }

    @Test
    void concurrentDuplicateMergeWithSameRequestIdReplaysSingleResult() throws Exception {
        String primary = uniqueKey("EV");
        String alias = uniqueKey("EV");
        intake("alice", primary, "SEAL-9");
        intake("alice", alias, "SEAL-9");
        MergeRequest request = new MergeRequest(uniqueKey("REQ"), primary, alias, 1L, 1L);

        List<Integer> statuses = runConcurrently(
                () -> controller.merge("alice", request),
                () -> controller.merge("alice", request));

        // 同键同参并发：均返回首次结果，合并只生效一次
        assertThat(statuses).containsOnly(200);
        var primaryChain = evidenceService.custodyChain(primary);
        assertThat(primaryChain.merges()).hasSize(1);
        assertThat(primaryChain.evidence().version()).isEqualTo(2);
        var aliasChain = evidenceService.custodyChain(alias);
        assertThat(aliasChain.evidence().status()).isEqualTo(EvidenceStatus.ALIAS);
        assertThat(aliasChain.evidence().version()).isEqualTo(2);
    }
}
