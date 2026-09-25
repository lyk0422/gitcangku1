package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.example.starter.consent.dto.DelegateCreateRequest;
import com.example.starter.consent.dto.DelegateQueryRequest;
import com.example.starter.consent.dto.DelegateRenewItem;
import com.example.starter.consent.dto.DelegateRenewRequest;
import com.example.starter.consent.dto.DelegateRevokeRequest;

/**
 * 授权代理委托 API 集成测试：覆盖委托作用域、授权代次、批次查询、快照边界与并发幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DelegateApiTest {

    /** 测试基准时刻（UTC），所有有效期相对它构造。 */
    static final Instant BASE = Instant.parse("2026-09-26T00:00:00Z");
    static final Instant VALID_FROM = BASE.minus(Duration.ofHours(1));
    static final Instant VALID_TO = BASE.plus(Duration.ofHours(1));
    static final Instant EXPIRED_FROM = BASE.minus(Duration.ofHours(2));
    static final Instant EXPIRED_TO = BASE.minus(Duration.ofHours(1));
    static final Instant FUTURE_FROM = BASE.plus(Duration.ofHours(1));
    static final Instant FUTURE_TO = BASE.plus(Duration.ofHours(2));
    static final Instant RENEWED_FROM = BASE.plus(Duration.ofHours(2));
    static final Instant RENEWED_TO = BASE.plus(Duration.ofHours(3));

    @TestConfiguration
    static class ClockTestConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return MutableClock.utc(BASE);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DelegateService delegateService;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM delegate_query_snapshot_subject");
        jdbc.update("DELETE FROM delegate_query_snapshot");
        jdbc.update("DELETE FROM consent_delegate_version");
        jdbc.update("DELETE FROM consent_delegate");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
        clock.setInstant(BASE);
    }

    // ---------- 请求辅助 ----------

    private ResultActions grant(String requestId, String subjectKey, String purpose) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/grants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s"}
                        """.formatted(requestId, subjectKey, purpose)));
    }

    private ResultActions writeRecord(String requestId, String subjectKey, String purpose,
                                      String recordKey, String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","recordKey":"%s","payload":"%s"}
                        """.formatted(requestId, subjectKey, purpose, recordKey, payload)));
    }

    private ResultActions revokeGrant(String requestId, String subjectKey, String purpose,
                                      int epoch) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d}
                        """.formatted(requestId, subjectKey, purpose, epoch)));
    }

    private ResultActions createDelegate(String delegateKey, String subjectKey, String agentKey,
                                         String purposesJson, Instant validFrom, Instant validTo)
            throws Exception {
        return mockMvc.perform(post("/api/v1/delegates")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"delegateKey":"%s","subjectKey":"%s","agentKey":"%s","purposes":%s,
                         "validFrom":"%s","validTo":"%s"}
                        """.formatted(delegateKey, subjectKey, agentKey, purposesJson,
                        validFrom, validTo)));
    }

    private ResultActions createDelegate(String delegateKey, String subjectKey, String agentKey,
                                         String purposesJson) throws Exception {
        return createDelegate(delegateKey, subjectKey, agentKey, purposesJson, VALID_FROM, VALID_TO);
    }

    private ResultActions renew(String requestId, String itemsJson) throws Exception {
        return mockMvc.perform(post("/api/v1/delegates/renewals")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","items":%s}
                        """.formatted(requestId, itemsJson)));
    }

    private ResultActions revokeDelegate(String requestId, String delegateKey) throws Exception {
        return mockMvc.perform(post("/api/v1/delegates/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","delegateKey":"%s"}
                        """.formatted(requestId, delegateKey)));
    }

    private ResultActions query(String requestId, String agentKey, String subjectsJson,
                                String purposesJson) throws Exception {
        return mockMvc.perform(post("/api/v1/delegate-queries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","agentKey":"%s","subjects":%s,"purposes":%s}
                        """.formatted(requestId, agentKey, subjectsJson, purposesJson)));
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    // ---------- 委托创建 ----------

    @Test
    void createDelegateNormalizesPurposesAndBindsEpochs() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-a", "PERSONALIZATION").andExpect(status().isOk());

        // 用途集合去重并按用途名排序；代次按用途绑定
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\",\"RESEARCH\",\"PERSONALIZATION\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delegateKey").value("d-1"))
                .andExpect(jsonPath("$.delegateVersion").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.purposes[0]").value("PERSONALIZATION"))
                .andExpect(jsonPath("$.purposes[1]").value("RESEARCH"))
                .andExpect(jsonPath("$.purposes.length()").value(2))
                .andExpect(jsonPath("$.epochs.PERSONALIZATION").value(1))
                .andExpect(jsonPath("$.epochs.RESEARCH").value(1))
                .andExpect(jsonPath("$.validFrom").value(VALID_FROM.toString()))
                .andExpect(jsonPath("$.validTo").value(VALID_TO.toString()));
    }

    @Test
    void createDelegateWithEmptyPurposesReturns422() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[]")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DELEGATE_PURPOSES_EMPTY"));
    }

    @Test
    void createDelegateForSelfReturns422() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "subj-a", "[\"RESEARCH\"]")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DELEGATE_SELF_DELEGATION"));
    }

    @Test
    void createDelegateWithInvalidWindowReturns422() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]", VALID_TO, VALID_TO)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DELEGATE_INVALID_WINDOW"));
        createDelegate("d-2", "subj-a", "agent-x", "[\"RESEARCH\"]", VALID_TO, VALID_FROM)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DELEGATE_INVALID_WINDOW"));
    }

    @Test
    void createDelegateWithoutGrantReturns404() throws Exception {
        createDelegate("d-1", "subj-none", "agent-x", "[\"RESEARCH\"]")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void createDelegateWithRevokedConsentReturns410() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        revokeGrant("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void delegateKeyReplayReturnsOriginalResult() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delegateVersion").value(1));
        assertThat(count("SELECT COUNT(*) FROM consent_delegate")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_delegate_version")).isEqualTo(1);
    }

    @Test
    void delegateKeyWithDifferentParamsReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-a", "PERSONALIZATION").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"PERSONALIZATION\"]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELEGATE_KEY_CONFLICT"));
    }

    @Test
    void failedDelegateCreationDoesNotConsumeKey() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[]")
                .andExpect(status().isUnprocessableEntity());
        // 失败不占键：同一 delegateKey 可再次使用
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delegateVersion").value(1));
    }

    // ---------- 代理批量查询 ----------

    @Test
    void batchQueryReturnsDataAndPersistsSnapshot() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-b", "RESEARCH").andExpect(status().isOk());
        writeRecord("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a").andExpect(status().isOk());
        writeRecord("w-2", "subj-b", "RESEARCH", "rec-1", "payload-b").andExpect(status().isOk());
        createDelegate("d-a", "subj-a", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());
        createDelegate("d-b", "subj-b", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());

        query("q-1", "agent-x", "[\"subj-b\",\"subj-a\"]", "[\"RESEARCH\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryId").value("q-1"))
                .andExpect(jsonPath("$.subjects[0].subjectKey").value("subj-a"))
                .andExpect(jsonPath("$.subjects[0].delegateKey").value("d-a"))
                .andExpect(jsonPath("$.subjects[0].delegateVersion").value(1))
                .andExpect(jsonPath("$.subjects[0].epochs.RESEARCH").value(1))
                .andExpect(jsonPath("$.subjects[0].records[0].payload").value("payload-a"))
                .andExpect(jsonPath("$.subjects[1].subjectKey").value("subj-b"))
                .andExpect(jsonPath("$.subjects[1].records[0].payload").value("payload-b"));

        // 快照固化委托与授权版本
        mockMvc.perform(get("/api/v1/delegate-queries/q-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjects[0].subjectKey").value("subj-a"))
                .andExpect(jsonPath("$.subjects[0].delegateVersion").value(1))
                .andExpect(jsonPath("$.subjects[0].epochs.RESEARCH").value(1));
    }

    @Test
    void batchQueryWithMissingDelegateReturns403WithoutData() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-b", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-a", "subj-a", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());

        query("q-1", "agent-x", "[\"subj-a\",\"subj-b\"]", "[\"RESEARCH\"]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BATCH_QUERY_DENIED"))
                .andExpect(jsonPath("$.reasons[0].subjectKey").value("subj-b"))
                .andExpect(jsonPath("$.reasons[0].code").value("DELEGATE_NOT_FOUND"))
                .andExpect(jsonPath("$.subjects").doesNotExist());
        // 失败批次不留快照、不占 requestId
        assertThat(count("SELECT COUNT(*) FROM delegate_query_snapshot")).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'q-1'")).isZero();
        mockMvc.perform(get("/api/v1/delegate-queries/q-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUERY_NOT_FOUND"));
    }

    @Test
    void batchQueryWithExpiredDelegateReturns403() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]", EXPIRED_FROM, EXPIRED_TO)
                .andExpect(status().isOk());
        query("q-1", "agent-x", "[\"subj-a\"]", "[\"RESEARCH\"]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reasons[0].code").value("DELEGATE_EXPIRED"));
    }

    @Test
    void batchQueryWithNotYetValidDelegateReturns403() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]", FUTURE_FROM, FUTURE_TO)
                .andExpect(status().isOk());
        query("q-1", "agent-x", "[\"subj-a\"]", "[\"RESEARCH\"]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reasons[0].code").value("DELEGATE_NOT_YET_VALID"));
    }

    @Test
    void batchQueryWithRevokedDelegateReturns403() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());
        revokeDelegate("dr-1", "d-1").andExpect(status().isOk());
        query("q-1", "agent-x", "[\"subj-a\"]", "[\"RESEARCH\"]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reasons[0].code").value("DELEGATE_REVOKED"));
    }

    @Test
    void batchQueryWithUncoveredPurposeReturns403() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-a", "PERSONALIZATION").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());
        query("q-1", "agent-x", "[\"subj-a\"]", "[\"RESEARCH\",\"PERSONALIZATION\"]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reasons[0].code").value("DELEGATE_PURPOSE_NOT_COVERED"));
    }

    @Test
    void batchQueryAfterEpochMigrationReturns403StaleDelegate() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());
        // 主体迁移用途代次：旧代次委托不能用于新代次
        revokeGrant("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());
        query("q-1", "agent-x", "[\"subj-a\"]", "[\"RESEARCH\"]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reasons[0].code").value("DELEGATE_EPOCH_STALE"));
    }

    @Test
    void batchQueryDenialReasonsAreStablyOrderedBySubject() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-b", "RESEARCH").andExpect(status().isOk());
        // subj-a：委托过期；subj-b：无委托；subj-c：无授权
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]", EXPIRED_FROM, EXPIRED_TO)
                .andExpect(status().isOk());
        query("q-1", "agent-x", "[\"subj-c\",\"subj-b\",\"subj-a\"]", "[\"RESEARCH\"]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reasons[0].subjectKey").value("subj-a"))
                .andExpect(jsonPath("$.reasons[0].code").value("DELEGATE_EXPIRED"))
                .andExpect(jsonPath("$.reasons[1].subjectKey").value("subj-b"))
                .andExpect(jsonPath("$.reasons[1].code").value("DELEGATE_NOT_FOUND"))
                .andExpect(jsonPath("$.reasons[2].subjectKey").value("subj-c"))
                .andExpect(jsonPath("$.reasons[2].code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void failedBatchQueryDoesNotConsumeRequestId() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        query("q-1", "agent-x", "[\"subj-a\"]", "[\"RESEARCH\"]")
                .andExpect(status().isForbidden());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());
        query("q-1", "agent-x", "[\"subj-a\"]", "[\"RESEARCH\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryId").value("q-1"));
    }

    // ---------- 撤销与快照边界 ----------

    @Test
    void revokeDelegateAffectsOnlySubsequentQueries() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeRecord("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());
        query("q-1", "agent-x", "[\"subj-a\"]", "[\"RESEARCH\"]").andExpect(status().isOk());

        revokeDelegate("dr-1", "d-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        // 撤销只影响后续查询
        query("q-2", "agent-x", "[\"subj-a\"]", "[\"RESEARCH\"]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reasons[0].code").value("DELEGATE_REVOKED"));
        // 已生成查询快照固化委托和授权版本：重放仍返回原结果
        query("q-1", "agent-x", "[\"subj-a\"]", "[\"RESEARCH\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjects[0].delegateVersion").value(1))
                .andExpect(jsonPath("$.subjects[0].records[0].payload").value("payload-a"));
        mockMvc.perform(get("/api/v1/delegate-queries/q-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjects[0].delegateVersion").value(1));
    }

    @Test
    void snapshotFreezesDelegateAndGrantVersionsAcrossRenewAndRevoke() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());
        query("q-1", "agent-x", "[\"subj-a\"]", "[\"RESEARCH\"]").andExpect(status().isOk());

        renew("rn-1", """
                [{"delegateKey":"d-1","expectedVersion":1,"validFrom":"%s","validTo":"%s"}]
                """.formatted(RENEWED_FROM, RENEWED_TO)).andExpect(status().isOk());
        revokeDelegate("dr-1", "d-1").andExpect(status().isOk());

        // 快照仍指向固化的委托版本 1 与授权代次 1
        mockMvc.perform(get("/api/v1/delegate-queries/q-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjects[0].delegateKey").value("d-1"))
                .andExpect(jsonPath("$.subjects[0].delegateVersion").value(1))
                .andExpect(jsonPath("$.subjects[0].epochs.RESEARCH").value(1));
    }

    @Test
    void revokeDelegateReplayReturnsOriginalResult() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());
        revokeDelegate("dr-1", "d-1").andExpect(status().isOk());
        revokeDelegate("dr-1", "d-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'dr-1'")).isEqualTo(1);
    }

    @Test
    void revokeAlreadyRevokedDelegateWithNewRequestIdReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());
        revokeDelegate("dr-1", "d-1").andExpect(status().isOk());
        revokeDelegate("dr-2", "d-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELEGATE_ALREADY_REVOKED"));
    }

    @Test
    void revokeNonexistentDelegateReturns404() throws Exception {
        revokeDelegate("dr-1", "d-none")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DELEGATE_NOT_FOUND"));
    }

    // ---------- 批量续签 ----------

    @Test
    void renewBumpsVersionAndExtendsValidity() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());

        renew("rn-1", """
                [{"delegateKey":"d-1","expectedVersion":1,"validFrom":"%s","validTo":"%s"}]
                """.formatted(RENEWED_FROM, RENEWED_TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renewed[0].delegateKey").value("d-1"))
                .andExpect(jsonPath("$.renewed[0].delegateVersion").value(2))
                .andExpect(jsonPath("$.renewed[0].validFrom").value(RENEWED_FROM.toString()));

        // 推进时钟越过旧窗口：旧版本已过期，新版本生效
        clock.setInstant(RENEWED_FROM.plus(Duration.ofMinutes(30)));
        query("q-1", "agent-x", "[\"subj-a\"]", "[\"RESEARCH\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjects[0].delegateVersion").value(2));
    }

    @Test
    void renewWithVersionConflictAbortsWholeBatch() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-b", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());
        createDelegate("d-2", "subj-b", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());

        renew("rn-1", """
                [{"delegateKey":"d-1","expectedVersion":1,"validFrom":"%1$s","validTo":"%2$s"},
                 {"delegateKey":"d-2","expectedVersion":9,"validFrom":"%1$s","validTo":"%2$s"}]
                """.formatted(RENEWED_FROM, RENEWED_TO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELEGATE_VERSION_CONFLICT"));

        // 整批不生效：d-1 版本未推进，无版本行与幂等记录残留
        assertThat(count("SELECT COUNT(*) FROM consent_delegate WHERE delegate_key = 'd-1'"
                + " AND current_version = 1")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_delegate_version WHERE delegate_version = 2")).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'rn-1'")).isZero();
    }

    @Test
    void renewRevokedDelegateReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());
        revokeDelegate("dr-1", "d-1").andExpect(status().isOk());
        renew("rn-1", """
                [{"delegateKey":"d-1","expectedVersion":1,"validFrom":"%s","validTo":"%s"}]
                """.formatted(RENEWED_FROM, RENEWED_TO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELEGATE_ALREADY_REVOKED"));
    }

    @Test
    void renewReplaySameRequestIdReturnsOriginalWithoutNewVersion() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());
        String items = """
                [{"delegateKey":"d-1","expectedVersion":1,"validFrom":"%s","validTo":"%s"}]
                """.formatted(RENEWED_FROM, RENEWED_TO);
        renew("rn-1", items).andExpect(status().isOk());
        renew("rn-1", items)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renewed[0].delegateVersion").value(2));
        assertThat(count("SELECT COUNT(*) FROM consent_delegate WHERE delegate_key = 'd-1'"
                + " AND current_version = 2")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_delegate_version WHERE delegate_key = 'd-1'"))
                .isEqualTo(2);
    }

    @Test
    void renewNonexistentDelegateReturns404() throws Exception {
        renew("rn-1", """
                [{"delegateKey":"d-none","expectedVersion":1,"validFrom":"%s","validTo":"%s"}]
                """.formatted(RENEWED_FROM, RENEWED_TO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DELEGATE_NOT_FOUND"));
    }

    @Test
    void renewWithDuplicateDelegateKeyInBatchReturns422() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());
        renew("rn-1", """
                [{"delegateKey":"d-1","expectedVersion":1,"validFrom":"%1$s","validTo":"%2$s"},
                 {"delegateKey":"d-1","expectedVersion":1,"validFrom":"%1$s","validTo":"%2$s"}]
                """.formatted(RENEWED_FROM, RENEWED_TO))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DELEGATE_DUPLICATE_IN_BATCH"));
    }

    // ---------- 委托历史与快照查询 ----------

    @Test
    void delegateHistoryShowsAllVersionsAndStatus() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());
        renew("rn-1", """
                [{"delegateKey":"d-1","expectedVersion":1,"validFrom":"%s","validTo":"%s"}]
                """.formatted(RENEWED_FROM, RENEWED_TO)).andExpect(status().isOk());
        revokeDelegate("dr-1", "d-1").andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/delegates/d-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].delegateVersion").value(1))
                .andExpect(jsonPath("$.versions[0].validTo").value(VALID_TO.toString()))
                .andExpect(jsonPath("$.versions[1].delegateVersion").value(2))
                .andExpect(jsonPath("$.versions[1].validFrom").value(RENEWED_FROM.toString()));
    }

    @Test
    void delegateHistoryNotFoundReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/delegates/d-none"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DELEGATE_NOT_FOUND"));
    }

    @Test
    void snapshotNotFoundReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/delegate-queries/q-none"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUERY_NOT_FOUND"));
    }

    // ---------- 并发与幂等边界 ----------

    @Test
    void concurrentDelegateCreationWithSameKeyProducesSingleDelegate() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return delegateService.create(new DelegateCreateRequest("d-con", "subj-a", "agent-x",
                        List.of(Purpose.RESEARCH), VALID_FROM, VALID_TO)).delegateVersion();
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<Integer> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM consent_delegate")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_delegate_version")).isEqualTo(1);
    }

    @Test
    void concurrentRenewWithSameExpectedVersionAllowsSingleWinner() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "rn-concurrent-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    delegateService.renew(new DelegateRenewRequest(requestId,
                            List.of(new DelegateRenewItem("d-1", 1, RENEWED_FROM, RENEWED_TO))));
                    return "OK";
                } catch (ApiException conflict) {
                    return conflict.getCode();
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
        int conflicts = 0;
        for (Future<String> future : futures) {
            String outcome = future.get(20, TimeUnit.SECONDS);
            if ("OK".equals(outcome)) {
                succeeded++;
            } else {
                assertThat(outcome).isEqualTo("DELEGATE_VERSION_CONFLICT");
                conflicts++;
            }
        }
        pool.shutdown();
        assertThat(succeeded).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        assertThat(count("SELECT COUNT(*) FROM consent_delegate WHERE delegate_key = 'd-1'"
                + " AND current_version = 2")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_delegate_version WHERE delegate_key = 'd-1'"
                + " AND delegate_version = 2")).isEqualTo(1);
    }

    @Test
    void concurrentQueriesAndRevokeAreSerializedConsistently() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        createDelegate("d-1", "subj-a", "agent-x", "[\"RESEARCH\"]").andExpect(status().isOk());
        int queryThreads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(queryThreads + 1);
        CountDownLatch ready = new CountDownLatch(queryThreads + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < queryThreads; i++) {
            String requestId = "q-concurrent-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    delegateService.query(new DelegateQueryRequest(requestId, "agent-x",
                            List.of("subj-a"), List.of(Purpose.RESEARCH)));
                    return "OK";
                } catch (BatchQueryDeniedException denied) {
                    return denied.getReasons().get(0).code();
                }
            });
        }
        tasks.add(() -> {
            ready.countDown();
            start.await();
            delegateService.revoke(new DelegateRevokeRequest("dr-concurrent", "d-1"));
            return "REVOKED";
        });
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int succeeded = 0;
        int denied = 0;
        for (Future<String> future : futures) {
            String outcome = future.get(20, TimeUnit.SECONDS);
            if ("OK".equals(outcome)) {
                succeeded++;
            } else if ("DELEGATE_REVOKED".equals(outcome)) {
                denied++;
            }
        }
        pool.shutdown();
        // 串行裁决：撤销前的查询成功并留快照，撤销后的查询失败且不留快照
        assertThat(succeeded + denied).isEqualTo(queryThreads);
        assertThat(count("SELECT COUNT(*) FROM delegate_query_snapshot")).isEqualTo(succeeded);
        assertThat(count("SELECT COUNT(*) FROM consent_delegate WHERE status = 'REVOKED'")).isEqualTo(1);
    }
}
