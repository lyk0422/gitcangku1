package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 研究数值记录 API 集成测试：覆盖写入主流程、边界值、失败分支与幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
class NumericRecordApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM derived_ancestor");
        jdbc.update("DELETE FROM derived_source");
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

    private ResultActions writeNumeric(String requestId, String operator, String subjectKey,
                                       String recordKey, long value, int expectedEpoch) throws Exception {
        return mockMvc.perform(post("/api/v1/numeric-records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","operator":"%s","subjectKey":"%s","recordKey":"%s","value":%d,"expectedEpoch":%d}
                        """.formatted(requestId, operator, subjectKey, recordKey, value, expectedEpoch)));
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

    // ---------- 写入与读取主流程 ----------

    @Test
    void writeThenReadRoundtripAndWriteResponseHidesValue() throws Exception {
        grant("g-1", "subj-a").andExpect(status().isOk());
        writeNumeric("w-1", "op-1", "subj-a", "n1", 42, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectKey").value("subj-a"))
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.recordKey").value("n1"))
                .andExpect(jsonPath("$.value").doesNotExist());
        // 值只在重新鉴权的读取中返回
        readNumeric("subj-a", "n1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("NUMERIC"))
                .andExpect(jsonPath("$.value").value(42));
    }

    @Test
    void writeBoundaryValuesZeroAndLongMax() throws Exception {
        grant("g-1", "subj-a").andExpect(status().isOk());
        writeNumeric("w-1", "op-1", "subj-a", "zero", 0, 1).andExpect(status().isOk());
        writeNumeric("w-2", "op-1", "subj-a", "max", Long.MAX_VALUE, 1).andExpect(status().isOk());
        readNumeric("subj-a", "zero").andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(0));
        readNumeric("subj-a", "max").andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(Long.MAX_VALUE));
    }

    @Test
    void negativeValueReturns400() throws Exception {
        grant("g-1", "subj-a").andExpect(status().isOk());
        writeNumeric("w-1", "op-1", "subj-a", "n1", -1, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void valueBeyondLongRangeReturns400() throws Exception {
        grant("g-1", "subj-a").andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/numeric-records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"w-1","operator":"op-1","subjectKey":"subj-a","recordKey":"n1","value":9223372036854775808,"expectedEpoch":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    // ---------- 授权状态分支 ----------

    @Test
    void writeWithoutGrantReturns404() throws Exception {
        writeNumeric("w-1", "op-1", "subj-none", "n1", 1, 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void writeAfterRevokeReturns410() throws Exception {
        grant("g-1", "subj-a").andExpect(status().isOk());
        revoke("r-1", "subj-a", 1).andExpect(status().isOk());
        writeNumeric("w-1", "op-1", "subj-a", "n1", 1, 1)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void expectedEpochMismatchReturns409() throws Exception {
        grant("g-1", "subj-a").andExpect(status().isOk());
        writeNumeric("w-1", "op-1", "subj-a", "n1", 1, 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EPOCH_MISMATCH"));
    }

    @Test
    void duplicateTargetKeyReturns409() throws Exception {
        grant("g-1", "subj-a").andExpect(status().isOk());
        writeNumeric("w-1", "op-1", "subj-a", "n1", 1, 1).andExpect(status().isOk());
        writeNumeric("w-2", "op-1", "subj-a", "n1", 2, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_KEY_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM numeric_record")).isEqualTo(1);
    }

    // ---------- 幂等与重放 ----------

    @Test
    void replaySameRequestIdSameParamsReturnsFirstResponse() throws Exception {
        grant("g-1", "subj-a").andExpect(status().isOk());
        writeNumeric("w-1", "op-1", "subj-a", "n1", 7, 1).andExpect(status().isOk());
        writeNumeric("w-1", "op-1", "subj-a", "n1", 7, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.value").doesNotExist());
        assertThat(count("SELECT COUNT(*) FROM numeric_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'w-1'")).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        grant("g-1", "subj-a").andExpect(status().isOk());
        writeNumeric("w-1", "op-1", "subj-a", "n1", 7, 1).andExpect(status().isOk());
        // 操作者属于幂等身份：异参 409
        writeNumeric("w-1", "op-2", "subj-a", "n1", 7, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        // 期望版本属于幂等身份：异参 409
        writeNumeric("w-1", "op-1", "subj-a", "n1", 7, 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        // 值属于幂等身份：异参 409
        writeNumeric("w-1", "op-1", "subj-a", "n1", 8, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void replayAfterRevokeReturns410() throws Exception {
        grant("g-1", "subj-a").andExpect(status().isOk());
        writeNumeric("w-1", "op-1", "subj-a", "n1", 7, 1).andExpect(status().isOk());
        revoke("r-1", "subj-a", 1).andExpect(status().isOk());
        // 撤回后载荷操作重放也返回 410
        writeNumeric("w-1", "op-1", "subj-a", "n1", 7, 1)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void failedRequestDoesNotConsumeRequestIdOrKey() throws Exception {
        writeNumeric("w-1", "op-1", "subj-a", "n1", 7, 1).andExpect(status().isNotFound());
        grant("g-1", "subj-a").andExpect(status().isOk());
        writeNumeric("w-1", "op-1", "subj-a", "n1", 7, 1).andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM numeric_record")).isEqualTo(1);
    }

    // ---------- 读取分支 ----------

    @Test
    void readWithoutGrantReturns404() throws Exception {
        readNumeric("subj-none", "n1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void readMissingRecordReturns404() throws Exception {
        grant("g-1", "subj-a").andExpect(status().isOk());
        readNumeric("subj-a", "n-none")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
    }

    @Test
    void readAfterRevokeReturns410() throws Exception {
        grant("g-1", "subj-a").andExpect(status().isOk());
        writeNumeric("w-1", "op-1", "subj-a", "n1", 7, 1).andExpect(status().isOk());
        revoke("r-1", "subj-a", 1).andExpect(status().isOk());
        readNumeric("subj-a", "n1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }
}
