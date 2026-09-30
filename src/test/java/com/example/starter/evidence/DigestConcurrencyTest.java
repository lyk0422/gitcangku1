package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.ExportPackageRequest;
import com.example.starter.evidence.dto.ExportPackageView;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.RegisterContentRequest;
import com.example.starter.evidence.dto.SealContentRequest;
import com.example.starter.evidence.dto.TransferInitiateRequest;
import com.example.starter.evidence.dto.WitnessDigestRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
 * 数字摘要见证并发边界测试（真实 H2 内存库）：
 * 并发重新封存与导出必须按提交时的完整版本裁决，不得混入不同版本内容；
 * 同一 requestId 并发导出只生成一个包；并发见证满足唯一约束与双人要求。
 */
@SpringBootTest
class DigestConcurrencyTest {

    @Autowired
    private DigestController controller;

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private DigestService digestService;

    @Autowired
    private JdbcTemplate jdbc;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private void transfer(String from, String evidenceKey, String to) {
        assertThat(evidenceController.initiateTransfer(from, evidenceKey,
                new TransferInitiateRequest(uniqueKey("CMD"), to)).getStatusCode().value())
                .isEqualTo(200);
        assertThat(evidenceController.acceptTransfer(to, evidenceKey,
                new CommandRequest(uniqueKey("CMD"))).getStatusCode().value()).isEqualTo(200);
    }

    private void register(String actor, String evidenceKey, String content) {
        String base64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        assertThat(controller.registerContent(actor, evidenceKey,
                new RegisterContentRequest(uniqueKey("CMD"), base64)).getStatusCode().value())
                .isEqualTo(200);
    }

    private int seal(String actor, String evidenceKey) {
        return controller.sealContent(actor, evidenceKey,
                new SealContentRequest(uniqueKey("CMD"))).getStatusCode().value();
    }

    private int witness(String actor, String evidenceKey, int versionNo) {
        return controller.witnessDigest(actor, evidenceKey,
                new WitnessDigestRequest(uniqueKey("CMD"), versionNo)).getStatusCode().value();
    }

    /**
     * 构造两件已可导出的证物（当前版本 2，alice 与 bob 各见证一次），保管人为 bob。
     */
    private List<String> prepareTwoExportable(String contentA, String contentB) {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        for (String key : new String[]{ev1, ev2}) {
            String content = key.equals(ev1) ? contentA : contentB;
            intake("alice", key);
            register("alice", key, content);
            assertThat(seal("alice", key)).isEqualTo(200);
            transfer("alice", key, "bob");
            assertThat(seal("bob", key)).isEqualTo(200);
            assertThat(witness("alice", key, 2)).isEqualTo(200);
            assertThat(witness("bob", key, 2)).isEqualTo(200);
            assertThat(digestService.digestView(key).digestState()).isEqualTo("EXPORTABLE");
        }
        return List.of(ev1, ev2);
    }

    /**
     * 并发调用结果：HTTP 状态码；导出成功时附带包标识。
     */
    private record CallOutcome(int status, String packageKey) {
    }

    private static final class LatchCall implements Callable<CallOutcome> {
        private final CountDownLatch ready;
        private final CountDownLatch start;
        private final Callable<CallOutcome> call;

        private LatchCall(CountDownLatch ready, CountDownLatch start, Callable<CallOutcome> call) {
            this.ready = ready;
            this.start = start;
            this.call = call;
        }

        @Override
        public CallOutcome call() throws Exception {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return call.call();
        }
    }

