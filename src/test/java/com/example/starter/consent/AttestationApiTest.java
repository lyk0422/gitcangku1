package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.consent.dto.AttestationRequest;
import com.example.starter.consent.dto.AttestationResponse;

/**
 * 接收方承诺 API 集成测试：在真实 H2（MODE=MySQL）上覆盖主流程、403 失败分支、
 * 到期边界、幂等、状态流转、撤回/读取并发互斥与审计保留。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttestationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConsentService consentService;

    @Autowired
    private AttestationService attestationService;

    @Autowired
    private TimeSource timeSource;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM read_audit");
        jdbc.update("DELETE FROM recipient_attestation");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
        timeSource.reset();
    }

    @AfterEach
    void resetClock() {
        timeSource.reset();
    }

    // ---------- 工具方法 ----------

    private ResultActions grant(String requestId, String subjectKey, String purpose) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/grants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s"}
                        """.formatted(requestId, subjectKey, purpose)));
    }

    private ResultActions write(String requestId, String subjectKey, String purpose,
                                String recordKey, String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","recordKey":"%s","payload":"%s"}
                        """.formatted(requestId, subjectKey, purpose, recordKey, payload)));
    }

    private ResultActions revoke(String requestId, String subjectKey, String purpose, int epoch) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d}
                        """.formatted(requestId, subjectKey, purpose, epoch)));
    }

    private ResultActions attest(String requestId, String recipient, String subject,
                                 String purpose, int epoch, Instant expiresAt) throws Exception {
        return mockMvc.perform(post("/api/v1/recipient/attestations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","recipientKey":"%s","subjectKey":"%s","purpose":"%s","epoch":%d,"expiresAt":"%s"}
                        """.formatted(requestId, recipient, subject, purpose, epoch, expiresAt.toString())));
    }

    private ResultActions recipientRead(String recipient, String subject, String purpose,
                                        int epoch, String recordKey) throws Exception {
        return mockMvc.perform(get("/api/v1/recipient/records")
                .param("recipientKey", recipient)
                .param("subjectKey", subject)
                .param("purpose", purpose)
                .param("epoch", String.valueOf(epoch))
                .param("recordKey", recordKey));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    /**
     * 构造未来到期时刻，截断到毫秒，与服务端规范化及 BIGINT 毫秒存储精度一致。
     */
    private static Instant futureExpiry() {
        return futureExpiry(3600);
    }

    private static Instant futureExpiry(long seconds) {
        return Instant.now().plusSeconds(seconds).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    private void prepareAuditGrantWithRecord() throws Exception {
        grant("g-1", "subj-a", "AUDIT").andExpect(status().isOk());
        write("w-1", "subj-a", "AUDIT", "rec-1", "audit-payload-1").andExpect(status().isOk());
    }

    // ---------- 主流程 ----------

    @Test
    void attestThenReadReturnsScopedDataAndRecordsAuditBasis() throws Exception {
        prepareAuditGrantWithRecord();
        Instant expiry = futureExpiry();
        attest("a-1", "recipient-R", "subj-a", "AUDIT", 1, expiry)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientKey").value("recipient-R"))
                .andExpect(jsonPath("$.purpose").value("AUDIT"))
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        recipientRead("recipient-R", "subj-a", "AUDIT", 1, "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("audit-payload-1"))
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.purpose").value("AUDIT"))
                .andExpect(jsonPath("$.recipientKey").value("recipient-R"))
                .andExpect(jsonPath("$.auditId").isNumber());

        // 本次读取依据已记录，授权方可查
        assertThat(count("SELECT COUNT(*) FROM read_audit WHERE recipient_key = 'recipient-R'")).isEqualTo(1);
        mockMvc.perform(get("/api/v1/consents/read-audits").param("subjectKey", "subj-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].recipientKey").value("recipient-R"))
                .andExpect(jsonPath("$[0].purpose").value("AUDIT"))
                .andExpect(jsonPath("$[0].epoch").value(1))
                .andExpect(jsonPath("$[0].recordKey").value("rec-1"))
                .andExpect(jsonPath("$[0].payload").doesNotExist());

        // 接收方可查自身承诺状态
        mockMvc.perform(get("/api/v1/recipient/attestations").param("recipientKey", "recipient-R"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subjectKey").value("subj-a"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void readOnlyReturnsDataWithinOriginalGrantScope() throws Exception {
        // 承诺有效，但记录不属于该授权代次：404 且不写审计，绝不越权返回其他代数据
        prepareAuditGrantWithRecord();
        Instant expiry = futureExpiry();
        attest("a-1", "recipient-R", "subj-a", "AUDIT", 1, expiry).andExpect(status().isOk());
        recipientRead("recipient-R", "subj-a", "AUDIT", 1, "rec-other")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
        assertThat(count("SELECT COUNT(*) FROM read_audit")).isZero();
    }

    // ---------- 403 失败分支：不泄露任何业务数据 ----------

    @Test
    void readWithoutAttestationReturns403AndNoDataLeak() throws Exception {
        prepareAuditGrantWithRecord();
        recipientRead("recipient-R", "subj-a", "AUDIT", 1, "rec-1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ATTESTATION_NOT_FOUND"))
                .andExpect(jsonPath("$.payload").doesNotExist());
        assertThat(count("SELECT COUNT(*) FROM read_audit")).isZero();
    }

    @Test
    void readWithMismatchedPurposeReturns403() throws Exception {
        prepareAuditGrantWithRecord();
        Instant expiry = futureExpiry();
        attest("a-1", "recipient-R", "subj-a", "AUDIT", 1, expiry).andExpect(status().isOk());
        // 承诺仅供审计使用，改用营销用途读取必须 403
        recipientRead("recipient-R", "subj-a", "MARKETING", 1, "rec-1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ATTESTATION_PURPOSE_MISMATCH"))
                .andExpect(jsonPath("$.payload").doesNotExist());
        assertThat(count("SELECT COUNT(*) FROM read_audit")).isZero();
    }

    @Test
    void auditReadAt1159SucceedsButAt1200OrMarketingReturns403() throws Exception {
        // 题干场景：R 承诺 epoch 4 仅供审计、12:00 到期；11:59 成功，12:00 或营销用途 403
        grant("g-4", "subj-4", "AUDIT").andExpect(status().isOk());
        for (int e = 2; e <= 4; e++) {
            revoke("r-" + (e - 1), "subj-4", "AUDIT", e - 1).andExpect(status().isOk());
            grant("g-" + (e + 3), "subj-4", "AUDIT").andExpect(status().isOk());
        }
        write("w-4", "subj-4", "AUDIT", "rec-audit", "epoch4-audit-data").andExpect(status().isOk());

        Instant noon = futureExpiry();
        attest("a-4", "recipient-R", "subj-4", "AUDIT", 4, noon)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(4));

        // 11:59（到期前一分钟）审计读取成功
        timeSource.setClock(Clock.fixed(noon.minus(Duration.ofMinutes(1)), java.time.ZoneOffset.UTC));
        recipientRead("recipient-R", "subj-4", "AUDIT", 4, "rec-audit")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("epoch4-audit-data"));

        // 12:00 到期时刻恰好到达即失效
        timeSource.setClock(Clock.fixed(noon, java.time.ZoneOffset.UTC));
        recipientRead("recipient-R", "subj-4", "AUDIT", 4, "rec-audit")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ATTESTATION_EXPIRED"))
                .andExpect(jsonPath("$.payload").doesNotExist());

        // 改用营销用途同样 403
        recipientRead("recipient-R", "subj-4", "MARKETING", 4, "rec-audit")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ATTESTATION_PURPOSE_MISMATCH"));

        // 只有 11:59 那次成功读取留下审计
        assertThat(count("SELECT COUNT(*) FROM read_audit")).isEqualTo(1);
    }

    @Test
    void readAfterRevokeReturns403AndHistoryAuditRemains() throws Exception {
        prepareAuditGrantWithRecord();
        Instant expiry = futureExpiry();
        attest("a-1", "recipient-R", "subj-a", "AUDIT", 1, expiry).andExpect(status().isOk());
        recipientRead("recipient-R", "subj-a", "AUDIT", 1, "rec-1").andExpect(status().isOk());

        revoke("r-1", "subj-a", "AUDIT", 1).andExpect(status().isOk());
        recipientRead("recipient-R", "subj-a", "AUDIT", 1, "rec-1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"))
                .andExpect(jsonPath("$.payload").doesNotExist());

        // 撤回不删除历史读取记录；接收方查到承诺状态 REVOKED
        assertThat(count("SELECT COUNT(*) FROM read_audit")).isEqualTo(1);
        mockMvc.perform(get("/api/v1/recipient/attestations").param("recipientKey", "recipient-R"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("REVOKED"));
    }

    // ---------- 幂等边界 ----------

    @Test
    void attestReplaySameRequestIdReturnsFirstVersion() throws Exception {
        prepareAuditGrantWithRecord();
        Instant expiry = futureExpiry();
        attest("a-1", "recipient-R", "subj-a", "AUDIT", 1, expiry).andExpect(status().isOk());
        attest("a-1", "recipient-R", "subj-a", "AUDIT", 1, expiry)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").value(expiry.toString()));
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'a-1'")).isEqualTo(1);
    }

    @Test
    void attestSameRequestIdWithDifferentExpiryReturns409() throws Exception {
        prepareAuditGrantWithRecord();
        attest("a-1", "recipient-R", "subj-a", "AUDIT", 1, futureExpiry())
                .andExpect(status().isOk());
        attest("a-1", "recipient-R", "subj-a", "AUDIT", 1, futureExpiry(7200))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void attestSameRequestIdWithDifferentPurposeReturns409() throws Exception {
        grant("g-1", "subj-a", "AUDIT").andExpect(status().isOk());
        grant("g-2", "subj-a", "MARKETING").andExpect(status().isOk());
        attest("a-1", "recipient-R", "subj-a", "AUDIT", 1, futureExpiry())
                .andExpect(status().isOk());
        attest("a-1", "recipient-R", "subj-a", "MARKETING", 1, futureExpiry())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void attestSameSlotWithDifferentExpiryAndNewRequestIdReturns409() throws Exception {
        prepareAuditGrantWithRecord();
        attest("a-1", "recipient-R", "subj-a", "AUDIT", 1, futureExpiry())
                .andExpect(status().isOk());
        attest("a-2", "recipient-R", "subj-a", "AUDIT", 1, futureExpiry(7200))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTESTATION_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation")).isEqualTo(1);
    }

    @Test
    void failedAttestDoesNotConsumeRequestIdAndTransactionRollsBack() throws Exception {
        // 对已撤回 epoch 提交承诺失败：不写承诺、不占用 requestId，事务整体回滚
        grant("g-1", "subj-a", "AUDIT").andExpect(status().isOk());
        revoke("r-1", "subj-a", "AUDIT", 1).andExpect(status().isOk());
        attest("a-1", "recipient-R", "subj-a", "AUDIT", 1, futureExpiry())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTESTATION_GRANT_REVOKED"));
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation")).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'a-1'")).isZero();

        // 重新授权后同一 requestId 仍可正常使用（失败未占用）
        grant("g-2", "subj-a", "AUDIT").andExpect(status().isOk());
        attest("a-1", "recipient-R", "subj-a", "AUDIT", 2, futureExpiry())
                .andExpect(status().isOk());
    }

    @Test
    void attestOnMissingEpochReturns404() throws Exception {
        attest("a-1", "recipient-R", "subj-x", "AUDIT", 9, futureExpiry())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    // ---------- 状态与重新授权边界 ----------

    @Test
    void statusTransitionsActiveExpiredRevokedAndOldEpochNotRestored() throws Exception {
        prepareAuditGrantWithRecord();
        Instant expiry = futureExpiry();
        attest("a-1", "recipient-R", "subj-a", "AUDIT", 1, expiry).andExpect(status().isOk());

        timeSource.setClock(Clock.fixed(expiry, java.time.ZoneOffset.UTC));
        mockMvc.perform(get("/api/v1/recipient/attestations").param("recipientKey", "recipient-R"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("EXPIRED"));

        revoke("r-1", "subj-a", "AUDIT", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "AUDIT").andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/recipient/attestations").param("recipientKey", "recipient-R"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].epoch").value(1))
                .andExpect(jsonPath("$[0].status").value("REVOKED"));

        // 旧 epoch 承诺不恢复：epoch 1 仍 403；新 epoch 2 没有承诺也 403
        recipientRead("recipient-R", "subj-a", "AUDIT", 1, "rec-1")
                .andExpect(status().isForbidden());
        recipientRead("recipient-R", "subj-a", "AUDIT", 2, "rec-1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ATTESTATION_NOT_FOUND"));
    }

    @Test
    void recipientSeesOnlyOwnAttestations() throws Exception {
        prepareAuditGrantWithRecord();
        attest("a-1", "recipient-R", "subj-a", "AUDIT", 1, futureExpiry())
                .andExpect(status().isOk());
        attest("a-2", "recipient-S", "subj-a", "AUDIT", 1, futureExpiry())
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/recipient/attestations").param("recipientKey", "recipient-R"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].recipientKey").doesNotExist())
                .andExpect(jsonPath("$[0].subjectKey").value("subj-a"));
    }

    // ---------- 并发边界：真实线程协调真实操作 ----------

    @Test
    void readsAfterRevokeCommittedAllFailAndNoAuditAppended() throws Exception {
        prepareAuditGrantWithRecord();
        attest("a-1", "recipient-R", "subj-a", "AUDIT", 1, futureExpiry())
                .andExpect(status().isOk());
        recipientRead("recipient-R", "subj-a", "AUDIT", 1, "rec-1").andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM read_audit")).isEqualTo(1);

        // 撤回真实提交完成
        consentService.revoke(new com.example.starter.consent.dto.RevokeRequest(
                "r-1", "subj-a", Purpose.AUDIT, 1));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    attestationService.read("recipient-R", "subj-a", Purpose.AUDIT, 1, "rec-1");
                    return true;
                } catch (ApiException ex) {
                    assertThat(ex.getStatus().value()).isEqualTo(403);
                    return false;
                }
            });
        }
        List<Future<Boolean>> futures = new ArrayList<>();
        for (Callable<Boolean> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<Boolean> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS)).isFalse();
        }
        pool.shutdown();
        // 撤回提交后的读取无一成功，审计不新增；撤回不删除历史记录
        assertThat(count("SELECT COUNT(*) FROM read_audit")).isEqualTo(1);
    }

    @Test
    void readTransactionLocksGrantRowSoRevokeCommitsOnlyAfterRead() throws Exception {
        // 真实 H2 两连接重叠：读事务先持授权行 FOR UPDATE 锁并插入审计，
        // 撤回 UPDATE 在读提交前阻塞；读提交后撤回才完成，此后新读取看到 REVOKED。
        prepareAuditGrantWithRecord();
        attest("a-1", "recipient-R", "subj-a", "AUDIT", 1, futureExpiry())
                .andExpect(status().isOk());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection reader = dataSource.getConnection();
             Connection revoker = dataSource.getConnection()) {
            reader.setAutoCommit(false);
            revoker.setAutoCommit(true);

            // 读事务：锁定授权代次行（与 AttestationService.read 相同的锁序起点）
            try (var ps = reader.prepareStatement(
                    "SELECT status FROM consent_grant"
                            + " WHERE subject_key = ? AND purpose = ? AND epoch = ? FOR UPDATE")) {
                ps.setString(1, "subj-a");
                ps.setString(2, "AUDIT");
                ps.setInt(3, 1);
                try (var rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("ACTIVE");
                }
            }

            // 撤回更新必须阻塞在读事务持有行锁期间
            Future<Integer> revokeFuture = pool.submit(() -> {
                try (var ps = revoker.prepareStatement(
                        "UPDATE consent_grant SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP"
                                + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND status = 'ACTIVE'")) {
                    ps.setString(1, "subj-a");
                    ps.setString(2, "AUDIT");
                    ps.setInt(3, 1);
                    return ps.executeUpdate();
                }
            });
            Thread.sleep(500);
            assertThat(revokeFuture.isDone()).isFalse();

            // 读事务在提交前完成授权读取依据落库
            try (var ps = reader.prepareStatement(
                    "INSERT INTO read_audit (subject_key, recipient_key, purpose, epoch, record_key, read_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, "subj-a");
                ps.setString(2, "recipient-R");
                ps.setString(3, "AUDIT");
                ps.setInt(4, 1);
                ps.setString(5, "rec-1");
                ps.setTimestamp(6, java.sql.Timestamp.from(Instant.now()));
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            reader.commit();

            // 读提交后撤回才完成且生效 1 行
            assertThat(revokeFuture.get(20, TimeUnit.SECONDS)).isEqualTo(1);
            pool.shutdown();

            // 此后的 service 读取必然 403；成功读取的审计已保留
            org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                    () -> attestationService.read("recipient-R", "subj-a", Purpose.AUDIT, 1, "rec-1"));
            assertThat(count("SELECT COUNT(*) FROM read_audit")).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE status = 'REVOKED'")).isEqualTo(1);
        }
    }

    @Test
    void concurrentAttestationsSameSlotSameExpiryProduceSingleRow() throws Exception {
        prepareAuditGrantWithRecord();
        Instant expiry = futureExpiry();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<AttestationResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "a-concurrent-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return attestationService.attest(new AttestationRequest(
                        requestId, "recipient-R", "subj-a", Purpose.AUDIT, 1, expiry));
            });
        }
        List<Future<AttestationResponse>> futures = new ArrayList<>();
        for (Callable<AttestationResponse> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<AttestationResponse> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS).expiresAt()).isEqualTo(expiry);
        }
        pool.shutdown();
        // 唯一槽位约束保证只有一条承诺
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation")).isEqualTo(1);
    }
}
