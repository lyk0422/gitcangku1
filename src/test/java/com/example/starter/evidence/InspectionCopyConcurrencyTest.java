package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.IssueCopyRequest;
import com.example.starter.evidence.dto.SealInspectionRequest;
import com.example.starter.evidence.dto.TransferInitiateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * 阅检副本并发测试：签发与交接、封条异常登记互斥；同 requestId 并发签发只落一份副本。
 * 并发协调使用真实线程与闩锁，单次等待均设超时。
 */
@SpringBootTest
class InspectionCopyConcurrencyTest {

    private static final Instant BASE = Instant.parse("2026-10-04T10:29:00Z");
    private static final LocalDateTime VALID_TO = LocalDateTime.ofInstant(
            Instant.parse("2026-10-04T10:59:00Z"), ZoneOffset.UTC);

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private InspectionCopyController copyController;

    @Autowired
    private InspectionCopyService copyService;

    @Autowired
    private EvidenceClock evidenceClock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1",
                        "d1e2f3", "synthetic-text"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private IssueCopyRequest issueRequest(String requestId, String reviewer) {
        return new IssueCopyRequest(requestId, reviewer, null, VALID_TO, null);
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

    private int copyCount(String evidenceKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inspection_copy WHERE evidence_key = ?",
                Integer.class, evidenceKey);
        return count == null ? 0 : count;
    }

    private EvidenceStatus evidenceStatus(String evidenceKey) {
        String status = jdbc.queryForObject(
                "SELECT status FROM evidence WHERE evidence_key = ?", String.class, evidenceKey);
        return EvidenceStatus.valueOf(status);
    }

    @Test
    void concurrentIssueAndTransferExactlyOneWins() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        List<Integer> statuses = runConcurrently(
                () -> copyController.issue("alice", evidenceKey,
                        issueRequest(uniqueKey("REQ"), "carol")),
                () -> evidenceController.initiateTransfer("alice", evidenceKey,
                        new TransferInitiateRequest(uniqueKey("CMD"), "bob")));

        // 签发先提交则交接被有效副本拦截（409）；交接先提交则签发遇到非 SEALED（422）
        assertThat(statuses.stream().sorted().toList())
                .isIn(List.of(201, 409), List.of(200, 422));
        if (copyCount(evidenceKey) == 1) {
            assertThat(evidenceStatus(evidenceKey)).isEqualTo(EvidenceStatus.SEALED);
        } else {
            assertThat(copyCount(evidenceKey)).isZero();
            assertThat(evidenceStatus(evidenceKey)).isEqualTo(EvidenceStatus.TRANSFER_PENDING);
        }
    }

    @Test
    void concurrentDuplicateIssueWithSameRequestIdReplaysSingleResult() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        IssueCopyRequest request = issueRequest(uniqueKey("REQ"), "carol");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<ResponseEntity<String>> call =
                    () -> copyController.issue("alice", evidenceKey, request);
            Future<ResponseEntity<String>> f1 = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return call.call();
            });
            Future<ResponseEntity<String>> f2 = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return call.call();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            ResponseEntity<String> r1 = f1.get(15, TimeUnit.SECONDS);
            ResponseEntity<String> r2 = f2.get(15, TimeUnit.SECONDS);

            assertThat(r1.getStatusCode().value()).isEqualTo(201);
            assertThat(r2.getStatusCode().value()).isEqualTo(201);
            // 并发同键签发只落一份副本；重放不泄露唯一键异常
            assertThat(copyCount(evidenceKey)).isEqualTo(1);
            String copyId1 = objectMapper.readTree(r1.getBody()).get("copyId").asText();
            String copyId2 = objectMapper.readTree(r2.getBody()).get("copyId").asText();
            assertThat(copyId1).isEqualTo(copyId2);
            Integer logCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM command_log WHERE command_key = ?",
                    Integer.class, request.requestId());
            assertThat(logCount).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentIssueAndFailedInspectionNeverLeavesReadableCopy() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String requestId = uniqueKey("REQ");

        List<Integer> statuses = runConcurrently(
                () -> copyController.issue("alice", evidenceKey, issueRequest(requestId, "carol")),
                () -> evidenceController.inspectSeal("alice", evidenceKey,
                        new SealInspectionRequest(uniqueKey("CMD"), false, "cracked")));

        // 异常登记先提交则签发 422；签发先提交则异常登记 200，但副本读取必须立即停止
        assertThat(statuses.stream().sorted().toList())
                .isIn(List.of(200, 201), List.of(200, 422));
        assertThat(evidenceStatus(evidenceKey)).isEqualTo(EvidenceStatus.SEAL_BROKEN);
        if (copyCount(evidenceKey) == 1) {
            String copyId = jdbc.queryForObject(
                    "SELECT copy_id FROM inspection_copy WHERE evidence_key = ?",
                    String.class, evidenceKey);
            try {
                copyService.read("carol", evidenceKey, copyId);
                throw new AssertionError("封条异常后副本不应可读");
            } catch (ApiException e) {
                assertThat(e.status().value()).isEqualTo(410);
            }
        }
    }
}