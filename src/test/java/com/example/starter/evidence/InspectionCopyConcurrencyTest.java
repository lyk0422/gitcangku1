package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.InspectionCopyIssueRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.TransferInitiateRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

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
 * 阅检副本并发测试：签发与交接互斥判定、同键并发签发重放、回收与交接竞争，
 * 均按事务提交顺序生效，不允许检查之后插入漏管副本或泄露唯一键异常。
 */
@SpringBootTest
class InspectionCopyConcurrencyTest {

    private static final Instant BASE = Instant.parse("2026-10-04T10:00:00Z");

    @Autowired
    private EvidenceController controller;

    @Autowired
    private EvidenceService evidenceService;

    @Autowired
    private InspectionCopyService copyService;

    @Autowired
    private EvidenceClock evidenceClock;

    @BeforeEach
    void fixClock() {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
    }

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private LocalDateTime validUntil() {
        return LocalDateTime.ofInstant(BASE.plusSeconds(1800), ZoneOffset.UTC);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = controller.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1",
                        "sha256:evidence-digest", "合成文本-证物"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private InspectionCopyIssueRequest issueRequest(String commandKey, String copyKey,
                                                    String reviewer) {
        return new InspectionCopyIssueRequest(commandKey, copyKey, reviewer, validUntil());
    }

    /**
     * 记录一次并发调用的 HTTP 状态码（业务异常取其携带状态码）。
     */
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
    void concurrentIssueAndTransferExactlyOneWins() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String copyKey = uniqueKey("COPY");
        intake("alice", evidenceKey);

        List<Integer> statuses = runConcurrently(
                () -> controller.issueCopy("alice", evidenceKey,
                        issueRequest(uniqueKey("CMD"), copyKey, "reviewer1")),
                () -> controller.initiateTransfer("alice", evidenceKey,
                        new TransferInitiateRequest(uniqueKey("CMD"), "bob")));

        // 签发先提交则交接 409；交接先提交则签发 422，二者恰一成功
        assertThat(statuses).containsExactlyInAnyOrder(
                statuses.get(0) == 201 ? 201 : 422,
                statuses.get(0) == 201 ? 409 : 200);

        var chain = evidenceService.custodyChain(evidenceKey);
        if (statuses.get(0) == 201) {
            // 签发先提交：证物仍 SEALED，交接被拒绝，副本有效可读
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(chain.transfers()).isEmpty();
            var content = copyService.read("reviewer1", evidenceKey, copyKey);
            assertThat(content.digest()).isEqualTo("sha256:evidence-digest");
        } else {
            // 交接先提交：证物进入待接收，签发被拒绝，不存在漏管副本
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.TRANSFER_PENDING);
            assertThat(chain.transfers()).hasSize(1);
            try {
                copyService.read("reviewer1", evidenceKey, copyKey);
                throw new AssertionError("签发被拒绝后不应存在可读副本");
            } catch (ApiException e) {
                assertThat(e.status().value()).isEqualTo(404);
            }
        }
    }

    @Test
    void concurrentDuplicateIssueWithSameCommandKeyReplaysSingleResult() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String copyKey = uniqueKey("COPY");
        String commandKey = uniqueKey("CMD");
        intake("alice", evidenceKey);
        InspectionCopyIssueRequest request = issueRequest(commandKey, copyKey, "reviewer1");

        List<Integer> statuses = runConcurrently(
                () -> controller.issueCopy("alice", evidenceKey, request),
                () -> controller.issueCopy("alice", evidenceKey, request));

        // 并发同键同参：均返回 201，唯一键异常不外泄，副本只有一份
        assertThat(statuses).containsOnly(201);
        var content = copyService.read("reviewer1", evidenceKey, copyKey);
        assertThat(content.reviewerId()).isEqualTo("reviewer1");
    }

    @Test
    void concurrentRevokeAndTransferExactlyOneWins() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String copyKey = uniqueKey("COPY");
        intake("alice", evidenceKey);
        controller.issueCopy("alice", evidenceKey,
                issueRequest(uniqueKey("CMD"), copyKey, "reviewer1"));

        List<Integer> statuses = runConcurrently(
                () -> controller.revokeCopy("alice", evidenceKey, copyKey,
                        new CommandRequest(uniqueKey("CMD"))),
                () -> controller.initiateTransfer("alice", evidenceKey,
                        new TransferInitiateRequest(uniqueKey("CMD"), "bob")));

        // 回收总是成功；交接在回收先提交时成功、在交接先判定时 409
        assertThat(statuses.get(0)).isEqualTo(200);
        assertThat(statuses.get(1)).isIn(200, 409);

        var chain = evidenceService.custodyChain(evidenceKey);
        if (statuses.get(1) == 200) {
            // 回收先提交：交接成立，证物进入待接收
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.TRANSFER_PENDING);
            assertThat(chain.transfers()).hasSize(1);
        } else {
            // 交接先判定：副本仍有效时交接被 409，随后回收生效，证物仍 SEALED
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(chain.transfers()).isEmpty();
        }
        // 回收已生效：读取返回 410
        try {
            copyService.read("reviewer1", evidenceKey, copyKey);
            throw new AssertionError("回收后不应可读");
        } catch (ApiException e) {
            assertThat(e.status().value()).isEqualTo(410);
        }
    }
}