    private List<CallOutcome> runConcurrently(Callable<CallOutcome> first,
                                               Callable<CallOutcome> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<CallOutcome> f1 = executor.submit(new LatchCall(ready, start, first));
            Future<CallOutcome> f2 = executor.submit(new LatchCall(ready, start, second));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private CallOutcome exportOutcome(String requestId, List<String> keys) {
        try {
            ExportPackageView view = controller.exportPackage("bob",
                    new ExportPackageRequest(requestId, keys));
            return new CallOutcome(200, view.packageKey());
        } catch (ApiException e) {
            return new CallOutcome(e.status().value(), null);
        }
    }

    private CallOutcome sealOutcome(String actor, String evidenceKey) {
        try {
            int status = controller.sealContent(actor, evidenceKey,
                    new SealContentRequest(uniqueKey("CMD"))).getStatusCode().value();
            return new CallOutcome(status, null);
        } catch (ApiException e) {
            return new CallOutcome(e.status().value(), null);
        }
    }

    @Test
    void concurrentResealAndExportAdjudicatesByCommittedVersionWithoutMixing() throws Exception {
        List<String> keys = prepareTwoExportable("doc-A", "doc-B");
        String ev1 = keys.get(0);
        String ev2 = keys.get(1);
        String requestId = uniqueKey("REQ");

        List<CallOutcome> outcomes = runConcurrently(
                () -> sealOutcome("bob", ev1),
                () -> exportOutcome(requestId, List.of(ev1, ev2)));

        CallOutcome sealResult = outcomes.get(0);
        CallOutcome exportResult = outcomes.get(1);
        assertThat(sealResult.status()).isEqualTo(200);
        // 行锁串行裁决：导出先提交则基于版本 2 成功；重封存先提交则版本 3 无见证导出 422。
        assertThat(exportResult.status()).isIn(200, 422);

        Integer packageCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM export_package WHERE request_id = ?", Integer.class, requestId);
        if (exportResult.status() == 200) {
            assertThat(packageCount).isEqualTo(1);
            // 成功包必须完整基于提交时的版本 2，绝不能混入 ev1 的新版本 3
            List<Integer> versions = jdbc.queryForList("""
                    SELECT i.version_no FROM export_package_item i
                    JOIN export_package p ON p.id = i.package_id
                    WHERE p.request_id = ? ORDER BY i.evidence_key
                    """, Integer.class, requestId);
            assertThat(versions).containsExactly(2, 2);
            // ev1 版本 3 在导出生成之后才存在，未被包入
            assertThat(digestService.digestView(ev1).versionNo()).isEqualTo(3);
        } else {
            assertThat(packageCount).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM export_package p JOIN export_package_item i ON i.package_id = p.id "
                            + "WHERE p.request_id = ?", Integer.class, requestId)).isZero();
        }
    }

    @Test
    void concurrentExportWithSameRequestIdProducesExactlyOnePackage() throws Exception {
        List<String> keys = prepareTwoExportable("doc-A", "doc-B");
        String requestId = uniqueKey("REQ");

        List<CallOutcome> outcomes = runConcurrently(
                () -> exportOutcome(requestId, List.of(keys.get(0), keys.get(1))),
                () -> exportOutcome(requestId, List.of(keys.get(1), keys.get(0))));

        assertThat(outcomes).extracting(CallOutcome::status).containsOnly(200);
        assertThat(outcomes.get(0).packageKey()).isEqualTo(outcomes.get(1).packageKey());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM export_package WHERE request_id = ?", Integer.class, requestId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM export_package_item i
                JOIN export_package p ON p.id = i.package_id WHERE p.request_id = ?
                """, Integer.class, requestId)).isEqualTo(2);
    }

    @Test
    void concurrentTwoDistinctWitnessesBothSucceed() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        register("alice", evidenceKey, "doc-A");
        seal("alice", evidenceKey);
        transfer("alice", evidenceKey, "bob");
        seal("bob", evidenceKey);

        List<CallOutcome> outcomes = runConcurrently(
                () -> {
                    try {
                        return new CallOutcome(witness("alice", evidenceKey, 2), null);
                    } catch (ApiException e) {
                        return new CallOutcome(e.status().value(), null);
                    }
                },
                () -> {
                    try {
                        return new CallOutcome(witness("bob", evidenceKey, 2), null);
                    } catch (ApiException e) {
                        return new CallOutcome(e.status().value(), null);
                    }
                });

        assertThat(outcomes).extracting(CallOutcome::status).containsOnly(200);
        Integer witnessCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM digest_witness WHERE evidence_key = ? AND version_no = 2",
                Integer.class, evidenceKey);
        assertThat(witnessCount).isEqualTo(2);
        assertThat(digestService.digestView(evidenceKey).digestState()).isEqualTo("EXPORTABLE");
    }

    @Test
    void concurrentDuplicateWitnessBySameCustodianSucceedsOnce() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        register("alice", evidenceKey, "doc-A");
        seal("alice", evidenceKey);
        transfer("alice", evidenceKey, "bob");
        seal("bob", evidenceKey);
        assertThat(witness("alice", evidenceKey, 2)).isEqualTo(200);

        List<CallOutcome> outcomes = runConcurrently(
                () -> {
                    try {
                        return new CallOutcome(witness("bob", evidenceKey, 2), null);
                    } catch (ApiException e) {
                        return new CallOutcome(e.status().value(), null);
                    }
                },
                () -> {
                    try {
                        return new CallOutcome(witness("bob", evidenceKey, 2), null);
                    } catch (ApiException e) {
                        return new CallOutcome(e.status().value(), null);
                    }
                });

        assertThat(outcomes).extracting(CallOutcome::status).containsExactlyInAnyOrder(200, 409);
        Integer witnessCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM digest_witness WHERE evidence_key = ? AND version_no = 2 AND witness_id = 'bob'",
                Integer.class, evidenceKey);
        assertThat(witnessCount).isEqualTo(1);
        // 两名不同见证齐备后仍可导出
        assertThat(digestService.digestView(evidenceKey).digestState()).isEqualTo("EXPORTABLE");
    }
}
