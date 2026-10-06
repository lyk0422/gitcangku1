package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.ContainerCommandRequest;
import com.example.starter.evidence.dto.ContainerCreateRequest;
import com.example.starter.evidence.dto.ContainerMembersRequest;
import com.example.starter.evidence.dto.ContainerTransferInitiateRequest;
import com.example.starter.evidence.dto.IntakeRequest;
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
 * 封存箱并发测试：接收、拆箱与异常登记竞争只形成合法后态；
 * 同一证物并发装箱只有一处生效；同 requestId 并发建箱重放同一结果。
 */
@SpringBootTest
class ContainerConcurrencyTest {

    @Autowired
    private ContainerController containerController;

    @Autowired
    private EvidenceController evidenceController;

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

    private void createBox(String actor, String containerKey) {
        ResponseEntity<String> response = containerController.create(actor,
                new ContainerCreateRequest(uniqueKey("REQ"), containerKey, "BOXSEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private void pack(String actor, String containerKey, int expectedVersion,
                      List<String> evidenceKeys, List<String> containerKeys) {
        ResponseEntity<String> response = containerController.pack(actor, containerKey,
                new ContainerMembersRequest(uniqueKey("REQ"), expectedVersion,
                        evidenceKeys, containerKeys));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    private void initiate(String actor, String containerKey, int expectedVersion, String toCustodian) {
        ResponseEntity<String> response = containerController.initiateTransfer(actor, containerKey,
                new ContainerTransferInitiateRequest(uniqueKey("REQ"), toCustodian, expectedVersion));
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
    void concurrentAcceptAndMemberSealFailureOnlyLegalOutcomes() throws Exception {
        String box = uniqueKey("BOX");
        String evidence = uniqueKey("EV");
        createBox("alice", box);
        intake("alice", evidence);
        pack("alice", box, 1, List.of(evidence), List.of());
        initiate("alice", box, 2, "bob");

        List<Integer> statuses = runConcurrently(
                () -> containerController.acceptTransfer("bob", box,
                        new ContainerCommandRequest(uniqueKey("REQ"))),
                () -> evidenceController.inspectSeal("alice", evidence,
                        new SealInspectionRequest(uniqueKey("CMD"), false, "cracked")));

        // 异常登记先提交则接收 422；接收先提交则旧保管人核验 409
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409 || s == 422);

        var tree = containerService.tree(box);
        var chain = evidenceService.custodyChain(evidence);
        if (chain.evidence().status() == EvidenceStatus.SEAL_BROKEN) {
            // 异常登记先提交：整箱接收必须失败，保管权不变，树仍冻结待接收
            assertThat(tree.container().custodianId()).isEqualTo("alice");
            assertThat(tree.container().status()).isEqualTo(ContainerStatus.TRANSFER_PENDING);
            assertThat(chain.evidence().custodianId()).isEqualTo("alice");
        } else {
            // 接收先提交：保管权一次推进，旧保管人核验被拒
            assertThat(tree.container().custodianId()).isEqualTo("bob");
            assertThat(tree.container().status()).isEqualTo(ContainerStatus.SEALED);
            assertThat(chain.evidence().custodianId()).isEqualTo("bob");
        }
    }

    @Test
    void concurrentAcceptAndUnpackNeverSplitsBox() throws Exception {
        String box = uniqueKey("BOX");
        String evidence = uniqueKey("EV");
        createBox("alice", box);
        intake("alice", evidence);
        pack("alice", box, 1, List.of(evidence), List.of());
        initiate("alice", box, 2, "bob");

        List<Integer> statuses = runConcurrently(
                () -> containerController.acceptTransfer("bob", box,
                        new ContainerCommandRequest(uniqueKey("REQ"))),
                () -> containerController.unpack("alice", box,
                        new ContainerMembersRequest(uniqueKey("REQ"), 2,
                                List.of(evidence), List.of())));

        // 待交接期间禁改成员：拆箱必须 409，接收 200；不允许出现半箱
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var tree = containerService.tree(box);
        assertThat(tree.container().custodianId()).isEqualTo("bob");
        assertThat(tree.evidenceItems()).hasSize(1);
        assertThat(tree.evidenceItems().get(0).evidenceKey()).isEqualTo(evidence);
        assertThat(tree.evidenceItems().get(0).custodianId()).isEqualTo("bob");
    }

    @Test
    void concurrentPackSameEvidenceIntoTwoBoxesExactlyOneWins() throws Exception {
        String boxA = uniqueKey("BOX");
        String boxB = uniqueKey("BOX");
        String evidence = uniqueKey("EV");
        createBox("alice", boxA);
        createBox("alice", boxB);
        intake("alice", evidence);

        List<Integer> statuses = runConcurrently(
                () -> containerController.pack("alice", boxA,
                        new ContainerMembersRequest(uniqueKey("REQ"), 1,
                                List.of(evidence), List.of())),
                () -> containerController.pack("alice", boxB,
                        new ContainerMembersRequest(uniqueKey("REQ"), 1,
                                List.of(evidence), List.of())));

        // 证物仅能有一个直接父箱：一处 200，另一处 422
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);
        int inA = containerService.tree(boxA).evidenceItems().size();
        int inB = containerService.tree(boxB).evidenceItems().size();
        assertThat(inA + inB).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateCreateWithSameRequestIdReplaysSingleResult() throws Exception {
        String box = uniqueKey("BOX");
        String requestId = uniqueKey("REQ");
        ContainerCreateRequest request = new ContainerCreateRequest(requestId, box, "BOXSEAL-1");

        List<Integer> statuses = runConcurrently(
                () -> containerController.create("alice", request),
                () -> containerController.create("alice", request));

        assertThat(statuses).containsOnly(201);
        var tree = containerService.tree(box);
        assertThat(tree.container().custodianId()).isEqualTo("alice");
        assertThat(tree.container().version()).isEqualTo(1);
    }
}
