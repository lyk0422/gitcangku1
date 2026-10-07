package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.ChunkAppendRequest;
import com.example.starter.evidence.dto.ChunkCommandRequest;
import com.example.starter.evidence.dto.ChunkIntakeCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.security.MessageDigest;
import java.util.HexFormat;
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
 * 分块入库并发边界测试（真实 H2 库）：补块、完成、放弃串行校验；
 * 并发重复提交不暴露唯一键错误，重放首次结果，最终业务状态一致。
 */
@SpringBootTest
class ChunkIntakeConcurrencyTest {

    @Autowired
    private ChunkIntakeController controller;

    @Autowired
    private ChunkIntakeService chunkIntakeService;

    @Autowired
    private EvidenceService evidenceService;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String createIntake(String actor, String evidenceKey, int totalBytes, String sha256) {
        String intakeKey = uniqueKey("IN");
        ResponseEntity<String> response = controller.create(actor,
                new ChunkIntakeCreateRequest(uniqueKey("CMD"), intakeKey, evidenceKey,
                        "CASE-1", "DIGITAL", "SEAL-1", totalBytes, sha256));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return intakeKey;
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
    void concurrentAppendsSerializeOnExpectedVersion() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String intakeKey = createIntake("alice", evidenceKey, 3, sha256Hex("abc"));

        // 两个不同块同时以期望版本 0 提交：恰好一个成功，另一个因版本推进得到 409
        List<Integer> statuses = runConcurrently(
                () -> controller.appendChunk("alice", intakeKey,
                        new ChunkAppendRequest(uniqueKey("CMD"), 2, "Yw==", 0)),
                () -> controller.appendChunk("alice", intakeKey,
                        new ChunkAppendRequest(uniqueKey("CMD"), 0, "YWI=", 0)));
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);

        // 失败方按当前版本重试后两块齐备，可完成接收
        int loserOffset = statuses.get(0) == 409 ? 2 : 0;
        String loserData = loserOffset == 2 ? "Yw==" : "YWI=";
        ResponseEntity<String> retry = controller.appendChunk("alice", intakeKey,
                new ChunkAppendRequest(uniqueKey("CMD"), loserOffset, loserData, 1));
        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        ResponseEntity<String> completed = controller.complete("alice", intakeKey,
                new ChunkCommandRequest(uniqueKey("CMD"), 2));
        assertThat(completed.getStatusCode().value()).isEqualTo(201);

        var manifest = chunkIntakeService.manifest(intakeKey);
        assertThat(manifest.chunks()).hasSize(2);
        assertThat(manifest.intake().status()).isEqualTo("COMPLETED");
        assertThat(chunkIntakeService.readBytes("alice", evidenceKey).dataBase64())
                .isEqualTo("YWJj");
    }

    @Test
    void concurrentCompleteAndAbandonExactlyOneWins() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String intakeKey = createIntake("alice", evidenceKey, 3, sha256Hex("abc"));
        controller.appendChunk("alice", intakeKey,
                new ChunkAppendRequest(uniqueKey("CMD"), 0, "YWJj", 0));

        List<Integer> statuses = runConcurrently(
                () -> controller.complete("alice", intakeKey,
                        new ChunkCommandRequest(uniqueKey("CMD"), 1)),
                () -> controller.abandon("alice", intakeKey,
                        new ChunkCommandRequest(uniqueKey("CMD"), 1)));

        // 完成（201）与放弃（200）恰好一个成功，另一个因记录已终结得到 409
        assertThat(statuses).contains(409);
        assertThat(statuses.stream().filter(s -> s == 200 || s == 201).count()).isEqualTo(1);
        var manifest = chunkIntakeService.manifest(intakeKey);
        if (manifest.intake().status().equals("COMPLETED")) {
            // 完成先提交：证物已生成且为 SEALED
            assertThat(evidenceService.custodyChain(evidenceKey).evidence().status())
                    .isEqualTo(EvidenceStatus.SEALED);
        } else {
            // 放弃先提交：不生成证物
            assertThat(manifest.intake().status()).isEqualTo("ABANDONED");
            try {
                evidenceService.custodyChain(evidenceKey);
                throw new AssertionError("放弃后不应生成证物");
            } catch (ApiException e) {
                assertThat(e.status().value()).isEqualTo(404);
            }
        }
    }

    @Test
    void concurrentDuplicateCreateWithSameCommandKeyReplaysSingleResult() throws Exception {
        String intakeKey = uniqueKey("IN");
        String commandKey = uniqueKey("CMD");
        ChunkIntakeCreateRequest request = new ChunkIntakeCreateRequest(commandKey, intakeKey,
                uniqueKey("EV"), "CASE-1", "DIGITAL", "SEAL-1", 3, sha256Hex("abc"));

        List<Integer> statuses = runConcurrently(
                () -> controller.create("alice", request),
                () -> controller.create("alice", request));

        // 竞争不暴露唯一键错误：双方均拿到首次结果
        assertThat(statuses).containsOnly(201);
        var manifest = chunkIntakeService.manifest(intakeKey);
        assertThat(manifest.intake().status()).isEqualTo("RECEIVING");
        assertThat(manifest.intake().creatorId()).isEqualTo("alice");
    }

    @Test
    void concurrentCompletesWithSameCommandKeyCreateSingleEvidence() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String intakeKey = createIntake("alice", evidenceKey, 3, sha256Hex("abc"));
        controller.appendChunk("alice", intakeKey,
                new ChunkAppendRequest(uniqueKey("CMD"), 0, "YWJj", 0));
        String commandKey = uniqueKey("CMD");
        ChunkCommandRequest complete = new ChunkCommandRequest(commandKey, 1);

        List<Integer> statuses = runConcurrently(
                () -> controller.complete("alice", intakeKey, complete),
                () -> controller.complete("alice", intakeKey, complete));

        assertThat(statuses).containsOnly(201);
        var manifest = chunkIntakeService.manifest(intakeKey);
        assertThat(manifest.intake().status()).isEqualTo("COMPLETED");
        // 只生成一件证物，保管人为入库人
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.evidence().custodianId()).isEqualTo("alice");
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
    }
}
