package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.ScopeCreateRequest;
import com.example.starter.consent.dto.ScopeRevokeRequest;

/**
 * 授权子范围 API 集成测试（真实 H2 MySQL 兼容库）：覆盖子范围划分、独立撤回、
 * 整体撤回交互、聚合可见性、清单查询以及并发与幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScopeConsentApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConsentService consentService;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_scope");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
    }

    private ResultActions grant(String requestId, String subjectKey, String purpose) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/grants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s"}
                        """.formatted(requestId, subjectKey, purpose)));
    }

    private ResultActions createScope(String requestId, String subjectKey, String purpose,
                                      int epoch, String scopeKey, String label) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/scopes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d,"scopeKey":"%s","label":"%s"}
                        """.formatted(requestId, subjectKey, purpose, epoch, scopeKey, label)));
    }

    private ResultActions revokeScope(String requestId, String subjectKey, String purpose,
                                      int epoch, String scopeKey) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/scope-revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d,"scopeKey":"%s"}
                        """.formatted(requestId, subjectKey, purpose, epoch, scopeKey)));
    }

    private ResultActions revoke(String requestId, String subjectKey, String purpose, int epoch) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d}
                        """.formatted(requestId, subjectKey, purpose, epoch)));
    }

    private ResultActions write(String requestId, String subjectKey, String purpose,
                                String recordKey, String payload, String scopeKey, String label) throws Exception {
        String scopePart = scopeKey == null ? "" : ",\"scopeKey\":\"" + scopeKey + "\"";
        String labelPart = label == null ? "" : ",\"label\":\"" + label + "\"";
        return mockMvc.perform(post("/api/v1/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","recordKey":"%s","payload":"%s"%s%s}
                        """.formatted(requestId, subjectKey, purpose, recordKey, payload, scopePart, labelPart)));
    }

    private ResultActions read(String subjectKey, String purpose, String recordKey) throws Exception {
        return mockMvc.perform(get("/api/v1/records")
                .param("subjectKey", subjectKey)
                .param("purpose", purpose)
                .param("recordKey", recordKey));
    }

    private ResultActions aggregate(String subjectKey, String purpose, int epoch) throws Exception {
        return mockMvc.perform(get("/api/v1/records/aggregate")
                .param("subjectKey", subjectKey)
                .param("purpose", purpose)
                .param("epoch", String.valueOf(epoch)));
    }

    private ResultActions listScopes(String subjectKey, String purpose, int epoch) throws Exception {
        return mockMvc.perform(get("/api/v1/consents/scopes")
                .param("subjectKey", subjectKey)
                .param("purpose", purpose)
                .param("epoch", String.valueOf(epoch)));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    // ---------- 子范围划分主流程 ----------

    @Test
    void writeWithoutScopeGoesToDefaultScope() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "p1", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKey").doesNotExist())
                .andExpect(jsonPath("$.epoch").value(1));
        read("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("p1"));
        assertThat(count("SELECT COUNT(*) FROM consent_scope")).isEqualTo(0);
    }

    @Test
    void explicitCreateScopePartitionsWithoutNewEpoch() throws Exception {
        grant("g-1", "subj-a", "RESEARCH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1));
        createScope("s-1", "subj-a", "RESEARCH", 1, "marketing", "营销子范围")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.scopeKey").value("marketing"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        // 子范围只是逻辑分区，不产生新 epoch
        grant("g-2", "subj-a", "RESEARCH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1));
        write("w-1", "subj-a", "RESEARCH", "rec-m", "pm", "marketing", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKey").value("marketing"));
    }

    @Test
    void writeWithNewScopeKeyAndLabelCreatesScopeImplicitly() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-m", "pm", "analytics", "分析子范围")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKey").value("analytics"));
        assertThat(count("SELECT COUNT(*) FROM consent_scope WHERE scope_key = 'analytics' AND status = 'ACTIVE'"))
                .isEqualTo(1);
    }

    @Test
    void writeWithNewScopeKeyWithoutLabelReturns400AndDoesNotOccupyRequestId() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-m", "pm", "analytics", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SCOPE_LABEL_REQUIRED"));
        // 失败不占键：同一 requestId 补标签后应成功
        write("w-1", "subj-a", "RESEARCH", "rec-m", "pm", "analytics", "分析子范围")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKey").value("analytics"));
    }

    @Test
    void defaultAndNamedScopesCoexistAndAreIsolated() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-d", "subj-a", "RESEARCH", "rec-default", "pd", null, null).andExpect(status().isOk());
        write("w-m", "subj-a", "RESEARCH", "rec-m", "pm", "marketing", "营销").andExpect(status().isOk());

        read("subj-a", "RESEARCH", "rec-default").andExpect(status().isOk());
        read("subj-a", "RESEARCH", "rec-m").andExpect(status().isOk());
    }

    @Test
    void createScopeSameKeySameLabelReturnsExistingScope() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("s-1", "subj-a", "RESEARCH", 1, "marketing", "营销").andExpect(status().isOk());
        createScope("s-2", "subj-a", "RESEARCH", 1, "marketing", "营销")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(count("SELECT COUNT(*) FROM consent_scope")).isEqualTo(1);
    }

    @Test
    void createScopeSameKeyDifferentLabelReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("s-1", "subj-a", "RESEARCH", 1, "marketing", "营销").andExpect(status().isOk());
        createScope("s-2", "subj-a", "RESEARCH", 1, "marketing", "市场")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCOPE_LABEL_CONFLICT"));
    }

    @Test
    void createScopeReplaySameRequestIdReturnsOriginal() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("s-1", "subj-a", "RESEARCH", 1, "marketing", "营销").andExpect(status().isOk());
        createScope("s-1", "subj-a", "RESEARCH", 1, "marketing", "营销")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKey").value("marketing"));
        assertThat(count("SELECT COUNT(*) FROM consent_scope")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 's-1'")).isEqualTo(1);
    }

    @Test
    void createScopeSameRequestIdDifferentParamsReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("s-1", "subj-a", "RESEARCH", 1, "marketing", "营销").andExpect(status().isOk());
        createScope("s-1", "subj-a", "RESEARCH", 1, "analytics", "分析")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void createScopeOnRevokedEpochReturns410() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        createScope("s-1", "subj-a", "RESEARCH", 1, "marketing", "营销")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void createScopeOnMissingEpochReturns404() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("s-1", "subj-a", "RESEARCH", 9, "marketing", "营销")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    // ---------- 子范围独立撤回 ----------

    @Test
    void independentRevokeMakesOnlyThatScopeGone() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-d", "subj-a", "RESEARCH", "rec-default", "pd", null, null).andExpect(status().isOk());
        write("w-a", "subj-a", "RESEARCH", "rec-a", "pa", "analytics", "分析").andExpect(status().isOk());
        write("w-m", "subj-a", "RESEARCH", "rec-m", "pm", "marketing", "营销").andExpect(status().isOk());

        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "analytics")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        // 被撤回子范围立即 410
        read("subj-a", "RESEARCH", "rec-a")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("SCOPE_REVOKED"));
        // 同 epoch 其他子范围与默认子范围不受影响
        read("subj-a", "RESEARCH", "rec-m").andExpect(status().isOk());
        read("subj-a", "RESEARCH", "rec-default").andExpect(status().isOk());

        // 撤回后新写入该子范围被拒绝
        write("w-a2", "subj-a", "RESEARCH", "rec-a2", "pa2", "analytics", null)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("SCOPE_REVOKED"));
    }

    @Test
    void revokedScopeKeyCannotBeReusedForNewWriteOrScope() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("s-1", "subj-a", "RESEARCH", 1, "marketing", "营销").andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "marketing").andExpect(status().isOk());

        // 复用已撤回 scopeKey 建新子范围：410
        createScope("s-2", "subj-a", "RESEARCH", 1, "marketing", "营销二号")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("SCOPE_REVOKED"));
        // 复用已撤回 scopeKey 写入：410
        write("w-1", "subj-a", "RESEARCH", "rec-m", "pm", "marketing", null)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("SCOPE_REVOKED"));
    }

    @Test
    void sameScopeNameInNewEpochIsFreshAndIndependent() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("s-1", "subj-a", "RESEARCH", 1, "marketing", "营销-旧").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-old", "old", "marketing", null).andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "marketing").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        grant("g-2", "subj-a", "RESEARCH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2));
        // 新 epoch 同名 scopeKey 视为全新子范围，与旧 epoch 记录无关
        createScope("s-2", "subj-a", "RESEARCH", 2, "marketing", "营销-新")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        write("w-2", "subj-a", "RESEARCH", "rec-new", "new", "marketing", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.scopeKey").value("marketing"));
        read("subj-a", "RESEARCH", "rec-new").andExpect(status().isOk());
    }

    @Test
    void revokeScopeReplaySameRequestIdReturnsOriginalResult() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("s-1", "subj-a", "RESEARCH", 1, "marketing", "营销").andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "marketing").andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "marketing")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'sr-1'")).isEqualTo(1);
    }

    @Test
    void revokeScopeAgainWithNewRequestIdReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("s-1", "subj-a", "RESEARCH", 1, "marketing", "营销").andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "marketing").andExpect(status().isOk());
        revokeScope("sr-2", "subj-a", "RESEARCH", 1, "marketing")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCOPE_ALREADY_REVOKED"));
    }

    @Test
    void revokeMissingScopeReturns404() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "ghost")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCOPE_NOT_FOUND"));
    }

    @Test
    void independentScopeRevokeAfterGlobalRevokeReturns410() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("s-1", "subj-a", "RESEARCH", 1, "marketing", "营销").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        // 整体 epoch 已撤回，子范围独立撤回请求直接 410
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "marketing")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    // ---------- 整体撤回与子范围交互 ----------

    @Test
    void globalRevokeMakesAllScopesGoneRegardlessOfScopeState() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-d", "subj-a", "RESEARCH", "rec-default", "pd", null, null).andExpect(status().isOk());
        write("w-a", "subj-a", "RESEARCH", "rec-a", "pa", "analytics", "分析").andExpect(status().isOk());
        write("w-m", "subj-a", "RESEARCH", "rec-m", "pm", "marketing", "营销").andExpect(status().isOk());
        // 一个子范围已独立撤回
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "analytics").andExpect(status().isOk());

        // 整体撤回不因子范围已撤回而受限
        revoke("r-1", "subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        read("subj-a", "RESEARCH", "rec-default").andExpect(status().isGone());
        read("subj-a", "RESEARCH", "rec-a").andExpect(status().isGone());
        read("subj-a", "RESEARCH", "rec-m")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void writeReplayAfterScopeRevokeCannotBypass() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-m", "pm", "marketing", "营销").andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "marketing").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-m", "pm", "marketing", "营销")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("SCOPE_REVOKED"));
    }

    // ---------- 聚合可见性与清单 ----------

    @Test
    void aggregateMarksScopeAndUsabilityWithoutFiltering() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-d", "subj-a", "RESEARCH", "rec-default", "pd", null, null).andExpect(status().isOk());
        write("w-a", "subj-a", "RESEARCH", "rec-a", "pa", "analytics", "分析").andExpect(status().isOk());
        write("w-m", "subj-a", "RESEARCH", "rec-m", "pm", "marketing", "营销").andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "analytics").andExpect(status().isOk());

        aggregate("subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].recordKey").value("rec-a"))
                .andExpect(jsonPath("$[0].scopeKey").value("analytics"))
                .andExpect(jsonPath("$[0].usable").value(false))
                .andExpect(jsonPath("$[1].recordKey").value("rec-default"))
                .andExpect(jsonPath("$[1].scopeKey").doesNotExist())
                .andExpect(jsonPath("$[1].usable").value(true))
                .andExpect(jsonPath("$[2].recordKey").value("rec-m"))
                .andExpect(jsonPath("$[2].scopeKey").value("marketing"))
                .andExpect(jsonPath("$[2].usable").value(true));
    }

    @Test
    void aggregateAfterGlobalRevokeMarksAllUnusableButStillReturnsRows() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-d", "subj-a", "RESEARCH", "rec-default", "pd", null, null).andExpect(status().isOk());
        write("w-m", "subj-a", "RESEARCH", "rec-m", "pm", "marketing", "营销").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        aggregate("subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.usable == true)]").isEmpty());
    }

    @Test
    void aggregateUnknownEpochReturns404() throws Exception {
        aggregate("subj-a", "RESEARCH", 99)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void listScopesReturnsStatusesPerEpoch() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("s-1", "subj-a", "RESEARCH", 1, "analytics", "分析").andExpect(status().isOk());
        createScope("s-2", "subj-a", "RESEARCH", 1, "marketing", "营销").andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "analytics").andExpect(status().isOk());

        listScopes("subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].scopeKey").value("analytics"))
                .andExpect(jsonPath("$[0].status").value("REVOKED"))
                .andExpect(jsonPath("$[1].scopeKey").value("marketing"))
                .andExpect(jsonPath("$[1].status").value("ACTIVE"));

        // 新 epoch 尚无已创建子范围，返回空数组
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());
        listScopes("subj-a", "RESEARCH", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void sameRecordKeyAcrossScopesConflicts() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "shared", "p1", "analytics", "分析").andExpect(status().isOk());
        // recordKey 在同一 epoch 内跨子范围唯一
        write("w-2", "subj-a", "RESEARCH", "shared", "p1", "marketing", "营销")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_SCOPE_CONFLICT"));
        write("w-3", "subj-a", "RESEARCH", "shared", "p1", null, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_SCOPE_CONFLICT"));
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentScopeRevokeAndWriteIsAdjudicatedByCommitOrder() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("s-1", "subj-a", "RESEARCH", 1, "marketing", "营销").andExpect(status().isOk());

        int rounds = 12;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < rounds; i++) {
                String subject = "subj-race-" + i;
                int round = i;
                grant("rg-" + round, subject, "RESEARCH").andExpect(status().isOk());
                createScope("rsc-" + round, subject, "RESEARCH", 1, "marketing", "营销").andExpect(status().isOk());

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                String writeRequestId = "rw-" + round;
                Callable<Integer> writeTask = () -> {
                    ready.countDown();
                    start.await();
                    return consentService.write(new RecordWriteRequest(
                            writeRequestId, subject, Purpose.RESEARCH, "rec-m", "pm",
                            "marketing", null)).epoch();
                };
                String revokeRequestId = "rsr-" + round;
                Callable<String> revokeTask = () -> {
                    ready.countDown();
                    start.await();
                    return consentService.revokeScope(new ScopeRevokeRequest(
                            revokeRequestId, subject, Purpose.RESEARCH, 1, "marketing")).status().name();
                };
                Future<Integer> writeFuture = pool.submit(writeTask);
                Future<String> revokeFuture = pool.submit(revokeTask);
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                // 撤回必定成功；写入按提交顺序要么成功要么 410，二者必须一致且最终撤回生效
                assertThat(revokeFuture.get(20, TimeUnit.SECONDS)).isEqualTo("REVOKED");
                Integer writeEpoch;
                boolean writeRejected = false;
                try {
                    writeEpoch = writeFuture.get(20, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException ex) {
                    assertThat(ex.getCause()).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) ex.getCause()).getStatus().value()).isEqualTo(410);
                    writeRejected = true;
                    writeEpoch = null;
                }
                if (!writeRejected) {
                    assertThat(writeEpoch).isEqualTo(1);
                }
                // 最终状态：子范围已撤回；若写入先提交则记录物理保留，但随后读取一律 410
                assertThat(count("SELECT COUNT(*) FROM consent_scope WHERE subject_key = ? AND status = 'REVOKED'",
                        subject)).isEqualTo(1);
                read(subject, "RESEARCH", "rec-m")
                        .andExpect(writeRejected ? status().isNotFound() : status().isGone());
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentCreatesSameScopeKeySameLabelProduceSingleScope() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "sc-concurrent-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return consentService.createScope(new ScopeCreateRequest(
                        requestId, "subj-a", Purpose.RESEARCH, 1, "marketing", "营销")).scopeKey();
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<String> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo("marketing");
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM consent_scope WHERE scope_key = 'marketing'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'SCOPE_CREATE'"))
                .isEqualTo(threads);
    }

    @Test
    void concurrentWritesIntoSameScopeAreSerializedByLocks() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("s-1", "subj-a", "RESEARCH", 1, "marketing", "营销").andExpect(status().isOk());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "w-concurrent-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return consentService.write(new RecordWriteRequest(
                        requestId, "subj-a", Purpose.RESEARCH, "rec-1", "same",
                        "marketing", null)).payload();
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<String> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo("same");
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'WRITE'")).isEqualTo(threads);
    }

    @Test
    void failedScopeWriteDoesNotConsumeRequestId() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        // 子范围已撤回，写入失败不占键
        createScope("s-1", "subj-a", "RESEARCH", 1, "marketing", "营销").andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "marketing").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-m", "pm", "marketing", null)
                .andExpect(status().isGone());
        // requestId 未被占用：新 epoch 下同名新子范围可使用该 requestId 成功写入
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-n", "pn", "marketing", "营销-新")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2));
    }
}
