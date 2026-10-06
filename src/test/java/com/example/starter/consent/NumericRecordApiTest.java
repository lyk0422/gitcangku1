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

import com.example.starter.consent.dto.DeriveRequest;
import com.example.starter.consent.dto.NumericWriteRequest;
import com.example.starter.consent.dto.SourceRef;

/**
 * 研究数值记录与求和派生 API 集成测试：覆盖主流程、失败分支、闭包核验、幂等与并发边界。
 * 使用 H2 内存库（MODE=MySQL），每个用例前清理场景数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class NumericRecordApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NumericRecordService numericRecordService;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM numeric_record_ancestor");
        jdbc.update("DELETE FROM numeric_record_source");
        jdbc.update("DELETE FROM numeric_record");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
    }

    private ResultActions grant(String requestId, String subjectKey) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/grants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH"}
                        """.formatted(requestId, subjectKey)));
    }

    private ResultActions revoke(String requestId, String subjectKey, int epoch) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH","epoch":%d}
                        """.formatted(requestId, subjectKey, epoch)));
    }

    private ResultActions writeValue(String requestId, String operator, String subjectKey,
                                     String recordKey, long value) throws Exception {
        return mockMvc.perform(post("/api/v1/numeric-records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","operator":"%s","subjectKey":"%s","recordKey":"%s","value":%d}
                        """.formatted(requestId, operator, subjectKey, recordKey, value)));
    }

    private String source(String subjectKey, int epoch, String recordKey) {
        return """
                {"subjectKey":"%s","epoch":%d,"recordKey":"%s"}
                """.formatted(subjectKey, epoch, recordKey);
    }

    private ResultActions derive(String requestId, String operator, String targetSubjectKey,
                                 int targetEpoch, String recordKey, String... sources) throws Exception {
        return mockMvc.perform(post("/api/v1/numeric-records/derivations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","operator":"%s","targetSubjectKey":"%s","targetEpoch":%d,\
                        "recordKey":"%s","sources":[%s]}
                        """.formatted(requestId, operator, targetSubjectKey, targetEpoch,
                        recordKey, String.join(",", sources))));
    }

    private ResultActions readNumeric(String subjectKey, String recordKey) throws Exception {
        return mockMvc.perform(get("/api/v1/numeric-records")
                .param("subjectKey", subjectKey)
                .param("recordKey", recordKey));
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    // ---------- 数值写入主流程 ----------

    @Test
    void writeValueReturnsAckWithoutValueAndReadReturnsValue() throws Exception {
        grant("g-1", "subj-a").andExpect(status().isOk());
        writeValue("w-1", "op-1", "subj-a", "rec-1", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordKey").value("rec-1"))
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.value").doesNotExist());
        readNumeric("subj-a", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("VALUE"))
                .andExpect(jsonPath("$.value").value(2));
    }

    @Test
    void writeValueBoundaryZeroAndLongMax() throws Exception {
        grant("g-1", "subj-a").andExpect(status().isOk());
        writeValue("w-1", "op-1", "subj-a", "rec-zero", 0).andExpect(status().isOk());
        writeValue("w-2", "op-1", "subj-a", "rec-max", Long.MAX_VALUE).andExpect(status().isOk());
        readNumeric("subj-a", "rec-zero").andExpect(jsonPath("$.value").value(0));
        readNumeric("subj-a", "rec-max").andExpect(jsonPath("$.value").value(Long.MAX_VALUE));
    }

    @Test
    void writeNegativeValueReturns400() throws Exception {
        grant("g-1", "subj-a").andExpect(status().isOk());
        writeValue("w-1", "op-1", "subj-a", "rec-1", -1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void writeWithoutGrantReturns404AndAfterRevokeReturns410() throws Exception {
        writeValue("w-1", "op-1", "subj-none", "rec-1", 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
        grant("g-1", "subj-a").andExpect(status().isOk());
        revoke("r-1", "subj-a", 1).andExpect(status().isOk());
        writeValue("w-2", "op-1", "subj-a", "rec-1", 1)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void writeSameKeyTwiceReturns409() throws Exception {
        grant("g-1", "subj-a").andExpect(status().isOk());
        writeValue("w-1", "op-1", "subj-a", "rec-1", 1).andExpect(status().isOk());
        writeValue("w-2", "op-1", "subj-a", "rec-1", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_KEY_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM numeric_record")).isEqualTo(1);
    }

    // ---------- 写入幂等 ----------

    @Test
    void writeReplaySameRequestIdReturnsOriginalAck() throws Exception {
        grant("g-1", "subj-a").andExpect(status().isOk());
        writeValue("w-1", "op-1", "subj-a", "rec-1", 7).andExpect(status().isOk());
        writeValue("w-1", "op-1", "subj-a", "rec-1", 7)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordKey").value("rec-1"))
                .andExpect(jsonPath("$.epoch").value(1));
        assertThat(count("SELECT COUNT(*) FROM numeric_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'w-1'")).isEqualTo(1);
    }

    @Test
    void writeSameRequestIdWithDifferentParamsReturns409() throws Exception {
        grant("g-1", "subj-a").andExpect(status().isOk());
        writeValue("w-1", "op-1", "subj-a", "rec-1", 7).andExpect(status().isOk());
        writeValue("w-1", "op-1", "subj-a", "rec-1", 8)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        writeValue("w-1", "op-2", "subj-a", "rec-1", 7)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void writeReplayAfterRevokeReturns410() throws Exception {
        grant("g-1", "subj-a").andExpect(status().isOk());
        writeValue("w-1", "op-1", "subj-a", "rec-1", 7).andExpect(status().isOk());
        revoke("r-1", "subj-a", 1).andExpect(status().isOk());
        writeValue("w-1", "op-1", "subj-a", "rec-1", 7)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void failedWriteDoesNotConsumeRequestIdOrKey() throws Exception {
        writeValue("w-1", "op-1", "subj-a", "rec-1", 7).andExpect(status().isNotFound());
        grant("g-1", "subj-a").andExpect(status().isOk());
        writeValue("w-1", "op-1", "subj-a", "rec-1", 7).andExpect(status().isOk());
    }

    // ---------- 派生主流程与题干合成场景 ----------

    /**
     * 题干合成场景：A 代 1 值 2 与 B 代 1 值 3 派生 C 值 5，再由 C 和 D 值 4 派生 E 值 9。
     */
    private void setupSyntheticScenario() throws Exception {
        grant("g-a", "subj-a").andExpect(status().isOk());
        grant("g-b", "subj-b").andExpect(status().isOk());
        grant("g-c", "subj-c").andExpect(status().isOk());
        grant("g-d", "subj-d").andExpect(status().isOk());
        grant("g-e", "subj-e").andExpect(status().isOk());
        writeValue("w-a", "op-1", "subj-a", "rec-a", 2).andExpect(status().isOk());
        writeValue("w-b", "op-1", "subj-b", "rec-b", 3).andExpect(status().isOk());
        writeValue("w-d", "op-1", "subj-d", "rec-d", 4).andExpect(status().isOk());
        derive("d-c", "op-1", "subj-c", 1, "rec-c",
                source("subj-a", 1, "rec-a"), source("subj-b", 1, "rec-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordKey").value("rec-c"))
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.value").doesNotExist());
        derive("d-e", "op-1", "subj-e", 1, "rec-e",
                source("subj-c", 1, "rec-c"), source("subj-d", 1, "rec-d"))
                .andExpect(status().isOk());
    }

    @Test
    void deriveSumsExactlyAndReadReturnsDerivedValues() throws Exception {
        setupSyntheticScenario();
        readNumeric("subj-c", "rec-c")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("DERIVED"))
                .andExpect(jsonPath("$.value").value(5));
        readNumeric("subj-e", "rec-e")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("DERIVED"))
                .andExpect(jsonPath("$.value").value(9));
        // 直接来源与完整祖先代次集合已固化保存
        assertThat(count("SELECT COUNT(*) FROM numeric_record_source"
                + " WHERE target_subject_key = 'subj-c'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM numeric_record_ancestor"
                + " WHERE target_subject_key = 'subj-e' AND target_record_key = 'rec-e'")).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM numeric_record_ancestor"
                + " WHERE target_subject_key = 'subj-e' AND ancestor_subject_key = 'subj-a'"
                + " AND ancestor_epoch = 1")).isEqualTo(1);
    }

    @Test
    void revokeAncestorMakesDerivedReadAndReplay410() throws Exception {
        setupSyntheticScenario();
        revoke("r-a", "subj-a", 1).andExpect(status().isOk());
        // 任一祖先代失效：派生读取整体 410，即使目标主体授权仍有效
        readNumeric("subj-c", "rec-c")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        readNumeric("subj-e", "rec-e")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        // 含值成功重放同样 410
        derive("d-c", "op-1", "subj-c", 1, "rec-c",
                source("subj-a", 1, "rec-a"), source("subj-b", 1, "rec-b"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void regrantWithSameNameRecordDoesNotReviveOldDerivation() throws Exception {
        setupSyntheticScenario();
        revoke("r-a", "subj-a", 1).andExpect(status().isOk());
        // 新授权同名记录不能恢复旧派生：来源按创建时主体＋代次＋键钉住
        grant("g-a2", "subj-a").andExpect(status().isOk());
        writeValue("w-a2", "op-1", "subj-a", "rec-a", 2).andExpect(status().isOk());
        readNumeric("subj-c", "rec-c").andExpect(status().isGone());
        readNumeric("subj-e", "rec-e").andExpect(status().isGone());
        // 新代记录本身可正常读取
        readNumeric("subj-a", "rec-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.value").value(2));
    }

    // ---------- 派生失败分支 ----------

    @Test
    void deriveWithUnknownOrStaleOrRevokedSourceReturns404Or410() throws Exception {
        grant("g-a", "subj-a").andExpect(status().isOk());
        grant("g-b", "subj-b").andExpect(status().isOk());
        grant("g-c", "subj-c").andExpect(status().isOk());
        writeValue("w-a", "op-1", "subj-a", "rec-a", 2).andExpect(status().isOk());
        writeValue("w-b", "op-1", "subj-b", "rec-b", 3).andExpect(status().isOk());
        // 来源主体未知 → 404
        derive("d-1", "op-1", "subj-c", 1, "rec-c",
                source("subj-none", 1, "rec-x"), source("subj-b", 1, "rec-b"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
        // 来源记录不存在 → 404
        derive("d-2", "op-1", "subj-c", 1, "rec-c",
                source("subj-a", 1, "rec-none"), source("subj-b", 1, "rec-b"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
        // 来源代次已撤回 → 410
        revoke("r-a", "subj-a", 1).andExpect(status().isOk());
        derive("d-3", "op-1", "subj-c", 1, "rec-c",
                source("subj-a", 1, "rec-a"), source("subj-b", 1, "rec-b"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        // 重新授权后引用旧代 → 410（旧代失效）
        grant("g-a2", "subj-a").andExpect(status().isOk());
        derive("d-4", "op-1", "subj-c", 1, "rec-c",
                source("subj-a", 1, "rec-a"), source("subj-b", 1, "rec-b"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        // 失败请求均未占用目标键
        assertThat(count("SELECT COUNT(*) FROM numeric_record WHERE subject_key = 'subj-c'")).isEqualTo(0);
    }

    @Test
    void deriveWithUnknownOrStaleTargetEpochReturns404Or410() throws Exception {
        grant("g-a", "subj-a").andExpect(status().isOk());
        grant("g-b", "subj-b").andExpect(status().isOk());
        grant("g-c", "subj-c").andExpect(status().isOk());
        writeValue("w-a", "op-1", "subj-a", "rec-a", 2).andExpect(status().isOk());
        writeValue("w-b", "op-1", "subj-b", "rec-b", 3).andExpect(status().isOk());
        // 目标代次未知 → 404
        derive("d-1", "op-1", "subj-c", 9, "rec-c",
                source("subj-a", 1, "rec-a"), source("subj-b", 1, "rec-b"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
        // 目标代次已撤回 → 410
        revoke("r-c", "subj-c", 1).andExpect(status().isOk());
        derive("d-2", "op-1", "subj-c", 1, "rec-c",
                source("subj-a", 1, "rec-a"), source("subj-b", 1, "rec-b"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void deriveWithDuplicateSourcesReturns400() throws Exception {
        grant("g-a", "subj-a").andExpect(status().isOk());
        grant("g-c", "subj-c").andExpect(status().isOk());
        writeValue("w-a", "op-1", "subj-a", "rec-a", 2).andExpect(status().isOk());
        derive("d-1", "op-1", "subj-c", 1, "rec-c",
                source("subj-a", 1, "rec-a"), source("subj-a", 1, "rec-a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SOURCE"));
    }

    @Test
    void deriveWithTooFewOrTooManySourcesReturns400() throws Exception {
        grant("g-c", "subj-c").andExpect(status().isOk());
        derive("d-1", "op-1", "subj-c", 1, "rec-c", source("subj-a", 1, "rec-a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        derive("d-2", "op-1", "subj-c", 1, "rec-c",
                source("s1", 1, "k"), source("s2", 1, "k"), source("s3", 1, "k"),
                source("s4", 1, "k"), source("s5", 1, "k"), source("s6", 1, "k"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void deriveTargetKeyExistsReturns409() throws Exception {
        setupSyntheticScenario();
        derive("d-x", "op-1", "subj-c", 1, "rec-c",
                source("subj-a", 1, "rec-a"), source("subj-b", 1, "rec-b"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_KEY_CONFLICT"));
    }

    @Test
    void deriveSumOverflowReturns400AndDoesNotOccupyKey() throws Exception {
        grant("g-a", "subj-a").andExpect(status().isOk());
        grant("g-b", "subj-b").andExpect(status().isOk());
        grant("g-c", "subj-c").andExpect(status().isOk());
        writeValue("w-a", "op-1", "subj-a", "rec-a", Long.MAX_VALUE).andExpect(status().isOk());
        writeValue("w-b", "op-1", "subj-b", "rec-b", Long.MAX_VALUE).andExpect(status().isOk());
        derive("d-1", "op-1", "subj-c", 1, "rec-c",
                source("subj-a", 1, "rec-a"), source("subj-b", 1, "rec-b"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUM_OVERFLOW"));
        // 失败不占目标键：修正来源后同键可成功
        writeValue("w-b2", "op-1", "subj-b", "rec-b2", 0).andExpect(status().isOk());
        derive("d-2", "op-1", "subj-c", 1, "rec-c",
                source("subj-a", 1, "rec-a"), source("subj-b", 1, "rec-b2"))
                .andExpect(status().isOk());
        readNumeric("subj-c", "rec-c").andExpect(jsonPath("$.value").value(Long.MAX_VALUE));
    }

    @Test
    void deriveDepthBeyondThreeReturns422AndDoesNotOccupyKey() throws Exception {
        grant("g-s", "subj-s").andExpect(status().isOk());
        writeValue("w-1", "op-1", "subj-s", "v1", 1).andExpect(status().isOk());
        writeValue("w-2", "op-1", "subj-s", "v2", 2).andExpect(status().isOk());
        writeValue("w-3", "op-1", "subj-s", "v3", 3).andExpect(status().isOk());
        writeValue("w-4", "op-1", "subj-s", "v4", 4).andExpect(status().isOk());
        derive("d-1", "op-1", "subj-s", 1, "dep1",
                source("subj-s", 1, "v1"), source("subj-s", 1, "v2")).andExpect(status().isOk());
        derive("d-2", "op-1", "subj-s", 1, "dep2",
                source("subj-s", 1, "dep1"), source("subj-s", 1, "v3")).andExpect(status().isOk());
        derive("d-3", "op-1", "subj-s", 1, "dep3",
                source("subj-s", 1, "dep2"), source("subj-s", 1, "v4")).andExpect(status().isOk());
        readNumeric("subj-s", "dep3").andExpect(jsonPath("$.value").value(10));
        // 第 4 层派生超限 → 422，不生成记录、不占目标键
        derive("d-4", "op-1", "subj-s", 1, "dep4",
                source("subj-s", 1, "dep3"), source("subj-s", 1, "v4"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DERIVATION_DEPTH_EXCEEDED"));
        assertThat(count("SELECT COUNT(*) FROM numeric_record WHERE record_key = 'dep4'")).isEqualTo(0);
        derive("d-5", "op-1", "subj-s", 1, "dep4",
                source("subj-s", 1, "v1"), source("subj-s", 1, "v4")).andExpect(status().isOk());
    }

    // ---------- 派生幂等与读取分支 ----------

    @Test
    void deriveReplaySameRequestIdReturnsOriginalAck() throws Exception {
        setupSyntheticScenario();
        derive("d-c", "op-1", "subj-c", 1, "rec-c",
                source("subj-a", 1, "rec-a"), source("subj-b", 1, "rec-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordKey").value("rec-c"))
                .andExpect(jsonPath("$.epoch").value(1));
        assertThat(count("SELECT COUNT(*) FROM numeric_record WHERE subject_key = 'subj-c'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'd-c'")).isEqualTo(1);
    }

    @Test
    void deriveSameRequestIdWithDifferentParamsReturns409() throws Exception {
        setupSyntheticScenario();
        derive("d-c", "op-1", "subj-c", 1, "rec-c",
                source("subj-b", 1, "rec-b"), source("subj-d", 1, "rec-d"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void failedDeriveDoesNotConsumeRequestId() throws Exception {
        grant("g-a", "subj-a").andExpect(status().isOk());
        grant("g-c", "subj-c").andExpect(status().isOk());
        writeValue("w-a", "op-1", "subj-a", "rec-a", 2).andExpect(status().isOk());
        // 来源缺失失败 → requestId 不应被占用
        derive("d-1", "op-1", "subj-c", 1, "rec-c",
                source("subj-a", 1, "rec-a"), source("subj-none", 1, "rec-x"))
                .andExpect(status().isNotFound());
        grant("g-b", "subj-b").andExpect(status().isOk());
        writeValue("w-b", "op-1", "subj-b", "rec-b", 3).andExpect(status().isOk());
        derive("d-1", "op-1", "subj-c", 1, "rec-c",
                source("subj-a", 1, "rec-a"), source("subj-b", 1, "rec-b"))
                .andExpect(status().isOk());
    }

    @Test
    void readWithoutGrantOrMissingRecordReturns404() throws Exception {
        readNumeric("subj-none", "rec-1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
        grant("g-1", "subj-a").andExpect(status().isOk());
        readNumeric("subj-a", "rec-none")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
    }

    @Test
    void readAfterOwnRevokeReturns410() throws Exception {
        grant("g-1", "subj-a").andExpect(status().isOk());
        writeValue("w-1", "op-1", "subj-a", "rec-1", 5).andExpect(status().isOk());
        revoke("r-1", "subj-a", 1).andExpect(status().isOk());
        readNumeric("subj-a", "rec-1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentValueWritesSameKeyProduceSingleRecord() throws Exception {
        grant("g-1", "subj-n").andExpect(status().isOk());
        List<Callable<String>> tasks = new ArrayList<>();
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            String requestId = "wn-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    numericRecordService.writeValue(new NumericWriteRequest(
                            requestId, "op-1", "subj-n", "rec-1", 7L));
                    return "OK";
                } catch (ApiException e) {
                    return e.getCode();
                }
            });
        }
        List<String> results = runConcurrently(tasks, ready, start);
        assertThat(results.stream().filter("OK"::equals).count()).isEqualTo(1);
        assertThat(results.stream().filter("RECORD_KEY_CONFLICT"::equals).count()).isEqualTo(threads - 1);
        assertThat(count("SELECT COUNT(*) FROM numeric_record")).isEqualTo(1);
    }

    @Test
    void concurrentDerivesSameTargetKeyProduceSingleRecord() throws Exception {
        grant("g-a", "subj-a").andExpect(status().isOk());
        grant("g-b", "subj-b").andExpect(status().isOk());
        grant("g-t", "subj-t").andExpect(status().isOk());
        writeValue("w-a", "op-1", "subj-a", "rec-a", 2).andExpect(status().isOk());
        writeValue("w-b", "op-1", "subj-b", "rec-b", 3).andExpect(status().isOk());
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "dn-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    numericRecordService.derive(new DeriveRequest(requestId, "op-1", "subj-t", 1, "rec-t",
                            List.of(new SourceRef("subj-a", 1, "rec-a"), new SourceRef("subj-b", 1, "rec-b"))));
                    return "OK";
                } catch (ApiException e) {
                    return e.getCode();
                }
            });
        }
        List<String> results = runConcurrently(tasks, ready, start);
        assertThat(results.stream().filter("OK"::equals).count()).isEqualTo(1);
        assertThat(results.stream().filter("RECORD_KEY_CONFLICT"::equals).count()).isEqualTo(threads - 1);
        assertThat(count("SELECT COUNT(*) FROM numeric_record WHERE subject_key = 'subj-t'")).isEqualTo(1);
        readNumeric("subj-t", "rec-t").andExpect(jsonPath("$.value").value(5));
    }

    private List<String> runConcurrently(List<Callable<String>> tasks, CountDownLatch ready,
                                         CountDownLatch start) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        return results;
    }
}
