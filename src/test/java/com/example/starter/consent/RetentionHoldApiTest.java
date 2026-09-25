package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.consent.dto.HoldCreateRequest;
import com.example.starter.consent.dto.PurgeRequest;
import com.example.starter.consent.dto.PurgeResponse;

/**
 * 保留冻结 API 的真实 H2（MODE=MySQL）集成测试：
 * 覆盖冻结范围、撤回读取隔离、解除条件、到期清除、角色权限与并发/幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfig.class)
class RetentionHoldApiTest {

    private static final String OFFICER_A = "officer-a";
    private static final String OFFICER_B = "officer-b";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RetentionHoldService retentionHoldService;

    @Autowired
    private MutableClockTestConfig.MutableBusinessClock clock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM retention_hold_release");
        jdbc.update("DELETE FROM retention_hold");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
        clock.setInstant(MutableClockTestConfig.INITIAL_NOW);
    }

    private Instant inFuture(long seconds) {
        return MutableClockTestConfig.INITIAL_NOW.plusSeconds(seconds);
    }

    private ResultActions grant(String requestId, String subjectKey) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/grants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH"}
                        """.formatted(requestId, subjectKey)));
    }

    private ResultActions write(String requestId, String subjectKey, String recordKey, String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH","recordKey":"%s","payload":"%s"}
                        """.formatted(requestId, subjectKey, recordKey, payload)));
    }

    private ResultActions revoke(String requestId, String subjectKey, int epoch) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH","epoch":%d}
                        """.formatted(requestId, subjectKey, epoch)));
    }

    private ResultActions read(String subjectKey, String recordKey) throws Exception {
        return mockMvc.perform(get("/api/v1/records")
                .param("subjectKey", subjectKey).param("purpose", "RESEARCH").param("recordKey", recordKey));
    }

    private ResultActions createHold(String requestId, String holdKey, String subjectKey, int epoch,
                                     String reason, Instant expiresAt, String actor, String role) throws Exception {
        var builder = post("/api/v1/retention/holds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","holdKey":"%s","subjectKey":"%s","purpose":"RESEARCH","epoch":%d,
                         "reason":"%s","expiresAt":"%s"}
                        """.formatted(requestId, holdKey, subjectKey, epoch, reason, expiresAt.toString()));
        if (actor != null) {
            builder.header("X-Actor-Id", actor);
        }
        if (role != null) {
            builder.header("X-Actor-Role", role);
        }
        return mockMvc.perform(builder);
    }

    private ResultActions release(String holdKey, String requestId, String note, String actor, String role)
            throws Exception {
        var builder = post("/api/v1/retention/holds/{0}/releases", holdKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","note":"%s"}
                        """.formatted(requestId, note));
        if (actor != null) {
            builder.header("X-Actor-Id", actor);
        }
        if (role != null) {
            builder.header("X-Actor-Role", role);
        }
        return mockMvc.perform(builder);
    }

    private ResultActions legalRead(String subjectKey, int epoch, String recordKey, String actor, String role)
            throws Exception {
        var builder = get("/api/v1/retention/records")
                .param("subjectKey", subjectKey).param("purpose", "RESEARCH")
                .param("epoch", String.valueOf(epoch)).param("recordKey", recordKey);
        if (actor != null) {
            builder.header("X-Actor-Id", actor);
        }
        if (role != null) {
            builder.header("X-Actor-Role", role);
        }
        return mockMvc.perform(builder);
    }

    private ResultActions purge(String requestId, String subjectKey, int epoch) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/purges")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH","epoch":%d}
                        """.formatted(requestId, subjectKey, epoch)));
    }

    private ResultActions epochStatus(String subjectKey, int epoch) throws Exception {
        return mockMvc.perform(get("/api/v1/retention/epochs/status")
                .param("subjectKey", subjectKey).param("purpose", "RESEARCH")
                .param("epoch", String.valueOf(epoch)));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    // ---------- 冻结创建主流程 ----------

    @Test
    void createHoldOnActiveEpochSucceedsAndMarksEffective() throws Exception {
        grant("g-1", "subj-1");
        createHold("h-1", "hold-1", "subj-1", 1, "COURT_CASE_001", inFuture(3600),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdKey").value("hold-1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.effective").value(true))
                .andExpect(jsonPath("$.createdBy").value(OFFICER_A));
        assertThat(count("SELECT COUNT(*) FROM retention_hold WHERE hold_key = 'hold-1'")).isEqualTo(1);
    }

    @Test
    void sameReasonSecondHoldReturns409ButDifferentReasonAllowed() throws Exception {
        grant("g-1", "subj-1");
        createHold("h-1", "hold-1", "subj-1", 1, "COURT_CASE_001", inFuture(3600),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER).andExpect(status().isCreated());
        createHold("h-2", "hold-2", "subj-1", 1, "COURT_CASE_001", inFuture(3600),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_ALREADY_ACTIVE"));
        createHold("h-3", "hold-3", "subj-1", 1, "AUDIT_2026", inFuture(3600),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isCreated());
        assertThat(count("SELECT COUNT(*) FROM retention_hold WHERE subject_key = 'subj-1'")).isEqualTo(2);
    }

    @Test
    void createHoldRequiresRetentionRole() throws Exception {
        grant("g-1", "subj-1");
        // 普通角色
        createHold("h-1", "hold-1", "subj-1", 1, "COURT_CASE_001", inFuture(3600),
                "biz-user", "BUSINESS_USER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // 缺少角色头
        createHold("h-1", "hold-1", "subj-1", 1, "COURT_CASE_001", inFuture(3600),
                null, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(count("SELECT COUNT(*) FROM retention_hold")).isZero();
    }

    @Test
    void createHoldOnMissingEpochReturns404() throws Exception {
        createHold("h-1", "hold-1", "subj-x", 9, "COURT_CASE_001", inFuture(3600),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EPOCH_NOT_FOUND"));
    }

    @Test
    void createHoldWithPastExpiryReturns400AndDoesNotConsumeRequestId() throws Exception {
        grant("g-1", "subj-1");
        createHold("h-1", "hold-1", "subj-1", 1, "COURT_CASE_001",
                MutableClockTestConfig.INITIAL_NOW.minusSeconds(10),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRES_IN_PAST"));
        // 失败不占键：同一 requestId 改为合法参数后成功
        createHold("h-1", "hold-1", "subj-1", 1, "COURT_CASE_001", inFuture(3600),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isCreated());
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'h-1'")).isEqualTo(1);
    }

    @Test
    void duplicateHoldKeyAcrossEpochsReturns409() throws Exception {
        grant("g-1", "subj-1");
        createHold("h-1", "hold-dup", "subj-1", 1, "COURT_CASE_001", inFuture(3600),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER).andExpect(status().isCreated());
        grant("g-2", "subj-2");
        createHold("h-2", "hold-dup", "subj-2", 1, "COURT_CASE_002", inFuture(3600),
                OFFICER_B, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_KEY_CONFLICT"));
    }

    // ---------- 撤回隔离与保留读取 ----------

    @Test
    void holdOnRevokedEpochAllowsOnlyLegalHoldReadMarkedLegalHold() throws Exception {
        grant("g-1", "subj-1");
        write("w-1", "subj-1", "rec-1", "secret");
        createHold("h-1", "hold-1", "subj-1", 1, "COURT_CASE_001", inFuture(3600),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER).andExpect(status().isCreated());
        revoke("r-1", "subj-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.retainedRecords").value(1));

        // 普通业务读取仍按撤回隔离返回 410
        read("subj-1", "rec-1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        // 普通业务写入仍被拒绝
        write("w-2", "subj-1", "rec-2", "more")
                .andExpect(status().isGone());
        // 保留角色只读：LEGAL_HOLD 且不返回业务可用授权状态
        legalRead("subj-1", 1, "rec-1", OFFICER_B, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessMode").value("LEGAL_HOLD"))
                .andExpect(jsonPath("$.consentAvailableForBusiness").value(false))
                .andExpect(jsonPath("$.payload").value("secret"));
        // 数据物理保留
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
    }

    @Test
    void activeHoldBlocksNormalBusinessReadAndWriteWith423() throws Exception {
        grant("g-1", "subj-1");
        write("w-1", "subj-1", "rec-1", "data");
        createHold("h-1", "hold-1", "subj-1", 1, "COURT_CASE_001", inFuture(3600),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER).andExpect(status().isCreated());

        read("subj-1", "rec-1")
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("LEGAL_HOLD_ACTIVE"));
        write("w-2", "subj-1", "rec-2", "data-2")
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("LEGAL_HOLD_ACTIVE"));

        // 冻结到期后普通业务读写恢复
        clock.setInstant(inFuture(7200));
        read("subj-1", "rec-1").andExpect(status().isOk());
        write("w-3", "subj-1", "rec-3", "data-3").andExpect(status().isOk());
    }

    @Test
    void legalHoldReadEnforcesRoleAndRecordExistence() throws Exception {
        grant("g-1", "subj-1");
        write("w-1", "subj-1", "rec-1", "data");
        createHold("h-1", "hold-1", "subj-1", 1, "COURT_CASE_001", inFuture(3600),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER).andExpect(status().isCreated());

        legalRead("subj-1", 1, "rec-1", "biz-user", "BUSINESS_USER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        legalRead("subj-1", 1, "rec-missing", OFFICER_B, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LEGAL_HOLD_RECORD_NOT_FOUND"));
    }

    @Test
    void legalHoldReadWithoutAnyHoldReturns404() throws Exception {
        grant("g-1", "subj-1");
        write("w-1", "subj-1", "rec-1", "data");
        legalRead("subj-1", 1, "rec-1", OFFICER_A, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
    }

    @Test
    void oneEffectiveAmongMultipleHoldsKeepsLegalReadAvailable() throws Exception {
        grant("g-1", "subj-1");
        write("w-1", "subj-1", "rec-1", "data");
        createHold("h-1", "hold-1", "subj-1", 1, "COURT_CASE_001", inFuture(1000),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER).andExpect(status().isCreated());
        createHold("h-2", "hold-2", "subj-1", 1, "AUDIT_2026", inFuture(7200),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER).andExpect(status().isCreated());
        // 第一个冻结到期，第二个仍生效
        clock.setInstant(inFuture(2000));
        legalRead("subj-1", 1, "rec-1", OFFICER_B, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessMode").value("LEGAL_HOLD"));
    }

    // ---------- 到期与清除 ----------

    @Test
    void expiredHoldMakesLegalRead410AndNextPurgeDeletesData() throws Exception {
        grant("g-1", "subj-1");
        write("w-1", "subj-1", "rec-1", "data");
        write("w-2", "subj-1", "rec-2", "data2");
        revoke("r-1", "subj-1", 1).andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-1", 1, "COURT_CASE_001", inFuture(3600),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER).andExpect(status().isCreated());

        // 生效冻结时清除保留全部数据
        purge("p-1", "subj-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purgedRecords").value(0))
                .andExpect(jsonPath("$.retainedRecords").value(2))
                .andExpect(jsonPath("$.remainingRecords").value(2));

        // 到期后保留读取 410
        clock.setInstant(inFuture(7200));
        legalRead("subj-1", 1, "rec-1", OFFICER_B, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));

        // 下一次清除删除数据
        purge("p-2", "subj-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purgedRecords").value(2))
                .andExpect(jsonPath("$.retainedRecords").value(0))
                .andExpect(jsonPath("$.remainingRecords").value(0));
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isZero();

        // 已清除 epoch：保留读取 410、再清除 404、新建冻结 404
        legalRead("subj-1", 1, "rec-1", OFFICER_B, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("EPOCH_PURGED"));
        purge("p-3", "subj-1", 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EPOCH_PURGED"));
        createHold("h-9", "hold-9", "subj-1", 1, "LATE_REASON", clock.now().plusSeconds(3600),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EPOCH_PURGED"));
    }

    @Test
    void purgeWithoutHoldOnRevokedEpochDeletesData() throws Exception {
        grant("g-1", "subj-1");
        write("w-1", "subj-1", "rec-1", "data");
        revoke("r-1", "subj-1", 1).andExpect(status().isOk());
        purge("p-1", "subj-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purgedRecords").value(1));
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isZero();
    }

    @Test
    void purgeOnActiveEpochReturns409() throws Exception {
        grant("g-1", "subj-1");
        write("w-1", "subj-1", "rec-1", "data");
        purge("p-1", "subj-1", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GRANT_ACTIVE"));
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
    }

    // ---------- 人工解除 ----------

    @Test
    void releaseRequiresDifferentOfficerAndIsImmutable() throws Exception {
        grant("g-1", "subj-1");
        write("w-1", "subj-1", "rec-1", "data");
        createHold("h-1", "hold-1", "subj-1", 1, "COURT_CASE_001", inFuture(3600),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER).andExpect(status().isCreated());

        // 创建人本人解除 409
        release("hold-1", "rel-x", "note", OFFICER_A, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_RELEASE_SAME_ACTOR"));
        // 非保留角色解除 403
        release("hold-1", "rel-x", "note", "biz-user", "BUSINESS_USER")
                .andExpect(status().isForbidden());
        // 不同保留角色解除成功
        release("hold-1", "rel-1", "court case closed", OFFICER_B, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdKey").value("hold-1"))
                .andExpect(jsonPath("$.releasedBy").value(OFFICER_B))
                .andExpect(jsonPath("$.note").value("court case closed"));
        // 再次解除 409（已解除）
        release("hold-1", "rel-2", "again", OFFICER_B, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_ALREADY_RELEASED"));
        // 解除历史不可变：仅一条
        assertThat(count("SELECT COUNT(*) FROM retention_hold_release WHERE hold_key = 'hold-1'")).isEqualTo(1);
        // 解除后保留读取 410
        legalRead("subj-1", 1, "rec-1", OFFICER_A, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("HOLD_ALREADY_RELEASED"));
        // 解除后下一次清除可处理数据
        revoke("r-1", "subj-1", 1).andExpect(status().isOk());
        purge("p-1", "subj-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purgedRecords").value(1));
    }

    @Test
    void releaseExpiredHoldReturns409() throws Exception {
        grant("g-1", "subj-1");
        createHold("h-1", "hold-1", "subj-1", 1, "COURT_CASE_001", inFuture(3600),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER).andExpect(status().isCreated());
        clock.setInstant(inFuture(7200));
        release("hold-1", "rel-1", "late note", OFFICER_B, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
    }

    @Test
    void releaseMissingHoldReturns404() throws Exception {
        release("nope", "rel-1", "note", OFFICER_B, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
    }

    // ---------- 状态查询 ----------

    @Test
    void epochStatusReportsHoldsCountsAndReleaseHistory() throws Exception {
        grant("g-1", "subj-1");
        write("w-1", "subj-1", "rec-1", "data");
        write("w-2", "subj-1", "rec-2", "data2");
        revoke("r-1", "subj-1", 1).andExpect(status().isOk());
        createHold("h-1", "hold-1", "subj-1", 1, "COURT_CASE_001", inFuture(3600),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER).andExpect(status().isCreated());
        release("hold-1", "rel-1", "closed", OFFICER_B, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isCreated());

        epochStatus("subj-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grantStatus").value("REVOKED"))
                .andExpect(jsonPath("$.holds[0].holdKey").value("hold-1"))
                .andExpect(jsonPath("$.holds[0].status").value("RELEASED"))
                .andExpect(jsonPath("$.holds[0].effective").value(false))
                .andExpect(jsonPath("$.retainedRecordCount").value(0))
                .andExpect(jsonPath("$.releaseHistory[0].holdKey").value("hold-1"))
                .andExpect(jsonPath("$.releaseHistory[0].releasedBy").value(OFFICER_B));

        // 重新建立生效冻结后计数为 2
        createHold("h-2", "hold-2", "subj-1", 1, "AUDIT_2026", inFuture(3600),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER).andExpect(status().isCreated());
        epochStatus("subj-1", 1)
                .andExpect(jsonPath("$.retainedRecordCount").value(2))
                .andExpect(jsonPath("$.holds.length()").value(2))
                .andExpect(jsonPath("$.releaseHistory.length()").value(1));
    }

    @Test
    void epochStatusMissingEpochReturns404() throws Exception {
        epochStatus("subj-x", 99).andExpect(status().isNotFound());
    }

    // ---------- 幂等 ----------

    @Test
    void createHoldReplayReturnsOriginalAndDifferentParams409() throws Exception {
        grant("g-1", "subj-1");
        createHold("h-1", "hold-1", "subj-1", 1, "COURT_CASE_001", inFuture(3600),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER).andExpect(status().isCreated());
        createHold("h-1", "hold-1", "subj-1", 1, "COURT_CASE_001", inFuture(3600),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdKey").value("hold-1"));
        assertThat(count("SELECT COUNT(*) FROM retention_hold")).isEqualTo(1);
        // 同 requestId 异参 409
        createHold("h-1", "hold-1", "subj-1", 1, "OTHER_REASON", inFuture(3600),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void releaseReplayReturnsOriginalResult() throws Exception {
        grant("g-1", "subj-1");
        createHold("h-1", "hold-1", "subj-1", 1, "R1", inFuture(3600),
                OFFICER_A, Actor.ROLE_RETENTION_OFFICER).andExpect(status().isCreated());
        release("hold-1", "rel-1", "note", OFFICER_B, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isCreated());
        // 同键同参重放返回首次结果，不会因“已解除”报 409
        release("hold-1", "rel-1", "note", OFFICER_B, Actor.ROLE_RETENTION_OFFICER)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.note").value("note"));
        assertThat(count("SELECT COUNT(*) FROM retention_hold_release")).isEqualTo(1);
    }

    @Test
    void purgeReplayReturnsFirstSnapshot() throws Exception {
        grant("g-1", "subj-1");
        write("w-1", "subj-1", "rec-1", "data");
        revoke("r-1", "subj-1", 1).andExpect(status().isOk());
        purge("p-1", "subj-1", 1).andExpect(status().isOk());
        purge("p-1", "subj-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purgedRecords").value(1));
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'p-1'")).isEqualTo(1);
    }

    // ---------- 并发裁决：冻结 vs 清除 ----------

    @Test
    void freezeFirstThenPurgeRetainsDataDeterministically() throws Exception {
        grant("g-1", "subj-1");
        write("w-1", "subj-1", "rec-1", "data");
        write("w-2", "subj-1", "rec-2", "data2");
        revoke("r-1", "subj-1", 1).andExpect(status().isOk());
        retentionHoldService.createHold(new HoldCreateRequest("h-1", "hold-1", "subj-1",
                Purpose.RESEARCH, 1, "R1", inFuture(3600)),
                new Actor(OFFICER_A, true));
        PurgeResponse response = retentionHoldService.purge(
                new PurgeRequest("p-1", "subj-1", Purpose.RESEARCH, 1));
        assertThat(response.purgedRecords()).isZero();
        assertThat(response.retainedRecords()).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE purged_at IS NOT NULL")).isZero();
    }

    @Test
    void purgeFirstThenFreezeReturns404AndDataStaysDeleted() throws Exception {
        grant("g-1", "subj-1");
        write("w-1", "subj-1", "rec-1", "data");
        revoke("r-1", "subj-1", 1).andExpect(status().isOk());
        retentionHoldService.purge(new PurgeRequest("p-1", "subj-1", Purpose.RESEARCH, 1));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> retentionHoldService.createHold(
                        new HoldCreateRequest("h-1", "hold-1", "subj-1", Purpose.RESEARCH, 1, "R1", inFuture(3600)),
                        new Actor(OFFICER_A, true)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("EPOCH_PURGED"));
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isZero();
    }

    @Test
    void concurrentHoldAndPurgeArbitratedByCommitOrder() throws Exception {
        int epochs = 8;
        for (int i = 1; i <= epochs; i++) {
            String subject = "subj-c" + i;
            grant("g-" + i, subject);
            write("w-" + i + "-1", subject, "rec-1", "data");
            write("w-" + i + "-2", subject, "rec-2", "data2");
            revoke("r-" + i, subject, 1).andExpect(status().isOk());
        }

        ExecutorService pool = Executors.newFixedThreadPool(epochs * 2);
        CountDownLatch ready = new CountDownLatch(epochs * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> holdOutcomes = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 1; i <= epochs; i++) {
            String subject = "subj-c" + i;
            int epochNo = i;
            holdOutcomes.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    return "TIMEOUT";
                }
                try {
                    retentionHoldService.createHold(new HoldCreateRequest(
                            "ch-" + epochNo, "chold-" + epochNo, subject, Purpose.RESEARCH, 1, "R" + epochNo,
                            inFuture(3600)), new Actor(OFFICER_A, true));
                    return "HOLD_OK";
                } catch (ApiException ex) {
                    // 清除先提交时冻结预期失败
                    return ex.getCode();
                }
            }));
            futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    return null;
                }
                retentionHoldService.purge(new PurgeRequest("cp-" + epochNo, subject, Purpose.RESEARCH, 1));
                return null;
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        for (Future<String> outcome : holdOutcomes) {
            String code = outcome.get(30, TimeUnit.SECONDS);
            assertThat(code).isIn("HOLD_OK", RetentionHoldService.CODE_EPOCH_PURGED);
        }
        pool.shutdown();

        // 每个 epoch 必须满足且仅满足一种裁决结果：
        // 冻结先提交 -> 数据保留且冻结存在；清除先提交 -> 数据已删且冻结以 EPOCH_PURGED 失败
        for (int i = 1; i <= epochs; i++) {
            String subject = "subj-c" + i;
            int records = count("SELECT COUNT(*) FROM consent_record WHERE subject_key = ?", subject);
            int holds = count("SELECT COUNT(*) FROM retention_hold WHERE subject_key = ?", subject);
            int purged = count("SELECT COUNT(*) FROM consent_grant WHERE subject_key = ? AND purged_at IS NOT NULL",
                    subject);
            if (holds == 1) {
                assertThat(records).as(subject + " 冻结先提交应保留数据").isEqualTo(2);
                assertThat(purged).as(subject + " 冻结先提交不应标记清除").isZero();
            } else {
                assertThat(holds).as(subject + " 清除先提交时冻结不得创建").isZero();
                assertThat(records).as(subject + " 清除先提交应删除数据").isZero();
                assertThat(purged).isEqualTo(1);
            }
        }
    }
}
