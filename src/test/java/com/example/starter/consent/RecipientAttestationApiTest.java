package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 接收方承诺 API 真实 H2 数据库集成测试：覆盖主流程、403 失败分支、精确到期、
 * 状态终态、撤回/读取并发边界与 requestId 幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecipientAttestationApiTest {

    private static final String SUBJECT = "demo-subject";
    private static final String RECIPIENT = "recipient-R";
    private static final Instant T1159 = Instant.parse("2026-09-30T11:59:00Z");
    private static final Instant T1200 = Instant.parse("2026-09-30T12:00:00Z");
    private static final Instant T1300 = Instant.parse("2026-09-30T13:00:00Z");

    /**
     * 测试专用配置：用可控时钟替换系统时钟，并用可在撤回 UPDATE 处暂停的仓储协调并发。
     */
    @TestConfiguration
    static class TestClockConfig {

        @Bean
        @Primary
        MutableTimeSource mutableTimeSource() {
            return new MutableTimeSource(T1159);
        }

        @Bean
        @Primary
        BlockingAttestationRepository blockingAttestationRepository(JdbcTemplate jdbc) {
            return new BlockingAttestationRepository(jdbc);
        }
    }

    /**
     * 在撤回 UPDATE 处可暂停的承诺仓储：暂停点位于事务开启之后、UPDATE 提交之前。
     * 闩锁使用静态字段：Spring 会为仓储创建 CGLIB 代理，静态状态在代理与目标实例间天然共享。
     */
    static class BlockingAttestationRepository extends AttestationRepository {

        private static volatile CountDownLatch revokeEntered = new CountDownLatch(0);
        private static volatile CountDownLatch allowRevoke = new CountDownLatch(0);

        BlockingAttestationRepository(JdbcTemplate jdbc) {
            super(jdbc);
        }

        static void arm() {
            revokeEntered = new CountDownLatch(1);
            allowRevoke = new CountDownLatch(1);
        }

        static CountDownLatch entered() {
            return revokeEntered;
        }

        static void release() {
            allowRevoke.countDown();
        }

        @Override
        boolean revoke(String recipientKey, String subjectKey, Purpose purpose, int epoch, Instant now) {
            revokeEntered.countDown();
            try {
                org.junit.jupiter.api.Assertions.assertTrue(allowRevoke.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return super.revoke(recipientKey, subjectKey, purpose, epoch, now);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableTimeSource clock;

    @Autowired
    private AttestationService attestationService;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM recipient_read_audit");
        jdbc.update("DELETE FROM recipient_attestation");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
        clock.setTime(T1159);
    }

    // ---------- HTTP 辅助 ----------

    private ResultActions grant(String requestId, String subjectKey, String purpose) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/grants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s"}
                        """.formatted(requestId, subjectKey, purpose)));
    }

    private ResultActions revokeGrant(String requestId, String subjectKey, String purpose, int epoch) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d}
                        """.formatted(requestId, subjectKey, purpose, epoch)));
    }

    private ResultActions write(String requestId, String subjectKey, String purpose,
                                String recordKey, String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","recordKey":"%s","payload":"%s"}
                        """.formatted(requestId, subjectKey, purpose, recordKey, payload)));
    }

    private ResultActions attest(String requestId, String recipient, String subject,
                                 String purpose, int epoch, String expiresAt) throws Exception {
        return mockMvc.perform(post("/api/v1/recipient/attestations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","recipientKey":"%s","subjectKey":"%s","purpose":"%s","epoch":%d,"expiresAt":"%s"}
                        """.formatted(requestId, recipient, subject, purpose, epoch, expiresAt)));
    }

    private ResultActions revokeAttestation(String requestId, String recipient, String subject,
                                            String purpose, int epoch) throws Exception {
        return mockMvc.perform(post("/api/v1/recipient/attestations/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","recipientKey":"%s","subjectKey":"%s","purpose":"%s","epoch":%d}
                        """.formatted(requestId, recipient, subject, purpose, epoch)));
    }

    private ResultActions recipientRead(String recipient, String subject,
                                        String purpose, String recordKey) throws Exception {
        return mockMvc.perform(get("/api/v1/recipient/records")
                .param("recipientKey", recipient)
                .param("subjectKey", subject)
                .param("purpose", purpose)
                .param("recordKey", recordKey));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    /**
     * 构造题干场景：AUDIT 用途前 3 代已撤回、第 4 代有效，并在第 4 代写一条记录。
     */
    private void setupEpoch4WithRecord() throws Exception {
        for (int epoch = 1; epoch <= 3; epoch++) {
            grant("g-" + epoch, SUBJECT, "AUDIT").andExpect(status().isOk());
            revokeGrant("r-" + epoch, SUBJECT, "AUDIT", epoch).andExpect(status().isOk());
        }
        grant("g-4", SUBJECT, "AUDIT").andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(4))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        write("w-1", SUBJECT, "AUDIT", "rec-1", "audit-only-payload-epoch-4")
                .andExpect(status().isOk());
    }

    // ---------- 题干主场景 ----------

    @Test
    void scenarioRAttestsEpoch4ForAuditExpiringAtNoon() throws Exception {
        setupEpoch4WithRecord();

        attest("a-1", RECIPIENT, SUBJECT, "AUDIT", 4, "2026-09-30T12:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expiresAt").value("2026-09-30T12:00:00Z"));

        // 11:59 审计读取成功，只返回授权范围内数据
        clock.setTime(T1159);
        recipientRead(RECIPIENT, SUBJECT, "AUDIT", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(4))
                .andExpect(jsonPath("$.payload").value("audit-only-payload-epoch-4"));

        // 12:00 到期时刻恰好到达：403，不泄露业务数据
        clock.setTime(T1200);
        recipientRead(RECIPIENT, SUBJECT, "AUDIT", "rec-1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ATTESTATION_EXPIRED"));

        // 改用营销用途读取：403 用途不符
        clock.setTime(T1159);
        recipientRead(RECIPIENT, SUBJECT, "MARKETING", "rec-1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PURPOSE_MISMATCH"));
    }

    @Test
    void successfulReadRecordsBasisAndSubjectCanQueryAuditTrail() throws Exception {
        setupEpoch4WithRecord();
        attest("a-1", RECIPIENT, SUBJECT, "AUDIT", 4, "2026-09-30T12:00:00Z").andExpect(status().isOk());

        recipientRead(RECIPIENT, SUBJECT, "AUDIT", "rec-1").andExpect(status().isOk());

        // 授权方可查读取审计：成功依据包含代次、用途与承诺到期时刻
        mockMvc.perform(get("/api/v1/consents/read-audits").param("subjectKey", SUBJECT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].result").value("SUCCESS"))
                .andExpect(jsonPath("$[0].recipientKey").value(RECIPIENT))
                .andExpect(jsonPath("$[0].purpose").value("AUDIT"))
                .andExpect(jsonPath("$[0].epoch").value(4))
                .andExpect(jsonPath("$[0].recordKey").value("rec-1"))
                .andExpect(jsonPath("$[0].denyReason").doesNotExist())
                .andExpect(jsonPath("$[0].attestationExpiresAt").value("2026-09-30T12:00:00Z"));
        assertThat(count("SELECT COUNT(*) FROM recipient_read_audit WHERE result = 'SUCCESS'")).isEqualTo(1);
    }

    // ---------- 403 失败分支 ----------

    @Test
    void readWithoutAttestationReturns403AndAuditsDenial() throws Exception {
        setupEpoch4WithRecord();

        recipientRead("recipient-other", SUBJECT, "AUDIT", "rec-1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_ATTESTATION"));

        // 拒绝响应不含任何业务数据，审计仅记录键与原因（无 payload 列）
        Integer denied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recipient_read_audit WHERE result = 'DENIED' AND deny_reason = 'NO_ATTESTATION'",
                Integer.class);
        assertThat(denied).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM recipient_read_audit WHERE attestation_expires_at IS NULL"
                + " AND record_key = 'rec-1'")).isEqualTo(1);
    }

    @Test
    void deniedReadDoesNotLeakPayload() throws Exception {
        setupEpoch4WithRecord();
        // 无承诺读取：响应体只有 code/message，不包含 payload
        String body = recipientRead(RECIPIENT, SUBJECT, "AUDIT", "rec-1")
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("audit-only-payload-epoch-4");
    }

    @Test
    void revokedAttestationDeniesReadButKeepsHistory() throws Exception {
        setupEpoch4WithRecord();
        attest("a-1", RECIPIENT, SUBJECT, "AUDIT", 4, "2026-09-30T12:00:00Z").andExpect(status().isOk());
        recipientRead(RECIPIENT, SUBJECT, "AUDIT", "rec-1").andExpect(status().isOk());
        revokeAttestation("ar-1", RECIPIENT, SUBJECT, "AUDIT", 4)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        recipientRead(RECIPIENT, SUBJECT, "AUDIT", "rec-1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ATTESTATION_REVOKED"));

        // 撤回不删除历史读取记录
        assertThat(count("SELECT COUNT(*) FROM recipient_read_audit WHERE result = 'SUCCESS'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM recipient_read_audit"
                + " WHERE result = 'DENIED' AND deny_reason = 'ATTESTATION_REVOKED'")).isEqualTo(1);
        // 撤回不删除承诺本身
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation WHERE status = 'REVOKED'")).isEqualTo(1);
    }

    @Test
    void oldEpochAttestationIsNotRestoredAfterRegrant() throws Exception {
        setupEpoch4WithRecord();
        attest("a-1", RECIPIENT, SUBJECT, "AUDIT", 4, "2026-09-30T12:00:00Z").andExpect(status().isOk());
        revokeAttestation("ar-1", RECIPIENT, SUBJECT, "AUDIT", 4).andExpect(status().isOk());

        // 授权再次撤回并重授到 epoch 5
        revokeGrant("r-4", SUBJECT, "AUDIT", 4).andExpect(status().isOk());
        grant("g-5", SUBJECT, "AUDIT").andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(5));

        // 旧 epoch 4 的承诺保持已撤回终态，不会恢复
        mockMvc.perform(get("/api/v1/recipient/attestations").param("recipientKey", RECIPIENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].epoch").value(4))
                .andExpect(jsonPath("$[0].status").value("REVOKED"));
        // 当前 epoch 5 无承诺，读取 403（旧 epoch 4 承诺不匹配当前代次）
        clock.setTime(T1159);
        recipientRead(RECIPIENT, SUBJECT, "AUDIT", "rec-1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_ATTESTATION"));
        // 在已撤回的 epoch 4 上重新承诺也被拒绝（授权代次已撤回，终态不恢复）
        attest("a-2", RECIPIENT, SUBJECT, "AUDIT", 4, "2026-09-30T13:00:00Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GRANT_ALREADY_REVOKED"));
    }

    @Test
    void readMatchingOnlyCurrentEpoch() throws Exception {
        // 独立主体：承诺 epoch 1，授权推进到 epoch 2 后旧承诺不匹配当前代次
        grant("hg-1", "subj-h", "AUDIT").andExpect(status().isOk());
        attest("ha-1", RECIPIENT, "subj-h", "AUDIT", 1, "2026-09-30T12:00:00Z").andExpect(status().isOk());
        write("hw-1", "subj-h", "AUDIT", "rec-1", "epoch1-payload").andExpect(status().isOk());
        revokeGrant("hr-1", "subj-h", "AUDIT", 1).andExpect(status().isOk());
        grant("hg-2", "subj-h", "AUDIT").andExpect(status().isOk());
        write("hw-2", "subj-h", "AUDIT", "rec-1", "epoch2-payload").andExpect(status().isOk());

        // 旧 epoch 承诺不匹配当前 epoch，读不到任何代次的数据
        recipientRead(RECIPIENT, "subj-h", "AUDIT", "rec-1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_ATTESTATION"));
        String body = recipientRead(RECIPIENT, "subj-h", "AUDIT", "rec-1")
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("epoch1-payload").doesNotContain("epoch2-payload");
    }

    // ---------- 幂等边界 ----------

    @Test
    void attestReplayReturnsFirstVersion() throws Exception {
        setupEpoch4WithRecord();
        attest("a-1", RECIPIENT, SUBJECT, "AUDIT", 4, "2026-09-30T12:00:00Z").andExpect(status().isOk());
        attest("a-1", RECIPIENT, SUBJECT, "AUDIT", 4, "2026-09-30T12:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(4))
                .andExpect(jsonPath("$.expiresAt").value("2026-09-30T12:00:00Z"));
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'a-1'")).isEqualTo(1);
    }

    @Test
    void attestSameRequestIdDifferentExpiryReturns409() throws Exception {
        setupEpoch4WithRecord();
        attest("a-1", RECIPIENT, SUBJECT, "AUDIT", 4, "2026-09-30T12:00:00Z").andExpect(status().isOk());
        attest("a-1", RECIPIENT, SUBJECT, "AUDIT", 4, "2026-09-30T18:00:00Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void attestSameRequestIdDifferentPurposeReturns409() throws Exception {
        setupEpoch4WithRecord();
        attest("a-1", RECIPIENT, SUBJECT, "AUDIT", 4, "2026-09-30T12:00:00Z").andExpect(status().isOk());
        attest("a-1", RECIPIENT, SUBJECT, "MARKETING", 4, "2026-09-30T12:00:00Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        // 原承诺仍是 AUDIT，未被改动
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation WHERE purpose = 'AUDIT'")).isEqualTo(1);
    }

    @Test
    void revokeAttestationReplayIsIdempotent() throws Exception {
        setupEpoch4WithRecord();
        attest("a-1", RECIPIENT, SUBJECT, "AUDIT", 4, "2026-09-30T12:00:00Z").andExpect(status().isOk());
        revokeAttestation("ar-1", RECIPIENT, SUBJECT, "AUDIT", 4).andExpect(status().isOk());
        revokeAttestation("ar-1", RECIPIENT, SUBJECT, "AUDIT", 4)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'ar-1'")).isEqualTo(1);
    }

    @Test
    void revokeAttestationTwiceWithNewRequestIdReturns409() throws Exception {
        setupEpoch4WithRecord();
        attest("a-1", RECIPIENT, SUBJECT, "AUDIT", 4, "2026-09-30T12:00:00Z").andExpect(status().isOk());
        revokeAttestation("ar-1", RECIPIENT, SUBJECT, "AUDIT", 4).andExpect(status().isOk());
        revokeAttestation("ar-2", RECIPIENT, SUBJECT, "AUDIT", 4)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTESTATION_ALREADY_REVOKED"));
    }

    @Test
    void attestationAgainstUnknownEpochReturns404() throws Exception {
        attest("a-1", RECIPIENT, SUBJECT, "AUDIT", 99, "2026-09-30T12:00:00Z")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation")).isZero();
    }

    // ---------- 状态查询与隔离 ----------

    @Test
    void recipientCanQueryOwnAttestationStatuses() throws Exception {
        setupEpoch4WithRecord();
        attest("a-1", RECIPIENT, SUBJECT, "AUDIT", 4, "2026-09-30T12:00:00Z").andExpect(status().isOk());
        attest("a-2", "recipient-OTHER", SUBJECT, "AUDIT", 4, "2026-09-30T12:00:00Z").andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/recipient/attestations").param("recipientKey", RECIPIENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].recipientKey").value(RECIPIENT))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void auditTrailIsScopedBySubject() throws Exception {
        setupEpoch4WithRecord();
        attest("a-1", RECIPIENT, SUBJECT, "AUDIT", 4, "2026-09-30T12:00:00Z").andExpect(status().isOk());
        recipientRead(RECIPIENT, SUBJECT, "AUDIT", "rec-1").andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/consents/read-audits").param("subjectKey", "other-subject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---------- 撤回与读取并发 ----------

    @Test
    void concurrentRevokeAndReadOnlySucceedsBeforeCommit() throws Exception {
        setupEpoch4WithRecord();
        attest("a-1", RECIPIENT, SUBJECT, "AUDIT", 4, "2026-09-30T13:00:00Z").andExpect(status().isOk());

        BlockingAttestationRepository.arm();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 撤回事务开启后停在 UPDATE 之前（尚未提交）
            Future<?> revokeFuture = pool.submit(() -> attestationService.revokeAttestation(
                    new com.example.starter.consent.dto.AttestationRevokeRequest(
                            "ar-1", RECIPIENT, SUBJECT, Purpose.AUDIT, 4)));
            assertThat(BlockingAttestationRepository.entered().await(10, TimeUnit.SECONDS)).isTrue();

            // 撤回提交前：读取授权成功
            attestationService.read(RECIPIENT, SUBJECT, Purpose.AUDIT, "rec-1");

            // 放行撤回并等待提交
            BlockingAttestationRepository.release();
            revokeFuture.get(10, TimeUnit.SECONDS);

            // 撤回提交后：读取被拒绝
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> attestationService.read(RECIPIENT, SUBJECT, Purpose.AUDIT, "rec-1"))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus().value()).isEqualTo(403);
                        assertThat(ex.getCode()).isEqualTo("ATTESTATION_REVOKED");
                    });
        } finally {
            BlockingAttestationRepository.release();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        // 最终数据核对：承诺已撤回；恰好一条成功审计与一条撤回拒绝审计；记录未删除
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation WHERE status = 'REVOKED'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM recipient_read_audit WHERE result = 'SUCCESS'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM recipient_read_audit"
                + " WHERE result = 'DENIED' AND deny_reason = 'ATTESTATION_REVOKED'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
    }
}
