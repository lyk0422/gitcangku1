package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.ResealApplyRequest;
import com.example.starter.evidence.dto.SealInspectionRequest;
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
 * 双人重新封存并发边界测试：确认/撤销竞争仅一个终态成功；确认与借出竞争符合
 * “确认提交前禁止借出、确认先提交后可借出”；同命令键并发申请只产生一笔申请。
 * 全部基于真实 H2(MySQL 模式) 行锁与事务。
 */
@SpringBootTest
class ResealConcurrencyTest {

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

    private void breakSeal(String evidenceKey) {
        ResponseEntity<String> response = controller.inspectSeal("alice", evidenceKey,
                new SealInspectionRequest(uniqueKey("CMD"), false, "cracked"));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    private String applyPending(String evidenceKey, String witness) {
        String resealKey = uniqueKey("RS");
        ResponseEntity<String> response = controller.applyReseal("alice", evidenceKey,
                new ResealApplyRequest(uniqueKey("CMD"), resealKey, witness,
                        "tampered seal needs renewal", "SEAL-2"));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return resealKey;
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
    void concurrentConfirmAndCancelExactlyOneTerminalWins() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        breakSeal(evidenceKey);
        String resealKey = applyPending(evidenceKey, "carol");

        List<Integer> statuses = runConcurrently(
                () -> controller.confirmReseal("carol", evidenceKey, resealKey,
                        new CommandRequest(uniqueKey("CMD"))),
                () -> controller.cancelReseal("alice", evidenceKey, resealKey,
                        new CommandRequest(uniqueKey("CMD"))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.reseals()).hasSize(1);
        var application = chain.reseals().get(0);
        if (application.status() == ResealStatus.CONFIRMED) {
            // 确认先提交：证物恢复 SEALED 并换封条，撤销方得到 409
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(chain.evidence().sealNo()).isEqualTo("SEAL-2");
            assertThat(application.oldSealNo()).isEqualTo("SEAL-1");
            assertThat(application.confirmedSealNo()).isEqualTo("SEAL-2");
        } else {
            // 撤销先提交：证物保持异常、不换封条
            assertThat(application.status()).isEqualTo(ResealStatus.CANCELLED);
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEAL_BROKEN);
            assertThat(chain.evidence().sealNo()).isEqualTo("SEAL-1");
        }
    }

    @Test
    void concurrentDoubleConfirmOnlyOneSucceeds() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        breakSeal(evidenceKey);
        String resealKey = applyPending(evidenceKey, "carol");

        List<Integer> statuses = runConcurrently(
                () -> controller.confirmReseal("carol", evidenceKey, resealKey,
                        new CommandRequest(uniqueKey("CMD"))),
                () -> controller.confirmReseal("carol", evidenceKey, resealKey,
                        new CommandRequest(uniqueKey("CMD"))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(chain.evidence().sealNo()).isEqualTo("SEAL-2");
        assertThat(chain.reseals()).hasSize(1);
    }

    @Test
    void concurrentConfirmAndBorrowNeverLeavesInconsistentState() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        breakSeal(evidenceKey);
        String resealKey = applyPending(evidenceKey, "carol");

        Callable<ResponseEntity<String>> confirm = () -> controller.confirmReseal(
                "carol", evidenceKey, resealKey, new CommandRequest(uniqueKey("CMD")));
        Callable<ResponseEntity<String>> borrow = () -> controller.borrow("alice", evidenceKey,
                new com.example.starter.evidence.dto.LoanCreateRequest(
                        uniqueKey("CMD"), uniqueKey("LOAN"), "bob", "lab",
                        java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(24)));

        List<Integer> statuses = runConcurrently(confirm, borrow);

        var chain = evidenceService.custodyChain(evidenceKey);
        // 确认必然成功（唯一终态）
        assertThat(chain.reseals().get(0).status()).isEqualTo(ResealStatus.CONFIRMED);
        if (statuses.stream().filter(s -> s == 200).count() == 2) {
            // 确认先提交：借出随后按原规则成功
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.BORROWED);
        } else {
            // 借出先拿到锁：确认尚未提交，借出被拒；随后确认成功
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        }
    }

    @Test
    void concurrentDuplicateApplyWithSameCommandKeyCreatesSingleApplication() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        breakSeal(evidenceKey);
        String commandKey = uniqueKey("CMD");
        String resealKey = uniqueKey("RS");
        ResealApplyRequest request = new ResealApplyRequest(commandKey, resealKey, "carol",
                "tampered seal needs renewal", "SEAL-2");

        List<Integer> statuses = runConcurrently(
                () -> controller.applyReseal("alice", evidenceKey, request),
                () -> controller.applyReseal("alice", evidenceKey, request));

        assertThat(statuses).containsOnly(200);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.reseals()).hasSize(1);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEAL_BROKEN);
    }
}
