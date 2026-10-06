package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.MergeRequest;
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
 * 合并并发边界测试：合并与借出/另一合并共用两证物行锁原子边界，
 * 并发下恰有一个成功；同键并发重放返回同一首次结果。
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

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = controller.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    /**
     * 记录一次并发调用的 HTTP 状态码（业务异常取其携带状态码）。
     */
    private static final class StatusCall implements Callable<ResponseEntity<String>> {
        private final CountDownLatch ready;
        private final CountDownLatch start;
        private final Callable<ResponseEntity<String>> call;

        StatusCall(CountDownLatch ready, CountDownLatch start, Callable<ResponseEntity<String>> call) {
            this.ready = ready;
            this.start = start;
            this.call = call;
        }

        @Override
        public ResponseEntity<String> call() throws Exception {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                return call.call();
            } catch (ApiException e) {
                return ResponseEntity.status(e.status()).body(e.getMessage());
            }
        }
    }

    private List<ResponseEntity<String>> runConcurrently(Callable<ResponseEntity<String>> first,
                                                         Callable<ResponseEntity<String>> second)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<ResponseEntity<String>> f1 = executor.submit(new StatusCall(ready, start, first));
            Future<ResponseEntity<String>> f2 = executor.submit(new StatusCall(ready, start, second));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentMergeAndBorrowExactlyOneWins() throws Exception {
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        intake("alice", e1);
        intake("alice", e2);
        LocalDateTime dueAt = LocalDateTime.now(ZoneOffset.UTC).plusHours(1);

        List<ResponseEntity<String>> results = runConcurrently(
                () -> controller.merge("alice", new MergeRequest(uniqueKey("CMD"), e1, 1L, e2, 1L)),
                () -> controller.borrow("alice", e2,
                        new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("LOAN"), "bob",
                                "audit", dueAt)));

        List<Integer> statuses = results.stream().map(r -> r.getStatusCode().value()).toList();
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        // 借出先提交则合并 422；合并先提交则借出命中别名 409
        assertThat(statuses).allMatch(s -> s == 200 || s == 409 || s == 422);

        var aliasChain = evidenceService.custodyChain(e2);
        if (statuses.get(0) == 200) {
            // 合并先提交：E2 已转别名且无借出记录
            assertThat(aliasChain.evidence().status()).isEqualTo(EvidenceStatus.ALIAS);
            assertThat(aliasChain.evidence().aliasOf()).isEqualTo(e1);
            assertThat(aliasChain.loans()).isEmpty();
            assertThat(aliasChain.mergeEvents()).hasSize(1);
        } else {
            // 借出先提交：E2 借出未归还，合并未发生
            assertThat(aliasChain.evidence().status()).isEqualTo(EvidenceStatus.BORROWED);
            assertThat(aliasChain.loans()).hasSize(1);
            assertThat(aliasChain.mergeEvents()).isEmpty();
        }
    }

    @Test
    void concurrentDuplicateMergeWithSameCommandKeyReplaysSingleResult() throws Exception {
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        intake("alice", e1);
        intake("alice", e2);
        MergeRequest request = new MergeRequest(uniqueKey("CMD"), e1, 1L, e2, 1L);

        List<ResponseEntity<String>> results = runConcurrently(
                () -> controller.merge("alice", request),
                () -> controller.merge("alice", request));

        assertThat(results.stream().map(r -> r.getStatusCode().value())).containsOnly(200);
        // 并发重放返回同一首次响应，且只落一条合并事件
        assertThat(results.get(0).getBody()).isEqualTo(results.get(1).getBody());
        var chain = evidenceService.custodyChain(e1);
        assertThat(chain.mergeEvents()).hasSize(1);
        assertThat(chain.evidence().version()).isEqualTo(2);
        assertThat(evidenceService.custodyChain(e2).evidence().status())
                .isEqualTo(EvidenceStatus.ALIAS);
    }

    @Test
    void concurrentCrossMergesExactlyOneSucceeds() throws Exception {
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        intake("alice", e1);
        intake("alice", e2);

        List<ResponseEntity<String>> results = runConcurrently(
                () -> controller.merge("alice", new MergeRequest(uniqueKey("CMD"), e1, 1L, e2, 1L)),
                () -> controller.merge("alice", new MergeRequest(uniqueKey("CMD"), e2, 1L, e1, 1L)));

        List<Integer> statuses = results.stream().map(r -> r.getStatusCode().value()).toList();
        // 恰一个成功；失败者看到已推进的版本（409）或别名状态（409）
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409);

        var chain1 = evidenceService.custodyChain(e1);
        var chain2 = evidenceService.custodyChain(e2);
        // 恰一条合并事件；恰一个证物为 ALIAS，另一个保持 SEALED
        assertThat(chain1.mergeEvents().size() + chain2.mergeEvents().size()).isEqualTo(2);
        assertThat(chain1.mergeEvents().get(0)).isEqualTo(chain2.mergeEvents().get(0));
        long aliasCount = List.of(chain1.evidence().status(), chain2.evidence().status()).stream()
                .filter(s -> s == EvidenceStatus.ALIAS).count();
        assertThat(aliasCount).isEqualTo(1);
    }
}
