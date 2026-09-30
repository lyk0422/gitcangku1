package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.DigestSealRequest;
import com.example.starter.evidence.dto.DigestState;
import com.example.starter.evidence.dto.DigestWitnessRequest;
import com.example.starter.evidence.dto.ExportPackageRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
 * 数字摘要见证并发边界测试（真实 H2 行锁）：
 * 并发重封存与导出必须以提交时锁定的完整版本裁决，不允许混入不同版本内容或产生半成品包；
 * 并发同 requestId 导出只允许一个首次包，另一个重放或按当时版本裁决。
 */
@SpringBootTest
class DigestWitnessConcurrencyTest {

    @Autowired
    private DigestController controller;

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private DigestService digestService;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private String b64(String text) {
        return DigestService.encodeContentUtf8(text);
    }

    private void seal(String actor, String evidenceKey, String contentText) {
        DigestSealRequest request = new DigestSealRequest(uniqueKey("CMD"), "ref://" + evidenceKey,
                b64(contentText));
        int status = controller.seal(actor, evidenceKey, request).getStatusCode().value();
        assertThat(status).isEqualTo(200);
    }

    private void witness(String actor, String evidenceKey, Integer versionNo) {
        DigestWitnessRequest request = new DigestWitnessRequest(uniqueKey("CMD"), versionNo);
        int status = controller.witness(actor, evidenceKey, request).getStatusCode().value();
        assertThat(status).isEqualTo(200);
    }

    /**
     * 记录一次并发调用的 HTTP 状态码（业务异常取其携带状态码）。
     */
    private static final class StatusCall implements Callable<Integer> {
        private final CountDownLatch ready;
        private final CountDownLatch start;
        private final Callable<Integer> call;

        StatusCall(CountDownLatch ready, CountDownLatch start, Callable<Integer> call) {
            this.ready = ready;
            this.start = start;
            this.call = call;
        }

        @Override
        public Integer call() throws Exception {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return call.call();
        }
    }

    private static int statusOf(Callable<?> call) {
        try {
            Object result = call.call();
            if (result instanceof org.springframework.http.ResponseEntity<?> response) {
                return response.getStatusCode().value();
            }
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        } catch (Exception e) {
            throw new AssertionError("并发调用出现非预期异常", e);
        }
    }

    private List<Integer> runConcurrently(Callable<Integer> first, Callable<Integer> second)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> f1 = executor.submit(new StatusCall(ready, start, first));
            Future<Integer> f2 = executor.submit(new StatusCall(ready, start, second));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(f1.get(20, TimeUnit.SECONDS), f2.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 并发重封存与导出：若封存先提交，导出必须裁决到新版本（无见证 -> 422）；
     * 若导出先提交，封存随后生效产生新版本。任何成功导出包都必须整体来自同一个提交版本。
     */
    @Test
    void concurrentResealAndExportAdjudicateAtomicCommittedVersion() throws Exception {
        String ev = uniqueKey("EV");
        evidenceController.intake("alice",
                new IntakeRequest(uniqueKey("CMD"), ev, "CASE-1", "DOCUMENT", "SEAL-1"));
        seal("alice", ev, "content-A-v1");
        witness("alice", ev, 1);
        witness("bob", ev, 1);

        String requestId = uniqueKey("REQ");
        Callable<Integer> exportCall = () -> statusOf(() -> controller.export("alice",
                new ExportPackageRequest(requestId, List.of(ev))));
        Callable<Integer> resealCall = () -> statusOf(() -> controller.seal("alice", ev,
                new DigestSealRequest(uniqueKey("CMD"), "ref://" + ev, b64("content-A-v2"))));

        List<Integer> statuses = runConcurrently(resealCall, exportCall);

        var statusView = digestService.status(ev);
        assertThat(statusView.versions()).hasSize(2);
        assertThat(statusView.currentVersionNo()).isEqualTo(2);
        assertThat(statusView.versions().get(0).status()).isEqualTo("SUPERSEDED");
        assertThat(statusView.versions().get(1).witnesses()).isEmpty();

        var packages = statusView.exports();
        if (statuses.contains(200) && statuses.contains(422)) {
            // 封存先提交：导出裁决到无见证的新版本，必须 422，无包；200 属于封存
            assertThat(packages).isEmpty();
        } else if (statuses.stream().filter(s -> s == 200).count() == 2) {
            // 导出先提交：恰好一个包，且包内明细固定为版本1
            assertThat(packages).hasSize(1);
            var pkg = packages.get(0);
            assertThat(pkg.items()).hasSize(1);
            assertThat(pkg.items().get(0).versionNo()).isEqualTo(1);
        } else {
            throw new AssertionError("非预期并发状态组合: " + statuses);
        }
        // 任何情况下都不得出现两个导出包
        assertThat(packages).hasSizeLessThanOrEqualTo(1);
    }

    /**
     * 并发同 requestId 导出：两线程看到的内容与见证完全一致，只允许一个首次包，
     * 另一个事务在持锁后复查命令日志并重放首次包标识，结果都是 200 且仅一个包。
     */
    @Test
    void concurrentSameRequestIdExportsProduceSinglePackage() throws Exception {
        String ev = uniqueKey("EV");
        evidenceController.intake("alice",
                new IntakeRequest(uniqueKey("CMD"), ev, "CASE-1", "DOCUMENT", "SEAL-1"));
        seal("alice", ev, "content-A");
        witness("alice", ev, null);
        witness("bob", ev, null);

        String requestId = uniqueKey("REQ");
        Callable<Integer> export1 = () -> statusOf(() -> controller.export("alice",
                new ExportPackageRequest(requestId, List.of(ev))));
        Callable<Integer> export2 = () -> statusOf(() -> controller.export("alice",
                new ExportPackageRequest(requestId, List.of(ev))));

        List<Integer> statuses = runConcurrently(export1, export2);
        assertThat(statuses).containsExactly(200, 200);

        var packages = digestService.status(ev).exports();
        assertThat(packages).hasSize(1);
        // 两次 200 响应的包标识一致（重放首次包）
        var replay = digestService.export("alice",
                new ExportPackageRequest(requestId, List.of(ev)),
                hashForExport("alice", requestId, List.of(ev)));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).contains(packages.get(0).packageId());
    }

