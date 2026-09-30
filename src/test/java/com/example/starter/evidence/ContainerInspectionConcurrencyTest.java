package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.ContainerCreateRequest;
import com.example.starter.evidence.dto.ContainerInspectionRequest;
import com.example.starter.evidence.dto.ContainerPlaceRequest;
import com.example.starter.evidence.dto.IntakeRequest;
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
 * 容器巡检并发边界测试（真实 H2 行锁验证）：
 * 证物交接接受与异常双人确认隔离竞争时，转移与隔离只能按提交顺序落到同一行、同一位置，
 * 不允许转移已换保管人而隔离仍记原位置的撕裂；两名见证人并发首提同一 requestId 只产生一张巡检单。
 */
@SpringBootTest
class ContainerInspectionConcurrencyTest {

    @Autowired
    private EvidenceController controller;

    @Autowired
    private EvidenceService evidenceService;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = controller.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-C", "DOCUMENT",
                        "SEAL-" + evidenceKey));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private String createContainer(String actor) {
        String containerKey = uniqueKey("CTR");
        ResponseEntity<String> response = controller.createContainer(actor,
                new ContainerCreateRequest(uniqueKey("CMD"), containerKey, "VAULT-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return containerKey;
    }

    private void place(String actor, String evidenceKey, String containerKey) {
        ResponseEntity<String> response = controller.placeIntoContainer(actor, evidenceKey,
                new ContainerPlaceRequest(uniqueKey("CMD"), containerKey));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
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
    void transferAcceptAndAnomalyQuarantineNeverLandOnDifferentLocations() throws Exception {
        // 容器 C（保管人 jia）内有 A、B；A 已发起交接给 yi（待接收）
        String a = uniqueKey("EV-A");
        String b = uniqueKey("EV-B");
        intake("jia", a);
        intake("jia", b);
        String c = createContainer("jia");
        place("jia", a, c);
        place("jia", b, c);
        initiate("jia", a, "yi");

        // 第一见证人 jia 已建单（仅一人确认，不动证物）
        String requestId = uniqueKey("REQ");
        ResponseEntity<String> firstWitness = controller.inspectContainer("jia", c,
                new ContainerInspectionRequest(requestId, 1, SealResult.ANOMALY, "cracked"));
        assertThat(firstWitness.getStatusCode().value()).isEqualTo(200);

        // 第二见证人 yi 的异常确认 与 yi 接受 A 的交接 同时发起
        List<Integer> statuses = runConcurrently(
                () -> controller.acceptTransfer("yi", a, new CommandRequest(uniqueKey("CMD"))),
                () -> controller.inspectContainer("yi", c,
                        new ContainerInspectionRequest(requestId, 1, SealResult.ANOMALY, "cracked")));

        // 巡检确认恒为 200；交接接受要么先提交 200，要么被隔离拒绝 409
        assertThat(statuses).allMatch(s -> s == 200 || s == 409);
        assertThat(statuses.stream().filter(s -> s == 200).count()).isBetween(1L, 2L);

        var chain = evidenceService.custodyChain(a);
        var transfer = chain.transfers().get(0);
        // 位置始终在容器 C，保管人只有一份终态，不存在转移到 yi、隔离快照却仍记 jia 的分裂
        assertThat(chain.evidence().containerKey()).isEqualTo(c);
        if (transfer.status() == TransferStatus.ACCEPTED) {
            // 交接先提交：保管人已切换 yi，随后隔离在同一行生效，快照责任人必须是 yi
            assertThat(statuses).contains(200, 200);
            assertThat(chain.evidence().custodianId()).isEqualTo("yi");
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.QUARANTINED);
        } else {
            // 隔离先提交：待接收交接作废，接受得到 409，保管人仍 jia
            assertThat(statuses).containsExactlyInAnyOrder(409, 200);
            assertThat(transfer.status()).isEqualTo(TransferStatus.CANCELLED);
            assertThat(chain.evidence().custodianId()).isEqualTo("jia");
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.QUARANTINED);
        }

        // B 必然与 A 在同一事务批次隔离；巡检清单冻结在同一容器位置
        var chainB = evidenceService.custodyChain(b);
        assertThat(chainB.evidence().status()).isEqualTo(EvidenceStatus.QUARANTINED);
        assertThat(chainB.evidence().containerKey()).isEqualTo(c);
        var inspection = evidenceService.inspectionView(requestId);
        assertThat(inspection.status()).isEqualTo(InspectionStatus.CONFIRMED_ANOMALY);
        assertThat(inspection.witnesses()).hasSize(2);
        assertThat(inspection.items()).hasSize(2);
        assertThat(inspection.items()).allSatisfy(item -> {
            assertThat(item.snapshotContainerKey()).isEqualTo(c);
            assertThat(item.disposition()).isEqualTo(ItemDisposition.QUARANTINED);
            // 快照责任人必须等于隔离落库时的实际保管人，不能与当前行保管人矛盾
            var itemChain = evidenceService.custodyChain(item.evidenceKey());
            assertThat(item.snapshotCustodianId()).isEqualTo(itemChain.evidence().custodianId());
        });
    }

    @Test
    void twoWitnessesSubmittingSameRequestIdConcurrentlyCreateSingleInspection() throws Exception {
        String a = uniqueKey("EV-A");
        String b = uniqueKey("EV-B");
        intake("jia", a);
        intake("jia", b);
        String c = createContainer("jia");
        place("jia", a, c);
        place("jia", b, c);

        String requestId = uniqueKey("REQ");
        ContainerInspectionRequest request =
                new ContainerInspectionRequest(requestId, 1, SealResult.ANOMALY, "cracked");
        List<Integer> statuses = runConcurrently(
                () -> controller.inspectContainer("jia", c, request),
                () -> controller.inspectContainer("yi", c, request));

        // 两人并发首提：先到建单，后到作为第二见证人完成隔离，双方都成功
        assertThat(statuses).containsExactly(200, 200);

        var inspection = evidenceService.inspectionView(requestId);
        assertThat(inspection.status()).isEqualTo(InspectionStatus.CONFIRMED_ANOMALY);
        assertThat(inspection.witnesses()).hasSize(2);
        assertThat(inspection.witnesses().stream().map(w -> w.witnessId()).sorted().toList())
                .containsExactly("jia", "yi");
        assertThat(inspection.items()).hasSize(2);
        assertThat(evidenceService.custodyChain(a).evidence().status())
                .isEqualTo(EvidenceStatus.QUARANTINED);
        assertThat(evidenceService.custodyChain(b).evidence().status())
                .isEqualTo(EvidenceStatus.QUARANTINED);
    }
}
