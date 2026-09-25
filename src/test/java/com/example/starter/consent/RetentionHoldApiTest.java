package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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

import com.example.starter.consent.dto.CreateHoldRequest;
import com.example.starter.consent.dto.PurgeRequest;
import com.example.starter.consent.dto.ReleaseHoldRequest;

/**
 * 保留冻结 API 集成测试：覆盖冻结创建范围、撤回后受限读取、解除条件、
 * 清除语义、并发裁决与幂等边界。到期判定通过可控时钟确定性验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RetentionHoldApiTest {

    private static final Instant T0 = Instant.parse("2026-09-25T00:00:00Z");
    private static final String EXPIRES_AT = "2026-09-25T01:00:00Z";

    @TestConfiguration
    static class TestClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return MutableClock.utc(T0);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RetentionService retentionService;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM retention_hold");
        jdbc.update("DELETE FROM idempotency_request");
        clock.setInstant(T0);
    }

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

    private ResultActions read(String subjectKey, String purpose, String recordKey) throws Exception {
        return mockMvc.perform(get("/api/v1/records")
                .param("subjectKey", subjectKey)
                .param("purpose", purpose)
                .param("recordKey", recordKey));
    }

    private ResultActions createHold(String requestId, String holdKey, String subjectKey, String purpose,
                                     int epoch, String legalReason, String createdBy, String expiresAt)
            throws Exception {
        return mockMvc.perform(post("/api/v1/retention/holds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","holdKey":"%s","subjectKey":"%s","purpose":"%s","epoch":%d,\
                        "legalReason":"%s","createdBy":"%s","expiresAt":"%s"}
                        """.formatted(requestId, holdKey, subjectKey, purpose, epoch,
                        legalReason, createdBy, expiresAt)));
    }

    private ResultActions release(String requestId, String holdKey, String releasedBy, String note,
                                  String role) throws Exception {
        var request = post("/api/v1/retention/holds/releases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","holdKey":"%s","releasedBy":"%s","note":"%s"}
                        """.formatted(requestId, holdKey, releasedBy, note));
        if (role != null) {
            request = request.header(RetentionService.RETENTION_ROLE_HEADER, role);
        }
        return mockMvc.perform(request);
    }

    private ResultActions purge(String requestId, String subjectKey, String purpose, int epoch) throws Exception {
        return mockMvc.perform(post("/api/v1/retention/purges")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d}
                        """.formatted(requestId, subjectKey, purpose, epoch)));
    }

    private ResultActions legalRead(String subjectKey, String purpose, int epoch,
                                    String recordKey, String role) throws Exception {
        var request = get("/api/v1/retention/records")
                .param("subjectKey", subjectKey)
                .param("purpose", purpose)
                .param("epoch", String.valueOf(epoch))
                .param("recordKey", recordKey);
        if (role != null) {
            request = request.header(RetentionService.RETENTION_ROLE_HEADER, role);
        }
        return mockMvc.perform(request);
    }

    private ResultActions listHolds(String subjectKey, String purpose, int epoch) throws Exception {
        return mockMvc.perform(get("/api/v1/retention/holds")
                .param("subjectKey", subjectKey)
                .param("purpose", purpose)
                .param("epoch", String.valueOf(epoch)));
    }

    private ResultActions retainedCount(String subjectKey, String purpose, int epoch) throws Exception {
        return mockMvc.perform(get("/api/v1/retention/retained-count")
                .param("subjectKey", subjectKey)
                .param("purpose", purpose)
                .param("epoch", String.valueOf(epoch)));
    }

    private ResultActions releaseHistory(String subjectKey, String purpose, int epoch) throws Exception {
        return mockMvc.perform(get("/api/v1/retention/releases")
                .param("subjectKey", subjectKey)
                .param("purpose", purpose)
                .param("epoch", String.valueOf(epoch)));
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    // ---------- 冻结创建 ----------

    @Test
    void createHoldOnActiveEpochSucceeds() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdKey").value("hold-1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expiresAt").value(EXPIRES_AT));
        listHolds("subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].legalReason").value("LITIGATION"));
    }

    @Test
    void createHoldOnRevokedEpochSucceeds() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createHoldDuplicateActiveReasonReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        createHold("h-2", "hold-2", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-2", EXPIRES_AT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_ALREADY_ACTIVE"));
        assertThat(count("SELECT COUNT(*) FROM retention_hold")).isEqualTo(1);
    }

    @Test
    void createHoldSameReasonAllowedAfterExpiry() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        // 到期后原冻结不再生效，同一事由可再建生效冻结
        clock.setInstant(T0.plus(2, ChronoUnit.HOURS));
        createHold("h-2", "hold-2", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-2",
                "2026-09-25T03:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(count("SELECT COUNT(*) FROM retention_hold")).isEqualTo(2);
    }

    @Test
    void createHoldOnNonexistentEpochReturns404() throws Exception {
        createHold("h-1", "hold-1", "subj-none", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void createHoldWithPastExpiresAtReturns400() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1",
                "2026-09-24T23:00:00Z")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRES_AT_INVALID"));
    }

    @Test
    void createHoldReplaySameRequestIdReturnsOriginal() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdKey").value("hold-1"));
        assertThat(count("SELECT COUNT(*) FROM retention_hold")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'h-1'")).isEqualTo(1);
    }

    @Test
    void createHoldSameRequestIdWithDifferentParamsReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        createHold("h-1", "hold-2", "subj-a", "RESEARCH", 1, "AUDIT", "officer-1", EXPIRES_AT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void createHoldFailedDoesNotConsumeRequestId() throws Exception {
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isNotFound());
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
    }

    @Test
    void createHoldSameHoldKeySameParamsDedupes() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        // 新 requestId、相同 holdKey 与参数：返回原冻结，不重复创建
        createHold("h-2", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdKey").value("hold-1"));
        assertThat(count("SELECT COUNT(*) FROM retention_hold")).isEqualTo(1);
    }

    @Test
    void createHoldSameHoldKeyDifferentParamsReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        createHold("h-2", "hold-1", "subj-a", "RESEARCH", 1, "AUDIT", "officer-1", EXPIRES_AT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_KEY_CONFLICT"));
    }

    // ---------- 保留权限只读查询与撤回读取隔离 ----------

    @Test
    void legalHoldReadOnRevokedEpochReturnsLegalHoldMarker() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());

        // 保留角色可按记录标识只读查询，响应标记 LEGAL_HOLD 且不返回授权可用状态
        legalRead("subj-a", "RESEARCH", 1, "rec-1", RetentionService.RETENTION_ROLE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessBasis").value("LEGAL_HOLD"))
                .andExpect(jsonPath("$.payload").value("payload-1"))
                .andExpect(jsonPath("$.status").doesNotExist());

        // 冻结不恢复已撤回授权：普通业务读写仍是 410
        read("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        write("w-2", "subj-a", "RESEARCH", "rec-2", "payload-2")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void holdOnActiveEpochDoesNotBlockBusinessAccess() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        // 有效代次上的冻结不中断普通业务读写
        read("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("payload-1"));
        write("w-2", "subj-a", "RESEARCH", "rec-2", "payload-2").andExpect(status().isOk());
    }

    @Test
    void legalHoldReadWithoutRoleReturns403() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        legalRead("subj-a", "RESEARCH", 1, "rec-1", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RETENTION_ROLE_REQUIRED"));
        legalRead("subj-a", "RESEARCH", 1, "rec-1", "BUSINESS")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RETENTION_ROLE_REQUIRED"));
    }

    @Test
    void legalHoldReadWithoutActiveHoldReturns403() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        legalRead("subj-a", "RESEARCH", 1, "rec-1", RetentionService.RETENTION_ROLE)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_ACTIVE"));
    }

    @Test
    void legalHoldReadAfterExpiryReturns410() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        clock.setInstant(T0.plus(2, ChronoUnit.HOURS));
        legalRead("subj-a", "RESEARCH", 1, "rec-1", RetentionService.RETENTION_ROLE)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
    }

    @Test
    void legalHoldReadAfterReleaseReturns403() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        release("rel-1", "hold-1", "officer-2", "case closed", RetentionService.RETENTION_ROLE)
                .andExpect(status().isOk());
        legalRead("subj-a", "RESEARCH", 1, "rec-1", RetentionService.RETENTION_ROLE)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_ACTIVE"));
    }

    @Test
    void legalHoldReadMissingRecordReturns404() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        legalRead("subj-a", "RESEARCH", 1, "rec-none", RetentionService.RETENTION_ROLE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
    }

    // ---------- 撤回响应的保留记录数 ----------

    @Test
    void revokeWithActiveHoldReportsRetainedCount() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-2", "payload-2").andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.retainedRecordCount").value(2));
        // 存在生效冻结：数据不得物理清除
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(2);
    }

    @Test
    void revokeWithoutHoldReportsZeroRetainedCount() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retainedRecordCount").value(0));
        // 无冻结：沿用既有语义，数据物理保留但不可见
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
    }

    // ---------- 清除 ----------

    @Test
    void purgeWithoutHoldDeletesRecords() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-2", "payload-2").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        purge("p-1", "subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purgedRecordCount").value(2));
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(0);
        retainedCount("subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retainedRecordCount").value(0));
    }

    @Test
    void purgeWithActiveHoldReturns409AndKeepsData() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        purge("p-1", "subj-a", "RESEARCH", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_ACTIVE"));
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
        // 失败的清除不占用 requestId：冻结解除后同一 requestId 可成功清除
        release("rel-1", "hold-1", "officer-2", "case closed", RetentionService.RETENTION_ROLE)
                .andExpect(status().isOk());
        purge("p-1", "subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purgedRecordCount").value(1));
    }

    @Test
    void purgeAfterHoldExpirySucceeds() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        clock.setInstant(T0.plus(2, ChronoUnit.HOURS));
        purge("p-1", "subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purgedRecordCount").value(1));
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(0);
    }

    @Test
    void purgeOnActiveEpochReturns409AndDoesNotConsumeRequestId() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        purge("p-1", "subj-a", "RESEARCH", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EPOCH_NOT_REVOKED"));
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        purge("p-1", "subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purgedRecordCount").value(1));
    }

    @Test
    void purgeNonexistentEpochReturns404() throws Exception {
        purge("p-1", "subj-none", "RESEARCH", 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void purgeReplayAndRepeatedPurgeAreIdempotent() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        purge("p-1", "subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purgedRecordCount").value(1));
        // 同 requestId 重放返回首次结果
        purge("p-1", "subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purgedRecordCount").value(1));
        // 新 requestId 重复清除：幂等无效果，返回 0
        purge("p-2", "subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purgedRecordCount").value(0));
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'PURGE'")).isEqualTo(2);
    }

    @Test
    void createHoldAfterPurgeReturns404() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        purge("p-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        // 清除先提交：冻结返回 404，不恢复已清除数据
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EPOCH_DATA_PURGED"));
        assertThat(count("SELECT COUNT(*) FROM retention_hold")).isEqualTo(0);
    }

    // ---------- 人工解除 ----------

    @Test
    void releaseByDifferentActorSucceedsAndRecordsImmutableHistory() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        release("rel-1", "hold-1", "officer-2", "case closed", RetentionService.RETENTION_ROLE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releasedBy").value("officer-2"))
                .andExpect(jsonPath("$.releasedAt").value("2026-09-25T00:00:00Z"));

        releaseHistory("subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].holdKey").value("hold-1"))
                .andExpect(jsonPath("$[0].createdBy").value("officer-1"))
                .andExpect(jsonPath("$[0].releasedBy").value("officer-2"))
                .andExpect(jsonPath("$[0].note").value("case closed"));
        listHolds("subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("RELEASED"));

        // 解除记录不可变：再次解除（不同解除人、新 requestId）返回 409，历史保持原样
        release("rel-2", "hold-1", "officer-3", "tamper attempt", RetentionService.RETENTION_ROLE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_ALREADY_RELEASED"));
        releaseHistory("subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].releasedBy").value("officer-2"))
                .andExpect(jsonPath("$[0].note").value("case closed"));
    }

    @Test
    void releaseByCreatorReturns403() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        release("rel-1", "hold-1", "officer-1", "self release", RetentionService.RETENTION_ROLE)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HOLD_RELEASE_ACTOR_FORBIDDEN"));
        assertThat(count("SELECT COUNT(*) FROM retention_hold WHERE status = 'ACTIVE'")).isEqualTo(1);
    }

    @Test
    void releaseWithoutRetentionRoleReturns403() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        release("rel-1", "hold-1", "officer-2", "no role", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RETENTION_ROLE_REQUIRED"));
    }

    @Test
    void releaseExpiredHoldReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        clock.setInstant(T0.plus(2, ChronoUnit.HOURS));
        release("rel-1", "hold-1", "officer-2", "too late", RetentionService.RETENTION_ROLE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
    }

    @Test
    void releaseNonexistentHoldReturns404() throws Exception {
        release("rel-1", "hold-none", "officer-2", "nothing", RetentionService.RETENTION_ROLE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
    }

    @Test
    void releaseReplaySameRequestIdReturnsOriginal() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        release("rel-1", "hold-1", "officer-2", "case closed", RetentionService.RETENTION_ROLE)
                .andExpect(status().isOk());
        release("rel-1", "hold-1", "officer-2", "case closed", RetentionService.RETENTION_ROLE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'rel-1'")).isEqualTo(1);
        releaseHistory("subj-a", "RESEARCH", 1)
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ---------- 状态与计数查询 ----------

    @Test
    void holdStatusQueryReflectsDerivedStates() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        listHolds("subj-a", "RESEARCH", 1)
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
        // 到期由可注入时钟判定，状态派生为 EXPIRED
        clock.setInstant(T0.plus(2, ChronoUnit.HOURS));
        listHolds("subj-a", "RESEARCH", 1)
                .andExpect(jsonPath("$[0].status").value("EXPIRED"));
    }

    @Test
    void holdQueriesOnNonexistentEpochReturn404() throws Exception {
        listHolds("subj-none", "RESEARCH", 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
        retainedCount("subj-none", "RESEARCH", 1)
                .andExpect(status().isNotFound());
        releaseHistory("subj-none", "RESEARCH", 1)
                .andExpect(status().isNotFound());
    }

    @Test
    void retainedCountQueryReflectsRecords() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-2", "payload-2").andExpect(status().isOk());
        retainedCount("subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retainedRecordCount").value(2));
    }

    // ---------- 并发裁决 ----------

    @Test
    void concurrentHoldCreateAndPurgeResolvedByCommitOrder() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> holdTask = () -> {
            ready.countDown();
            start.await();
            try {
                retentionService.createHold(new CreateHoldRequest("h-c", "hold-c", "subj-a",
                        Purpose.RESEARCH, 1, "LITIGATION", "officer-1",
                        T0.plus(1, ChronoUnit.HOURS)));
                return "HOLD_OK";
            } catch (ApiException e) {
                return e.getCode();
            }
        };
        Callable<String> purgeTask = () -> {
            ready.countDown();
            start.await();
            try {
                retentionService.purge(new PurgeRequest("p-c", "subj-a", Purpose.RESEARCH, 1));
                return "PURGE_OK";
            } catch (ApiException e) {
                return e.getCode();
            }
        };
        Future<String> holdFuture = pool.submit(holdTask);
        Future<String> purgeFuture = pool.submit(purgeTask);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        String holdResult = holdFuture.get(20, TimeUnit.SECONDS);
        String purgeResult = purgeFuture.get(20, TimeUnit.SECONDS);
        pool.shutdown();

        int records = count("SELECT COUNT(*) FROM consent_record");
        int holds = count("SELECT COUNT(*) FROM retention_hold");
        if ("HOLD_OK".equals(holdResult)) {
            // 冻结先提交：清除必须保留数据
            assertThat(purgeResult).isEqualTo("HOLD_ACTIVE");
            assertThat(records).isEqualTo(1);
            assertThat(holds).isEqualTo(1);
        } else {
            // 清除先提交：冻结返回 404，不恢复已清除数据
            assertThat(holdResult).isEqualTo("EPOCH_DATA_PURGED");
            assertThat(purgeResult).isEqualTo("PURGE_OK");
            assertThat(records).isEqualTo(0);
            assertThat(holds).isEqualTo(0);
        }
    }

    @Test
    void concurrentHoldsSameReasonProduceSingleActiveHold() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    retentionService.createHold(new CreateHoldRequest("h-con-" + index, "hold-con-" + index,
                            "subj-a", Purpose.RESEARCH, 1, "LITIGATION", "officer-" + index,
                            T0.plus(1, ChronoUnit.HOURS)));
                    return "OK";
                } catch (ApiException e) {
                    return e.getCode();
                }
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int succeeded = 0;
        for (Future<String> future : futures) {
            if ("OK".equals(future.get(20, TimeUnit.SECONDS))) {
                succeeded++;
            }
        }
        pool.shutdown();
        assertThat(succeeded).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM retention_hold WHERE status = 'ACTIVE'")).isEqualTo(1);
    }

    @Test
    void concurrentReleasesProduceSingleReleaseRecord() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-a", "RESEARCH", 1, "LITIGATION", "officer-1", EXPIRES_AT)
                .andExpect(status().isOk());
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    retentionService.release(new ReleaseHoldRequest("rel-con-" + index, "hold-1",
                            "officer-r" + index, "concurrent release"), RetentionService.RETENTION_ROLE);
                    return "OK";
                } catch (ApiException e) {
                    return e.getCode();
                }
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int succeeded = 0;
        for (Future<String> future : futures) {
            if ("OK".equals(future.get(20, TimeUnit.SECONDS))) {
                succeeded++;
            }
        }
        pool.shutdown();
        assertThat(succeeded).isEqualTo(1);
        // 解除记录不可变且只有一条
        assertThat(count("SELECT COUNT(*) FROM retention_hold WHERE status = 'RELEASED'")).isEqualTo(1);
        releaseHistory("subj-a", "RESEARCH", 1)
                .andExpect(jsonPath("$.length()").value(1));
    }
}
