package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.ChunkAbandonRequest;
import com.example.starter.evidence.dto.ChunkCompleteRequest;
import com.example.starter.evidence.dto.ChunkIntakeCreateRequest;
import com.example.starter.evidence.dto.ChunkUploadRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
 * 分块接收在真实 H2 上的并发边界：补块/完成/放弃在接收行锁上串行，
 * 重叠竞争恰好一方成功；同参重放只有一份块与一条业务结果，错误不暴露底层唯一键异常。
 */
@SpringBootTest
class ChunkIntakeConcurrencyTest {

    @Autowired
    private ChunkIntakeController controller;

    @Autowired
    private ChunkIntakeService service;

    @Autowired
    private ChunkIntakeRepository intakeRepository;

    @Autowired
    private EvidenceChunkRepository chunkRepository;

    @Autowired
    private EvidenceRepository evidenceRepository;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private String create(String actor, String key, int totalLength, String sha) {
        String requestId = uniqueKey("REQ");
        ResponseEntity<String> response = controller.create(actor,
                new ChunkIntakeCreateRequest(requestId, key, "CASE-1", "DIGITAL", "SEAL-1",
                        totalLength, sha));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return requestId;
    }

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
            return List.of(f1.get(20, TimeUnit.SECONDS), f2.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentOverlappingChunksExactlyOneWins() throws Exception {
        String key = uniqueKey("CI");
        create("alice", key, 3, sha256("abc"));

        List<Integer> statuses = runConcurrently(
                () -> controller.upload("alice", key,
                        new ChunkUploadRequest(uniqueKey("REQ"), 0, b64("ab"), null)),
                () -> controller.upload("alice", key,
                        new ChunkUploadRequest(uniqueKey("REQ"), 1, b64("bc"), null)));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        // 最终数据：只有一个块、版本 1、无重复/交叠落库
        assertThat(chunkRepository.findByIntakeKey(key)).hasSize(1);
        assertThat(intakeRepository.findByKey(key).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void concurrentSameChunkSameRequestIdReplaysWithoutSecondInsert() throws Exception {
        String key = uniqueKey("CI");
        create("alice", key, 3, sha256("abc"));
        String requestId = uniqueKey("REQ");
        ChunkUploadRequest request = new ChunkUploadRequest(requestId, 0, b64("ab"), null);

        List<Integer> statuses = runConcurrently(
                () -> controller.upload("alice", key, request),
                () -> controller.upload("alice", key, request));

        assertThat(statuses).containsOnly(200);
        assertThat(chunkRepository.findByIntakeKey(key)).hasSize(1);
        assertThat(intakeRepository.findByKey(key).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void concurrentCompleteAndAbandonExactlyOneWins() throws Exception {
        String key = uniqueKey("CI");
        create("alice", key, 3, sha256("abc"));
        controller.upload("alice", key,
                new ChunkUploadRequest(uniqueKey("REQ"), 0, b64("abc"), null));

        List<Integer> statuses = runConcurrently(
                () -> controller.complete("alice", key,
                        new ChunkCompleteRequest(uniqueKey("REQ"), null)),
                () -> controller.abandon("alice", key,
                        new ChunkAbandonRequest(uniqueKey("REQ"), "race")));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        ChunkIntake intake = intakeRepository.findByKey(key).orElseThrow();
        if (intake.status() == ChunkIntakeStatus.SEALED) {
            // 完成先提交：证物必须存在且 SEALED，放弃方得到 409
            assertThat(evidenceRepository.findByKey(key).orElseThrow().status())
                    .isEqualTo(EvidenceStatus.SEALED);
        } else {
            // 放弃先提交：不得产生证物，完成方得到 409
            assertThat(intake.status()).isEqualTo(ChunkIntakeStatus.ABANDONED);
            assertThat(evidenceRepository.findByKey(key)).isEmpty();
        }
    }

    @Test
    void concurrentDuplicateCreateSameRequestIdReplaysSingleRecord() throws Exception {
        String key = uniqueKey("CI");
        String requestId = uniqueKey("REQ");
        ChunkIntakeCreateRequest request = new ChunkIntakeCreateRequest(
                requestId, key, "CASE-1", "DIGITAL", "SEAL-1", 3, sha256("abc"));

        List<Integer> statuses = runConcurrently(
                () -> controller.create("alice", request),
                () -> controller.create("alice", request));

        assertThat(statuses).containsOnly(201);
        assertThat(intakeRepository.findByKey(key)).isPresent();
        assertThat(evidenceRepository.findByKey(key)).isEmpty();
    }
}
