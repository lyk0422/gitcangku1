package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CaseTransferRequest;
import com.example.starter.evidence.dto.CaseTransferRevokeRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 跨案移交并发与幂等边界测试：同一证物并发移交最多一笔成功；
 * 并发撤销最多一笔成功；同键并发重放只产生一个批次。
 */
@SpringBootTest
class CaseTransferConcurrencyTest {

    private static final LocalDateTime VALID_FROM = LocalDateTime.of(2020, 1, 1, 0, 0);
    private static final LocalDateTime VALID_TO = LocalDateTime.of(2099, 12, 31, 23, 59, 59);

    @Autowired
    private CaseTransferController caseTransferController;

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private CaseTransferService caseTransferService;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private record Fixture(String sourceCase, String targetCase, String orderVersion,
                           String sourceCustodian, String targetCustodian) {
    }

    private Fixture newFixture() {
        Fixture f = new Fixture(uniqueKey("CASE-SRC"), uniqueKey("CASE-TGT"),
                uniqueKey("ORD"), "alice", "bob");
        caseTransferService.registerOrder(f.orderVersion(), VALID_FROM, VALID_TO);
        caseTransferService.grantCustodian(f.sourceCase(), f.sourceCustodian());
        caseTransferService.grantCustodian(f.targetCase(), f.targetCustodian());
        return f;
    }

    private void intake(String actor, String evidenceKey, String caseKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, caseKey, "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private CaseTransferRequest transferRequest(Fixture f, String commandKey,
                                                List<String> evidenceKeys) {
        return new CaseTransferRequest(commandKey, f.sourceCase(), f.targetCase(),
                f.orderVersion(), f.sourceCustodian(), f.targetCustodian(), evidenceKeys, null);
    }

    private String transferIdOf(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).get("transferId").asText();
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
                                          Callable<ResponseEntity<String>> second)
            throws Exception {
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
    void concurrentCaseTransferOfSameEvidenceExactlyOneSucceeds() throws Exception {
        Fixture f1 = newFixture();
        Fixture f2 = new Fixture(f1.sourceCase(), uniqueKey("CASE-TGT"), f1.orderVersion(),
                f1.sourceCustodian(), f1.targetCustodian());
        caseTransferService.grantCustodian(f2.targetCase(), f2.targetCustodian());
        String ev = uniqueKey("EV");
        intake(f1.sourceCustodian(), ev, f1.sourceCase());

        List<Integer> statuses = runConcurrently(
                () -> caseTransferController.caseTransfer(f1.sourceCustodian(),
                        transferRequest(f1, uniqueKey("CMD"), List.of(ev))),
                () -> caseTransferController.caseTransfer(f2.sourceCustodian(),
                        transferRequest(f2, uniqueKey("CMD"), List.of(ev))));

        // 同一证物并发移交最多一笔成功；另一笔因证物已不属于来源案件得到 422
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);

        // 最终只存在一个移交批次，证物归属与之一致
        var batches = caseTransferService.listByCase(f1.sourceCase());
        assertThat(batches).hasSize(1);
        var batch = batches.get(0);
        var evidence = evidenceController.custodyChain(ev).evidence();
        assertThat(evidence.caseKey()).isEqualTo(batch.targetCaseKey());
        assertThat(evidence.custodianId()).isEqualTo(f1.targetCustodian());
        assertThat(evidence.status()).isEqualTo(EvidenceStatus.SEALED);
        // 双案链与批次一致：来源一条移出、目标一条移入
        assertThat(caseTransferService.listCaseLinks(f1.sourceCase())).hasSize(1);
        assertThat(caseTransferService.listCaseLinks(batch.targetCaseKey())).hasSize(1);
    }

    @Test
    void concurrentRevokeExactlyOneSucceeds() throws Exception {
        Fixture f = newFixture();
        String ev = uniqueKey("EV");
        intake(f.sourceCustodian(), ev, f.sourceCase());
        ResponseEntity<String> transferred = caseTransferController.caseTransfer(
                f.sourceCustodian(), transferRequest(f, uniqueKey("CMD"), List.of(ev)));
        assertThat(transferred.getStatusCode().value()).isEqualTo(200);
        String transferId = transferIdOf(transferred);

        List<Integer> statuses = runConcurrently(
                () -> caseTransferController.revokeCaseTransfer(f.sourceCustodian(), transferId,
                        new CaseTransferRevokeRequest(uniqueKey("CMD"), f.orderVersion(),
                                f.sourceCustodian(), f.targetCustodian())),
                () -> caseTransferController.revokeCaseTransfer(f.targetCustodian(), transferId,
                        new CaseTransferRevokeRequest(uniqueKey("CMD"), f.orderVersion(),
                                f.sourceCustodian(), f.targetCustodian())));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        // 反向链只追加一次，证物回到来源案件
        var sourceLinks = caseTransferService.listCaseLinks(f.sourceCase());
        assertThat(sourceLinks.stream().map(l -> l.direction().name()).toList())
                .containsExactly("OUT", "REVOKE_IN");
        var targetLinks = caseTransferService.listCaseLinks(f.targetCase());
        assertThat(targetLinks.stream().map(l -> l.direction().name()).toList())
                .containsExactly("IN", "REVOKE_OUT");
        var evidence = evidenceController.custodyChain(ev).evidence();
        assertThat(evidence.caseKey()).isEqualTo(f.sourceCase());
        assertThat(evidence.custodianId()).isEqualTo(f.sourceCustodian());
        assertThat(caseTransferService.getTransfer(transferId).status())
                .isEqualTo(CaseTransferStatus.REVOKED);
    }

    @Test
    void concurrentSameCommandKeyTransferReplaysSingleBatch() throws Exception {
        Fixture f = newFixture();
        String ev = uniqueKey("EV");
        intake(f.sourceCustodian(), ev, f.sourceCase());
        String commandKey = uniqueKey("CMD");
        CaseTransferRequest request = transferRequest(f, commandKey, List.of(ev));

        List<Integer> statuses = runConcurrently(
                () -> caseTransferController.caseTransfer(f.sourceCustodian(), request),
                () -> caseTransferController.caseTransfer(f.sourceCustodian(), request));

        assertThat(statuses).containsOnly(200);
        // 只产生一个批次与一对双案链
        assertThat(caseTransferService.listByCase(f.sourceCase())).hasSize(1);
        assertThat(caseTransferService.listCaseLinks(f.sourceCase())).hasSize(1);
        assertThat(caseTransferService.listCaseLinks(f.targetCase())).hasSize(1);
    }
}
