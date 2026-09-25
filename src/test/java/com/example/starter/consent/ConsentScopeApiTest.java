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
 * 授权子范围 API 集成测试：覆盖子范围划分、独立撤回、聚合可见性、幂等与并发边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsentScopeApiTest {

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
                                      String scopeKey, String label) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/scopes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","scopeKey":"%s","label":"%s"}
                        """.formatted(requestId, subjectKey, purpose, scopeKey, label)));
    }

    private ResultActions revokeScope(String requestId, String subjectKey, String purpose,
                                      int epoch, String scopeKey) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/scope-revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d,"scopeKey":"%s"}
                        """.formatted(requestId, subjectKey, purpose, epoch, scopeKey)));
    }

    private ResultActions write(String requestId, String subjectKey, String purpose,
                                String recordKey, String scopeKey, String payload) throws Exception {
        String scopeField = scopeKey == null ? "" : "\"scopeKey\":\"%s\",".formatted(scopeKey);
        return mockMvc.perform(post("/api/v1/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","recordKey":"%s",%s"payload":"%s"}
                        """.formatted(requestId, subjectKey, purpose, recordKey, scopeField, payload)));
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
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    // ---------- 子范围创建与写入划分 ----------

    @Test
    void createScopeThenWriteAndReadWithinScope() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.scopeKey").value("scope-a"))
                .andExpect(jsonPath("$.label").value("标签A"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        write("w-1", "subj-a", "RESEARCH", "rec-1", "scope-a", "payload-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKey").value("scope-a"));
        read("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKey").value("scope-a"))
                .andExpect(jsonPath("$.payload").value("payload-1"));
    }

    @Test
    void writeWithoutScopeFallsIntoDefaultScope() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", null, "payload-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKey").value("default"));
        read("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKey").value("default"));
    }

    @Test
    void createScopeDoesNotCreateNewEpoch() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A").andExpect(status().isOk());
        createScope("sc-2", "subj-a", "RESEARCH", "scope-b", "标签B").andExpect(status().isOk());
        // 子范围只是逻辑分区，不产生新代次
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE subject_key = 'subj-a'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_scope WHERE epoch = 1")).isEqualTo(2);
    }

    @Test
    void writeWithUnknownScopeReturns404() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "scope-none", "payload-1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCOPE_NOT_FOUND"));
    }

    @Test
    void createScopeWithoutGrantReturns404() throws Exception {
        createScope("sc-1", "subj-none", "RESEARCH", "scope-a", "标签A")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void createScopeAfterWholeRevokeReturns410() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void createScopeReservedDefaultKeyReturns400() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "default", "标签A")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SCOPE_KEY"));
    }

    @Test
    void createScopeWithBlankLabelReturns400() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void createScopeSameKeySameLabelDeduplicates() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A").andExpect(status().isOk());
        // 新 requestId、相同 scopeKey 与标签：返回原子范围，不重复创建
        createScope("sc-2", "subj-a", "RESEARCH", "scope-a", "标签A")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(count("SELECT COUNT(*) FROM consent_scope")).isEqualTo(1);
    }

    @Test
    void createScopeSameKeyDifferentLabelReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A").andExpect(status().isOk());
        createScope("sc-2", "subj-a", "RESEARCH", "scope-a", "标签B")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCOPE_LABEL_CONFLICT"));
    }

    // ---------- 子范围独立撤回 ----------

    @Test
    void scopeRevokeMakesOnlyThatScopeGone() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A").andExpect(status().isOk());
        createScope("sc-2", "subj-a", "RESEARCH", "scope-b", "标签B").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-a", "scope-a", "payload-a").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-b", "scope-b", "payload-b").andExpect(status().isOk());
        write("w-3", "subj-a", "RESEARCH", "rec-d", null, "payload-d").andExpect(status().isOk());

        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "scope-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKey").value("scope-a"))
                .andExpect(jsonPath("$.status").value("REVOKED"));

        // 被撤回子范围立即 410
        read("subj-a", "RESEARCH", "rec-a")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("SCOPE_REVOKED"));
        write("w-4", "subj-a", "RESEARCH", "rec-a2", "scope-a", "payload-a2")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("SCOPE_REVOKED"));
        // 同 epoch 其他子范围与默认子范围不受影响
        read("subj-a", "RESEARCH", "rec-b")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("payload-b"));
        read("subj-a", "RESEARCH", "rec-d")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("payload-d"));
        write("w-5", "subj-a", "RESEARCH", "rec-b2", "scope-b", "payload-b2").andExpect(status().isOk());
        write("w-6", "subj-a", "RESEARCH", "rec-d2", null, "payload-d2").andExpect(status().isOk());
    }

    @Test
    void revokedScopeKeyCannotBeReusedWithinSameEpoch() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A").andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "scope-a").andExpect(status().isOk());
        // 已撤回子范围的 scopeKey 不可复用于新子范围
        createScope("sc-2", "subj-a", "RESEARCH", "scope-a", "标签A2")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("SCOPE_REVOKED"));
        assertThat(count("SELECT COUNT(*) FROM consent_scope")).isEqualTo(1);
    }

    @Test
    void sameScopeKeyInNewEpochIsFreshScope() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "旧标签").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "scope-a", "old-payload").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());

        // 新 epoch 下同名 scopeKey 视为全新子范围
        createScope("sc-2", "subj-a", "RESEARCH", "scope-a", "新标签")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.label").value("新标签"));
        write("w-2", "subj-a", "RESEARCH", "rec-1", "scope-a", "new-payload")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2));
        read("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("new-payload"));
        assertThat(count("SELECT COUNT(*) FROM consent_scope WHERE scope_key = 'scope-a'")).isEqualTo(2);
    }

    @Test
    void scopeRevokeAfterWholeRevokeReturns410() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        // 整体已撤回，子范围不可单独撤回
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "scope-a")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void scopeRevokeNonexistentScopeReturns404() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "scope-none")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCOPE_NOT_FOUND"));
    }

    @Test
    void scopeRevokeNonexistentEpochReturns404() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 9, "scope-a")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void scopeRevokeReplaySameRequestIdReturnsOriginal() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A").andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "scope-a").andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "scope-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'sr-1'")).isEqualTo(1);
    }

    @Test
    void scopeRevokeAlreadyRevokedWithNewRequestIdReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A").andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "scope-a").andExpect(status().isOk());
        revokeScope("sr-2", "subj-a", "RESEARCH", 1, "scope-a")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCOPE_ALREADY_REVOKED"));
    }

    @Test
    void scopeRevokeSameRequestIdWithDifferentScopeReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A").andExpect(status().isOk());
        createScope("sc-2", "subj-a", "RESEARCH", "scope-b", "标签B").andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "scope-a").andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "scope-b")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    // ---------- 整体撤回支配子范围 ----------

    @Test
    void wholeRevokeMakesAllScopesGone() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A").andExpect(status().isOk());
        createScope("sc-2", "subj-a", "RESEARCH", "scope-b", "标签B").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-a", "scope-a", "payload-a").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-b", "scope-b", "payload-b").andExpect(status().isOk());
        write("w-3", "subj-a", "RESEARCH", "rec-d", null, "payload-d").andExpect(status().isOk());
        // 先撤回一个子范围，再整体撤回：整体撤回不受子范围已撤回状态限制
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "scope-a").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        // 全部子范围一并 410，不区分子范围状态
        read("subj-a", "RESEARCH", "rec-a")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        read("subj-a", "RESEARCH", "rec-b")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        read("subj-a", "RESEARCH", "rec-d")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        write("w-4", "subj-a", "RESEARCH", "rec-b2", "scope-b", "payload-b2")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    // ---------- 聚合可见性与子范围清单 ----------

    @Test
    void aggregateMarksScopeAvailabilityWithoutFiltering() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A").andExpect(status().isOk());
        createScope("sc-2", "subj-a", "RESEARCH", "scope-b", "标签B").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-a", "scope-a", "payload-a").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-b", "scope-b", "payload-b").andExpect(status().isOk());
        write("w-3", "subj-a", "RESEARCH", "rec-d", null, "payload-d").andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "scope-a").andExpect(status().isOk());

        // 已撤回子范围的记录仍返回并标记不可用，不静默过滤
        aggregate("subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.records.length()").value(3))
                .andExpect(jsonPath("$.records[0].recordKey").value("rec-a"))
                .andExpect(jsonPath("$.records[0].scopeKey").value("scope-a"))
                .andExpect(jsonPath("$.records[0].available").value(false))
                .andExpect(jsonPath("$.records[1].recordKey").value("rec-b"))
                .andExpect(jsonPath("$.records[1].scopeKey").value("scope-b"))
                .andExpect(jsonPath("$.records[1].available").value(true))
                .andExpect(jsonPath("$.records[2].recordKey").value("rec-d"))
                .andExpect(jsonPath("$.records[2].scopeKey").value("default"))
                .andExpect(jsonPath("$.records[2].available").value(true));
    }

    @Test
    void aggregateOnRevokedEpochReturns410() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", null, "payload-1").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        aggregate("subj-a", "RESEARCH", 1)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void aggregateOnMissingEpochReturns404() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        aggregate("subj-a", "RESEARCH", 9)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void scopeListShowsDefaultAndExplicitScopesWithStatus() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A").andExpect(status().isOk());
        createScope("sc-2", "subj-a", "RESEARCH", "scope-b", "标签B").andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "scope-a").andExpect(status().isOk());

        listScopes("subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopes.length()").value(3))
                .andExpect(jsonPath("$.scopes[0].scopeKey").value("default"))
                .andExpect(jsonPath("$.scopes[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.scopes[0].available").value(true))
                .andExpect(jsonPath("$.scopes[1].scopeKey").value("scope-a"))
                .andExpect(jsonPath("$.scopes[1].status").value("REVOKED"))
                .andExpect(jsonPath("$.scopes[1].available").value(false))
                .andExpect(jsonPath("$.scopes[2].scopeKey").value("scope-b"))
                .andExpect(jsonPath("$.scopes[2].status").value("ACTIVE"))
                .andExpect(jsonPath("$.scopes[2].available").value(true));
    }

    @Test
    void scopeListAfterWholeRevokeMarksAllRevoked() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        listScopes("subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopes.length()").value(2))
                .andExpect(jsonPath("$.scopes[0].scopeKey").value("default"))
                .andExpect(jsonPath("$.scopes[0].status").value("REVOKED"))
                .andExpect(jsonPath("$.scopes[0].available").value(false))
                .andExpect(jsonPath("$.scopes[1].scopeKey").value("scope-a"))
                .andExpect(jsonPath("$.scopes[1].status").value("REVOKED"))
                .andExpect(jsonPath("$.scopes[1].available").value(false));
    }

    @Test
    void scopeListOnMissingEpochReturns404() throws Exception {
        listScopes("subj-none", "RESEARCH", 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    // ---------- 幂等边界 ----------

    @Test
    void scopeCreateReplaySameRequestIdReturnsOriginal() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1));
        assertThat(count("SELECT COUNT(*) FROM consent_scope")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'sc-1'")).isEqualTo(1);
    }

    @Test
    void scopeCreateSameRequestIdWithDifferentLabelReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签B")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void failedScopeCreateDoesNotConsumeRequestId() throws Exception {
        // 无授权时创建子范围失败，requestId 不应被占用
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A")
                .andExpect(status().isNotFound());
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A")
                .andExpect(status().isOk());
    }

    @Test
    void writeReplayAfterScopeRevokeCannotBypassScopeState() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "scope-a", "payload-1").andExpect(status().isOk());
        revokeScope("sr-1", "subj-a", "RESEARCH", 1, "scope-a").andExpect(status().isOk());
        // 重放原写入请求（同 requestId 同参数）也必须被拒绝
        write("w-1", "subj-a", "RESEARCH", "rec-1", "scope-a", "payload-1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("SCOPE_REVOKED"));
    }

    @Test
    void writeSameKeyInDifferentScopeReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", null, "payload-1").andExpect(status().isOk());
        // recordKey 同代唯一：同一 recordKey 落入不同子范围视为冲突
        write("w-2", "subj-a", "RESEARCH", "rec-1", "scope-a", "payload-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_SCOPE_CONFLICT"));
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentScopeCreatesSameKeyProduceSingleScope() throws Exception {
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
                        requestId, "subj-a", Purpose.RESEARCH, "scope-a", "标签A")).scopeKey();
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<String> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo("scope-a");
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM consent_scope")).isEqualTo(1);
    }

    @Test
    void concurrentWritesIntoScopeSameRecordKeyProduceSingleRecord() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A").andExpect(status().isOk());
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
                        requestId, "subj-a", Purpose.RESEARCH, "rec-1", "scope-a", "same-payload")).payload();
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<String> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo("same-payload");
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE scope_key = 'scope-a'")).isEqualTo(1);
    }

    @Test
    void concurrentWriteAndScopeRevokeAreResolvedByCommitOrder() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createScope("sc-1", "subj-a", "RESEARCH", "scope-a", "标签A").andExpect(status().isOk());

        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch ready = new CountDownLatch(writers + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            String requestId = "w-race-" + i;
            String recordKey = "rec-race-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    consentService.write(new RecordWriteRequest(
                            requestId, "subj-a", Purpose.RESEARCH, recordKey, "scope-a", "payload-" + recordKey));
                    return "WRITTEN";
                } catch (ApiException e) {
                    return e.getCode();
                }
            });
        }
        tasks.add(() -> {
            ready.countDown();
            start.await();
            consentService.revokeScope(new ScopeRevokeRequest(
                    "sr-race", "subj-a", Purpose.RESEARCH, 1, "scope-a"));
            return "REVOKED";
        });
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int written = 0;
        int rejected = 0;
        for (int i = 0; i < writers; i++) {
            String outcome = futures.get(i).get(30, TimeUnit.SECONDS);
            if ("WRITTEN".equals(outcome)) {
                written++;
            } else {
                // 撤回先提交则写入被拒绝（410），不允许出现其他错误
                assertThat(outcome).isEqualTo("SCOPE_REVOKED");
                rejected++;
            }
        }
        assertThat(futures.get(writers).get(30, TimeUnit.SECONDS)).isEqualTo("REVOKED");
        pool.shutdown();

        // 按提交顺序裁决：成功写入数与库中记录数一致；撤回最终生效
        assertThat(written + rejected).isEqualTo(writers);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE scope_key = 'scope-a'")).isEqualTo(written);
        assertThat(count("SELECT COUNT(*) FROM consent_scope WHERE scope_key = 'scope-a' AND status = 'REVOKED'"))
                .isEqualTo(1);
        // 撤回生效后新的写入立即 410
        write("w-after", "subj-a", "RESEARCH", "rec-after", "scope-a", "payload-after")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("SCOPE_REVOKED"));
    }
}
