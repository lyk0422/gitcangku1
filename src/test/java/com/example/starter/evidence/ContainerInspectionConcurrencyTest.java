package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.ContainerCreateRequest;
import com.example.starter.evidence.dto.ContainerInspectionRequest;
import com.example.starter.evidence.dto.ContainerPlaceRequest;
import com.example.starter.evidence.dto.IntakeRequest;
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
 * 容器巡检并发与幂等边界测试（真实 H2 行锁与事务）：
 * 证物转移与异常巡检确认竞争时按事务提交顺序生效，转移和隔离不会分别落到不同位置；
 * 同 requestId 并发确认只产生一份隔离清单。
 */
@SpringBootTest
class ContainerInspectionConcurrencyTest {

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private ContainerController containerController;

    @Autowired
    private ContainerService containerService;

    @Autowired
    private EvidenceService evidenceService;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private void createContainer(String actor, String containerKey, String location) {
        ResponseEntity<String> response = containerController.create(actor,
                new ContainerCreateRequest(uniqueKey("CMD"), containerKey, location));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private void place(String actor, String containerKey, String evidenceKey) {
        ResponseEntity<String> response = containerController.place(actor, containerKey,
                new ContainerPlaceRequest(uniqueKey("CMD"), evidenceKey));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    /**
     * 并发调用结果：HTTP 状态码 + 响应体（业务异常取其携带状态码，无响应体）。
     */
    private record CallOutcome(int status, String body) {
    }

    private static final class OutcomeCall implements Callable<CallOutcome> {
        private final CountDownLatch ready;
        private final CountDownLatch start;
        private final Callable<ResponseEntity<String>> call;

        OutcomeCall(CountDownLatch ready, CountDownLatch start, Callable<ResponseEntity<String>> call) {
            this.ready = ready;
            this.start = start;
            this.call = call;
        }

        @Override
        public CallOutcome call() throws Exception {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                ResponseEntity<String> response = call.call();
                return new CallOutcome(response.getStatusCode().value(), response.getBody());
            } catch (ApiException e) {
                return new CallOutcome(e.status().value(), "");
            }
        }
    }

    private List<CallOutcome> runConcurrently(Callable<ResponseEntity<String>> first,
                                              Callable<ResponseEntity<String>> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<CallOutcome> f1 = executor.submit(new OutcomeCall(ready, start, first));
            Future<CallOutcome> f2 = executor.submit(new OutcomeCall(ready, start, second));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(f1.get(20, TimeUnit.SECONDS), f2.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentMoveAndBrokenInspectionNeverSplitPositions() throws Exception {
        String containerC = uniqueKey("BOX");
        String containerD = uniqueKey("BOX");
        String evidenceA = uniqueKey("EV");
        String evidenceB = uniqueKey("EV");
        createContainer("alice", containerC, "证物室-C柜");
        createContainer("alice", containerD, "证物室-D柜");
        intake("alice", evidenceA);
        intake("alice", evidenceB);
        place("alice", containerC, evidenceA);
        place("alice", containerC, evidenceB);
        // 容器 C 当前版本 2；甲先行确认封条异常（PENDING，不改变状态）
        String requestId = uniqueKey("INSP");
        ResponseEntity<String> pending = containerController.inspect("custodian-jia", containerC,
                new ContainerInspectionRequest(requestId, 2L, false, null));
        assertThat(pending.getStatusCode().value()).isEqualTo(201);

        // 乙确认巡检（将隔离箱内全部证物）与 alice 将 B 转移到容器 D 并发
        List<CallOutcome> outcomes = runConcurrently(
                () -> containerController.inspect("custodian-yi", containerC,
                        new ContainerInspectionRequest(requestId, 2L, false, null)),
                () -> containerController.place("alice", containerD,
                        new ContainerPlaceRequest(uniqueKey("CMD"), evidenceB)));

        // 恰有一个先提交成功，另一个按提交后的状态被拒绝（409）
        assertThat(outcomes.stream().map(CallOutcome::status).toList())
                .containsExactlyInAnyOrder(200, 409);

        var detailC = containerService.detail(containerC);
        var detailD = containerService.detail(containerD);
        var chainB = evidenceService.custodyChain(evidenceB);
        boolean bIsolated = detailC.inspections().stream()
                .flatMap(i -> i.items().stream())
                .anyMatch(item -> item.evidenceKey().equals(evidenceB));
        boolean bMovedToD = detailD.container().currentItems().contains(evidenceB);

        // 核心不变式：转移和隔离不能分别落到不同位置（B 不可能既在 D 又被隔离在 C）
        assertThat(bIsolated && bMovedToD).isFalse();
        if (bIsolated) {
            // 巡检确认先提交：A、B 均在 C 隔离，转移被拒绝
            assertThat(chainB.evidence().status()).isEqualTo(EvidenceStatus.QUARANTINED);
            assertThat(detailC.container().status()).isEqualTo(ContainerStatus.ABNORMAL);
            assertThat(detailC.container().currentItems())
                    .containsExactlyInAnyOrder(evidenceA, evidenceB);
            assertThat(detailD.container().currentItems()).isEmpty();
        } else {
            // 转移先提交：B 已到 D 且未被隔离；巡检版本过期，箱内其余证物不受影响
            assertThat(bMovedToD).isTrue();
            assertThat(chainB.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(detailC.container().status()).isEqualTo(ContainerStatus.NORMAL);
            assertThat(detailC.container().currentItems()).containsExactly(evidenceA);
            assertThat(detailC.inspections().get(0).status()).isEqualTo(InspectionStatus.PENDING);
            assertThat(detailC.inspections().get(0).items()).isEmpty();
            assertThat(evidenceService.custodyChain(evidenceA).evidence().status())
                    .isEqualTo(EvidenceStatus.SEALED);
        }
    }

    @Test
    void concurrentSecondConfirmationsProduceSingleIsolationList() throws Exception {
        String containerKey = uniqueKey("BOX");
        String evidenceA = uniqueKey("EV");
        String evidenceB = uniqueKey("EV");
        createContainer("alice", containerKey, "证物室-E柜");
        intake("alice", evidenceA);
        intake("alice", evidenceB);
        place("alice", containerKey, evidenceA);
        place("alice", containerKey, evidenceB);
        String requestId = uniqueKey("INSP");
        containerController.inspect("custodian-jia", containerKey,
                new ContainerInspectionRequest(requestId, 2L, false, null));

        // 乙、丙同时作为第二人确认同一 requestId
        List<CallOutcome> outcomes = runConcurrently(
                () -> containerController.inspect("custodian-yi", containerKey,
                        new ContainerInspectionRequest(requestId, 2L, false, null)),
                () -> containerController.inspect("custodian-bing", containerKey,
                        new ContainerInspectionRequest(requestId, 2L, false, null)));

        // 先提交者完成确认，后到者重放首次结果：双方响应一致
        assertThat(outcomes.stream().map(CallOutcome::status).toList()).containsOnly(200);
        assertThat(outcomes.get(0).body()).isEqualTo(outcomes.get(1).body());

        // 隔离清单恰好两条（A、B 各一条），第二确认人唯一
        var detail = containerService.detail(containerKey);
        assertThat(detail.inspections()).hasSize(1);
        var inspection = detail.inspections().get(0);
        assertThat(inspection.status()).isEqualTo(InspectionStatus.CONFIRMED);
        assertThat(inspection.secondInspector()).isIn("custodian-yi", "custodian-bing");
        assertThat(inspection.items()).hasSize(2);
        assertThat(inspection.items().stream().map(item -> item.evidenceKey()).toList())
                .containsExactlyInAnyOrder(evidenceA, evidenceB);
        assertThat(evidenceService.custodyChain(evidenceA).evidence().status())
                .isEqualTo(EvidenceStatus.QUARANTINED);
        assertThat(evidenceService.custodyChain(evidenceB).evidence().status())
                .isEqualTo(EvidenceStatus.QUARANTINED);
    }

    @Test
    void concurrentFirstSubmissionsSameRequestIdSettleIntoSingleInspection() throws Exception {
        String containerKey = uniqueKey("BOX");
        String evidenceA = uniqueKey("EV");
        createContainer("alice", containerKey, "证物室-F柜");
        intake("alice", evidenceA);
        place("alice", containerKey, evidenceA);
        String requestId = uniqueKey("INSP");

        // 甲、乙并发以同一 requestId 首次提交：request_id 唯一约束只落一条巡检记录，
        // 后到者回滚后按已存在记录处理（作为第二人完成确认）
        List<CallOutcome> outcomes = runConcurrently(
                () -> containerController.inspect("custodian-jia", containerKey,
                        new ContainerInspectionRequest(requestId, 1L, false, null)),
                () -> containerController.inspect("custodian-yi", containerKey,
                        new ContainerInspectionRequest(requestId, 1L, false, null)));

        assertThat(outcomes.stream().map(CallOutcome::status).toList())
                .containsExactlyInAnyOrder(201, 200);

        var detail = containerService.detail(containerKey);
        assertThat(detail.inspections()).hasSize(1);
        var inspection = detail.inspections().get(0);
        assertThat(inspection.status()).isEqualTo(InspectionStatus.CONFIRMED);
        assertThat(inspection.firstInspector()).isNotEqualTo(inspection.secondInspector());
        assertThat(inspection.items()).hasSize(1);
        assertThat(inspection.items().get(0).evidenceKey()).isEqualTo(evidenceA);
        assertThat(evidenceService.custodyChain(evidenceA).evidence().status())
                .isEqualTo(EvidenceStatus.QUARANTINED);
    }
}