    /**
     * 并发两名不同见证人：行锁串行化，两人都成功；同一版本恰好两条见证，随后可导出。
     */
    @Test
    void concurrentTwoDifferentWitnessesBothSucceed() throws Exception {
        String ev = uniqueKey("EV");
        evidenceController.intake("alice",
                new IntakeRequest(uniqueKey("CMD"), ev, "CASE-1", "DOCUMENT", "SEAL-1"));
        seal("alice", ev, "content-A");

        Callable<Integer> w1 = () -> statusOf(() -> controller.witness("alice", ev,
                new DigestWitnessRequest(uniqueKey("CMD"), null)));
        Callable<Integer> w2 = () -> statusOf(() -> controller.witness("bob", ev,
                new DigestWitnessRequest(uniqueKey("CMD"), null)));

        List<Integer> statuses = runConcurrently(w1, w2);
        assertThat(statuses).containsExactly(200, 200);

        var statusView = digestService.status(ev);
        assertThat(statusView.state()).isEqualTo(DigestState.EXPORTABLE);
        assertThat(statusView.versions().get(0).witnesses())
                .containsExactlyInAnyOrder("alice", "bob");
    }

    /**
     * 并发同一见证人重复提交（不同 commandKey）：行锁后仅一条成功，另一条返回 422。
     */
    @Test
    void concurrentSameWitnessDuplicateRejectedOnce() throws Exception {
        String ev = uniqueKey("EV");
        evidenceController.intake("alice",
                new IntakeRequest(uniqueKey("CMD"), ev, "CASE-1", "DOCUMENT", "SEAL-1"));
        seal("alice", ev, "content-A");

        Callable<Integer> first = () -> statusOf(() -> controller.witness("alice", ev,
                new DigestWitnessRequest(uniqueKey("CMD"), null)));
        Callable<Integer> duplicate = () -> statusOf(() -> controller.witness("alice", ev,
                new DigestWitnessRequest(uniqueKey("CMD"), null)));

        List<Integer> statuses = runConcurrently(first, duplicate);
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);
        assertThat(digestService.status(ev).versions().get(0).witnesses()).hasSize(1);
    }

    @Autowired
    private IdempotencyAdvisor idempotencyAdvisor;

    private String hashForExport(String actor, String requestId, List<String> evidenceKeys) {
        return idempotencyAdvisor.hash(DigestService.OP_DIGEST_EXPORT, actor,
                "EXPORT:" + requestId, new java.util.ArrayList<>(
                        new java.util.TreeSet<>(evidenceKeys)));
    }
}
