package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.ContainerCommandRequest;
import com.example.starter.evidence.dto.ContainerCreateRequest;
import com.example.starter.evidence.dto.ContainerMemberRequest;
import com.example.starter.evidence.dto.ContainerTransferInitiateRequest;
import com.example.starter.evidence.dto.ContainerTreeView;
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
 * 封存箱并发边界测试：接收、拆箱与异常登记竞争只形成合法后态；
 * 并发装箱同一对象只有一个成功；同 requestId 并发重放只占一次版本。
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

    private void createBox(String actor, String containerKey) {
        ResponseEntity<String> response = containerController.create(actor,
                new ContainerCreateRequest(uniqueKey("REQ"), containerKey, "BOX-SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private void pack(String actor, String containerKey, String memberType,
                      String memberKey, int expectedVersion) {
        ResponseEntity<String> response = containerController.pack(actor, containerKey,
                new ContainerMemberRequest(uniqueKey("REQ"), memberType, memberKey, expectedVersion));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    private void initiate(String actor, String containerKey, String toCustodian) {
        ResponseEntity<String> response = containerController.initiateTransfer(actor, containerKey,
                new ContainerTransferInitiateRequest(uniqueKey("REQ"), toCustodian));
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
    void concurrentAcceptAndUnpackKeepsMembersFrozen() throws Exception {
        String root = uniqueKey("BOX");
        String evidence = uniqueKey("EV");
        createBox("alice", root);
        intake("alice", evidence);
        pack("alice", root, "EVIDENCE", evidence, 1);
        initiate("alice", root, "bob");

        List<Integer> statuses = runConcurrently(
                () -> containerController.acceptTransfer("bob", root,
                        new ContainerCommandRequest(uniqueKey("REQ"))),
                () -> containerController.unpack("alice", root,
                        new ContainerMemberRequest(uniqueKey("REQ"), "EVIDENCE", evidence, 2)));

        // 待交接期间禁改成员：拆箱必败；接收核验完整树后成功
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        ContainerTreeView tree = containerService.tree(root);
        assertThat(tree.custodianId()).isEqualTo("bob");
        assertThat(tree.status()).isEqualTo("SEALED");
        assertThat(tree.members()).hasSize(1);
        assertThat(tree.members().get(0).memberKey()).isEqualTo(evidence);
        assertThat(tree.members().get(0).custodianId()).isEqualTo("bob");
    }

    @Test
    void concurrentAcceptAndBrokenSealRegistrationOnlyLegalOutcomes() throws Exception {
        String root = uniqueKey("BOX");
        String child = uniqueKey("BOX");
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        createBox("alice", root);
        createBox("alice", child);
        intake("alice", e1);
        intake("alice", e2);
        pack("alice", root, "EVIDENCE", e1, 1);
        pack("alice", root, "BOX", child, 2);
        pack("alice", child, "EVIDENCE", e2, 3);
        initiate("alice", root, "bob");

        List<Integer> statuses = runConcurrently(
                () -> containerController.acceptTransfer("bob", root,
                        new ContainerCommandRequest(uniqueKey("REQ"))),
                () -> evidenceController.inspectSeal("alice", e2,
                        new SealInspectionRequest(uniqueKey("CMD"), false, "cracked")));

        int acceptStatus = statuses.get(0);
        int inspectStatus = statuses.get(1);
        ContainerTreeView tree = containerService.tree(root);
        var e1Chain = evidenceService.custodyChain(e1);
        var e2Chain = evidenceService.custodyChain(e2);
        if (acceptStatus == 200) {
            // 接收先提交：整树保管权已推进，旧保管人的异常登记被拒
            assertThat(inspectStatus).isEqualTo(409);
            assertThat(tree.custodianId()).isEqualTo("bob");
            assertThat(e1Chain.evidence().custodianId()).isEqualTo("bob");
            assertThat(e2Chain.evidence().custodianId()).isEqualTo("bob");
            assertThat(e2Chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        } else {
            // 异常登记先提交：E2 封条异常，整箱接收 422，保管权不变，树仍冻结完整
            assertThat(acceptStatus).isEqualTo(422);
            assertThat(inspectStatus).isEqualTo(200);
            assertThat(tree.status()).isEqualTo("TRANSFER_PENDING");
            assertThat(tree.members()).hasSize(2);
            assertThat(e1Chain.evidence().custodianId()).isEqualTo("alice");
            assertThat(e2Chain.evidence().custodianId()).isEqualTo("alice");
            assertThat(e2Chain.evidence().status()).isEqualTo(EvidenceStatus.SEAL_BROKEN);
        }
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
                        new ContainerMemberRequest(uniqueKey("REQ"), "EVIDENCE", evidence, 1)),
                () -> containerController.pack("alice", boxB,
                        new ContainerMemberRequest(uniqueKey("REQ"), "EVIDENCE", evidence, 1)));

        // 重复成员：一个 200，另一个 422；证物只入一个箱
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);
        ContainerTreeView treeA = containerService.tree(boxA);
        ContainerTreeView treeB = containerService.tree(boxB);
        assertThat(treeA.members().size() + treeB.members().size()).isEqualTo(1);
        if (treeA.members().size() == 1) {
            assertThat(treeA.version()).isEqualTo(2);
            assertThat(treeB.version()).isEqualTo(1);
        } else {
            assertThat(treeB.version()).isEqualTo(2);
            assertThat(treeA.version()).isEqualTo(1);
        }
    }

    @Test
    void concurrentDuplicatePackWithSameRequestIdReplaysSingleResult() throws Exception {
        String box = uniqueKey("BOX");
        String evidence = uniqueKey("EV");
        createBox("alice", box);
        intake("alice", evidence);
        String requestId = uniqueKey("REQ");
        ContainerMemberRequest request =
                new ContainerMemberRequest(requestId, "EVIDENCE", evidence, 1);

        List<ResponseEntity<String>> bodies = new java.util.ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<ResponseEntity<String>> call = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return containerController.pack("alice", box, request);
            };
            Future<ResponseEntity<String>> f1 = executor.submit(call::call);
            Future<ResponseEntity<String>> f2 = executor.submit(call::call);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            bodies.add(f1.get(15, TimeUnit.SECONDS));
            bodies.add(f2.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertThat(bodies).allMatch(r -> r.getStatusCode().value() == 200);
        assertThat(bodies.get(0).getBody()).isEqualTo(bodies.get(1).getBody());
        // 版本只加一次，成员只有一条
        ContainerTreeView tree = containerService.tree(box);
        assertThat(tree.version()).isEqualTo(2);
        assertThat(tree.members()).hasSize(1);
    }
}
