package com.example.starter.evidence.aliquot;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.EvidenceController;
import com.example.starter.evidence.aliquot.dto.SamplingApplyRequest;
import com.example.starter.evidence.aliquot.dto.SamplingItemInput;
import com.example.starter.evidence.aliquot.dto.SamplingReviewRequest;
import com.example.starter.evidence.dto.TransferInitiateRequest;
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
 * 多母样联合取样并发边界的真实 H2 数据库测试：
 * 重叠母样申请、申请/确认与原交接并发时不得超额预留、不得半生成子样或映射。
 */
@SpringBootTest
class AliquotConcurrencyTest {

    @Autowired
    private AliquotController aliquotController;

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private AliquotService aliquotService;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new com.example.starter.evidence.dto.IntakeRequest(
                        uniqueKey("CMD"), evidenceKey, "CASE-C", "BLOOD", "SEAL-X"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private void registerMother(String actor, String sampleKey, long totalQty) {
        ResponseEntity<String> response = aliquotController.registerMother(actor, sampleKey,
                new com.example.starter.evidence.aliquot.dto.MotherRegisterRequest(
                        uniqueKey("CMD"), totalQty, "ML"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private String[] twoMothers(long q1, long q2) {
        String s1 = uniqueKey("M");
        String s2 = uniqueKey("M");
        intake("alice", s1);
        intake("alice", s2);
        registerMother("alice", s1, q1);
        registerMother("alice", s2, q2);
        return new String[]{s1, s2};
    }

    private SamplingApplyRequest applyRequest(String requestId, String aliquotKey,
                                              String s1, long q1, String s2, long q2) {
        return new SamplingApplyRequest(uniqueKey("CMD"), requestId, aliquotKey,
                List.of(new SamplingItemInput(s1, q1), new SamplingItemInput(s2, q2)));
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
            start.await(10, TimeUnit.SECONDS);
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
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentOverlappingApplicationsNeverOverReserve() throws Exception {
        // 两件母样各 10：A 取 5/5，B 取 8/8；串行化下只有一单能成功。
        String[] mothers = twoMothers(10, 10);
        SamplingApplyRequest reqA = applyRequest(uniqueKey("REQ"), uniqueKey("ALQ"),
                mothers[0], 5, mothers[1], 5);
        SamplingApplyRequest reqB = applyRequest(uniqueKey("REQ"), uniqueKey("ALQ"),
                mothers[0], 8, mothers[1], 8);

        List<Integer> statuses = runConcurrently(
                () -> aliquotController.apply("alice", reqA),
                () -> aliquotController.apply("alice", reqB));

        assertThat(statuses).containsExactlyInAnyOrder(201, 422);

        var m1 = aliquotService.motherView(mothers[0]);
        var m2 = aliquotService.motherView(mothers[1]);
        // 预留总量不得超过成功单，且恒等式成立
        long reserved1 = m1.reservedQty();
        long reserved2 = m2.reservedQty();
        assertThat(reserved1).isIn(5L, 8L);
        assertThat(reserved2).isIn(5L, 8L);
        assertThat(reserved1 + m1.consumedQty()).isLessThanOrEqualTo(m1.totalQty());
        assertThat(reserved2 + m2.consumedQty()).isLessThanOrEqualTo(m2.totalQty());
        if (statuses.get(0) == 201) {
            assertThat(reserved1).isEqualTo(5);
            assertThat(reserved2).isEqualTo(5);
            assertThat(aliquotService.orderView(reqA.requestId()).status()).isEqualTo("PENDING");
        }
    }

    @Test
    void concurrentApplicationAndTransferNeverLeavesInconsistentState() throws Exception {
        String[] mothers = twoMothers(100, 100);
        SamplingApplyRequest req = applyRequest(uniqueKey("REQ"), uniqueKey("ALQ"),
                mothers[0], 10, mothers[1], 10);

        List<Integer> statuses = runConcurrently(
                () -> aliquotController.apply("alice", req),
                () -> evidenceController.initiateTransfer("alice", mothers[0],
                        new TransferInitiateRequest(uniqueKey("CMD"), "bob")));

        int applyStatus = statuses.get(0);
        int transferStatus = statuses.get(1);
        assertThat(applyStatus).isIn(201, 409, 422);
        assertThat(transferStatus).isEqualTo(200);

        var m1 = aliquotService.motherView(mothers[0]);
        if (applyStatus == 201) {
            // 申请先提交：预留成功，但交接随即改变了母样证物版本，任何确认都必须 409，永不耗用。
            assertThat(m1.reservedQty()).isEqualTo(10);
            int firstConfirm;
            try {
                firstConfirm = aliquotController.confirm("bob", req.requestId(),
                        new SamplingReviewRequest(uniqueKey("CMD"), null, null, null))
                        .getStatusCode().value();
            } catch (ApiException e) {
                firstConfirm = e.status().value();
            }
            assertThat(firstConfirm).isEqualTo(409);
            var order = aliquotService.orderView(req.requestId());
            assertThat(order.mappings()).isEmpty();
            assertThat(aliquotService.motherView(mothers[0]).consumedQty()).isZero();
            assertThat(aliquotService.motherView(mothers[1]).consumedQty()).isZero();
        } else {
            // 交接先提交：整单失败且无预留
            assertThat(m1.reservedQty()).isZero();
            assertThat(aliquotService.motherView(mothers[1]).reservedQty()).isZero();
        }
    }

    @Test
    void concurrentSecondConfirmAndTransferNeverHalfGenerate() throws Exception {
        String[] mothers = twoMothers(100, 100);
        String requestId = uniqueKey("REQ");
        String aliquotKey = uniqueKey("ALQ");
        SamplingApplyRequest req = applyRequest(requestId, aliquotKey,
                mothers[0], 10, mothers[1], 10);
        assertThat(aliquotController.apply("alice", req).getStatusCode().value()).isEqualTo(201);
        assertThat(aliquotController.confirm("bob", requestId,
                new SamplingReviewRequest(uniqueKey("CMD"), "first", null, null))
                .getStatusCode().value()).isEqualTo(200);

        var pending = aliquotService.orderView(requestId);
        Map<String, Long> versions = new java.util.LinkedHashMap<>();
        for (var item : pending.items()) {
            versions.put(item.sampleKey(), item.sampleVersion());
        }

        List<Integer> statuses = runConcurrently(
                () -> aliquotController.confirm("carol", requestId,
                        new SamplingReviewRequest(uniqueKey("CMD"), "second", 1L, versions)),
                () -> evidenceController.initiateTransfer("alice", mothers[0],
                        new TransferInitiateRequest(uniqueKey("CMD"), "bob")));

        int confirmStatus = statuses.get(0);
        int transferStatus = statuses.get(1);
        assertThat(confirmStatus).isIn(200, 409);
        assertThat(transferStatus).isEqualTo(200);

        var order = aliquotService.orderView(requestId);
        if (confirmStatus == 200) {
            // 确认先于交接提交：完整耗用、映射两件、子样 SEALED
            assertThat(order.status()).isEqualTo("CONFIRMED");
            assertThat(order.mappings()).hasSize(2);
            assertThat(aliquotService.motherView(mothers[0]).consumedQty()).isEqualTo(10);
            assertThat(aliquotService.motherView(mothers[1]).consumedQty()).isEqualTo(10);
            var chain = evidenceController.custodyChain(aliquotKey);
            assertThat(chain.evidence().status().name()).isEqualTo("SEALED");
        } else {
            // 交接先提交：确认失败，不得耗用或生成任何映射/子样，预留保留待拒绝释放
            assertThat(order.status()).isEqualTo("PENDING");
            assertThat(order.mappings()).isEmpty();
            assertThat(aliquotService.motherView(mothers[0]).consumedQty()).isZero();
            assertThat(aliquotService.motherView(mothers[1]).consumedQty()).isZero();
            assertThat(aliquotService.motherView(mothers[0]).reservedQty()).isEqualTo(10);
            // 拒绝可一次释放全部预留
            assertThat(aliquotController.reject("carol", requestId,
                    new SamplingReviewRequest(uniqueKey("CMD"), "release", null, null))
                    .getStatusCode().value()).isEqualTo(200);
            assertThat(aliquotService.motherView(mothers[0]).reservedQty()).isZero();
            assertThat(aliquotService.motherView(mothers[1]).reservedQty()).isZero();
        }
    }

    @Test
    void concurrentDuplicateRequestSameSetBothReplaySingleReservation() throws Exception {
        String[] mothers = twoMothers(100, 100);
        String requestId = uniqueKey("REQ");
        String aliquotKey = uniqueKey("ALQ");
        // 不同 commandKey、同参集合（换序）：并发下二者都应成功且只预留一次
        SamplingApplyRequest reqA = new SamplingApplyRequest(uniqueKey("CMD"), requestId,
                aliquotKey, List.of(
                        new SamplingItemInput(mothers[0], 7L),
                        new SamplingItemInput(mothers[1], 9L)));
        SamplingApplyRequest reqB = new SamplingApplyRequest(uniqueKey("CMD"), requestId,
                aliquotKey, List.of(
                        new SamplingItemInput(mothers[1], 9L),
                        new SamplingItemInput(mothers[0], 7L)));

        List<Integer> statuses = runConcurrently(
                () -> aliquotController.apply("alice", reqA),
                () -> aliquotController.apply("alice", reqB));

        assertThat(statuses).containsOnly(201);
        assertThat(aliquotService.motherView(mothers[0]).reservedQty()).isEqualTo(7);
        assertThat(aliquotService.motherView(mothers[1]).reservedQty()).isEqualTo(9);
        assertThat(aliquotService.orderView(requestId).items()).hasSize(2);
    }

    @Test
    void concurrentApplicationsWithSameAliquotKeyExactlyOneWins() throws Exception {
        String[] mothersA = twoMothers(100, 100);
        String[] mothersB = twoMothers(100, 100);
        String aliquotKey = uniqueKey("ALQ");
        SamplingApplyRequest reqA = applyRequest(uniqueKey("REQ"), aliquotKey,
                mothersA[0], 1, mothersA[1], 1);
        SamplingApplyRequest reqB = applyRequest(uniqueKey("REQ"), aliquotKey,
                mothersB[0], 1, mothersB[1], 1);

        List<Integer> statuses = runConcurrently(
                () -> aliquotController.apply("alice", reqA),
                () -> aliquotController.apply("alice", reqB));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        // 败者整单回滚，不产生预留
        SamplingApplyRequest winner = statuses.get(0) == 201 ? reqA : reqB;
        SamplingApplyRequest loser = winner == reqA ? reqB : reqA;
        var order = aliquotService.orderView(winner.requestId());
        assertThat(order.aliquotKey()).isEqualTo(aliquotKey);
        for (SamplingItemInput item : loser.items()) {
            assertThat(aliquotService.motherView(item.sampleKey()).reservedQty()).isZero();
        }
    }
}
