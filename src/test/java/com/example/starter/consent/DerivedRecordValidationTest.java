package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
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
 * 派生记录失败分支测试：来源有效性、重复来源、目标键冲突、求和溢出与派生层级上限。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DerivedRecordValidationTest {

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

    private ResultActions writeNumeric(String requestId, String subjectKey,
                                       String recordKey, long value) throws Exception {
        return mockMvc.perform(post("/api/v1/numeric-records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","operator":"op-1","subjectKey":"%s","recordKey":"%s","value":%d,"expectedEpoch":1}
                        """.formatted(requestId, subjectKey, recordKey, value)));
    }

    private ResultActions derive(String requestId, String targetSubjectKey, String recordKey,
                                 String sourcesJson) throws Exception {
        return mockMvc.perform(post("/api/v1/derived-records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","operator":"op-1","targetSubjectKey":"%s","recordKey":"%s","expectedEpoch":1,"sources":%s}
                        """.formatted(requestId, targetSubjectKey, recordKey, sourcesJson)));
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    private static String source(String subjectKey, int epoch, String recordKey) {
        return "{\"subjectKey\":\"%s\",\"epoch\":%d,\"recordKey\":\"%s\"}"
                .formatted(subjectKey, epoch, recordKey);
    }

    private void grantAndWrite(String subjectKey, String recordKey, long value) throws Exception {
        grant("g-" + subjectKey, subjectKey).andExpect(status().isOk());
        writeNumeric("w-" + subjectKey, subjectKey, recordKey, value).andExpect(status().isOk());
    }

    // ---------- 来源有效性 ----------

    @Test
    void unknownSourceSubjectReturns404() throws Exception {
        grantAndWrite("subj-a", "n1", 1);
        grant("g-c", "subj-c").andExpect(status().isOk());
        derive("d-1", "subj-c", "sum", "[" + source("subj-a", 1, "n1") + "," + source("subj-none", 1, "n1") + "]")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void missingSourceRecordReturns404() throws Exception {
        grantAndWrite("subj-a", "n1", 1);
        grant("g-b", "subj-b").andExpect(status().isOk());
        grant("g-c", "subj-c").andExpect(status().isOk());
        derive("d-1", "subj-c", "sum", "[" + source("subj-a", 1, "n1") + "," + source("subj-b", 1, "n-none") + "]")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));
    }

    @Test
    void revokedSourceReturns410() throws Exception {
        grantAndWrite("subj-a", "n1", 1);
        grantAndWrite("subj-b", "n1", 2);
        grant("g-c", "subj-c").andExpect(status().isOk());
        revoke("r-b", "subj-b", 1).andExpect(status().isOk());
        derive("d-1", "subj-c", "sum", "[" + source("subj-a", 1, "n1") + "," + source("subj-b", 1, "n1") + "]")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void oldEpochSourceReturns410() throws Exception {
        grantAndWrite("subj-a", "n1", 1);
        grant("g-b", "subj-b").andExpect(status().isOk());
        writeNumeric("w-b", "subj-b", "n1", 2).andExpect(status().isOk());
        grant("g-c", "subj-c").andExpect(status().isOk());
        // B 撤回代 1 后重新授权到代 2，引用旧代 1 的来源 410
        revoke("r-b", "subj-b", 1).andExpect(status().isOk());
        grant("g-b2", "subj-b").andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2));
        derive("d-1", "subj-c", "sum", "[" + source("subj-a", 1, "n1") + "," + source("subj-b", 1, "n1") + "]")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void duplicateSourcesReturn400() throws Exception {
        grantAndWrite("subj-a", "n1", 1);
        grant("g-c", "subj-c").andExpect(status().isOk());
        derive("d-1", "subj-c", "sum", "[" + source("subj-a", 1, "n1") + "," + source("subj-a", 1, "n1") + "]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SOURCE"));
    }

    @Test
    void tooFewOrTooManySourcesReturn400() throws Exception {
        grantAndWrite("subj-a", "n1", 1);
        grant("g-c", "subj-c").andExpect(status().isOk());
        derive("d-1", "subj-c", "sum", "[" + source("subj-a", 1, "n1") + "]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        String six = "[" + String.join(",", java.util.Collections.nCopies(6, source("subj-a", 1, "n1"))) + "]";
        derive("d-2", "subj-c", "sum", six)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    // ---------- 目标键与求和溢出 ----------

    @Test
    void existingTargetKeyReturns409() throws Exception {
        grantAndWrite("subj-a", "n1", 1);
        grantAndWrite("subj-b", "n1", 2);
        grant("g-c", "subj-c").andExpect(status().isOk());
        writeNumeric("w-c", "subj-c", "sum", 9).andExpect(status().isOk());
        derive("d-1", "subj-c", "sum", "[" + source("subj-a", 1, "n1") + "," + source("subj-b", 1, "n1") + "]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_KEY_CONFLICT"));
    }

    @Test
    void sumOverflowReturns400AndDoesNotOccupyKeyOrRequestId() throws Exception {
        grantAndWrite("subj-a", "n1", Long.MAX_VALUE);
        grantAndWrite("subj-b", "n1", 1);
        grant("g-c", "subj-c").andExpect(status().isOk());
        derive("d-1", "subj-c", "sum", "[" + source("subj-a", 1, "n1") + "," + source("subj-b", 1, "n1") + "]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUM_OVERFLOW"));
        // 失败不占键、不占 requestId
        assertThat(count("SELECT COUNT(*) FROM numeric_record WHERE record_key = 'sum'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'd-1'")).isZero();
        // 同一 requestId 以合法参数重试成功
        writeNumeric("w-b2", "subj-b", "n2", 1).andExpect(status().isOk());
        derive("d-1", "subj-c", "sum", "[" + source("subj-b", 1, "n1") + "," + source("subj-b", 1, "n2") + "]")
                .andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM numeric_record WHERE record_key = 'sum'")).isEqualTo(1);
    }

    // ---------- 派生层级上限 ----------

    @Test
    void derivationDepthBeyondThreeReturns422AndDoesNotOccupyKey() throws Exception {
        grant("g-x", "subj-x").andExpect(status().isOk());
        writeNumeric("w-1", "subj-x", "n1", 1).andExpect(status().isOk());
        writeNumeric("w-2", "subj-x", "n2", 1).andExpect(status().isOk());
        writeNumeric("w-3", "subj-x", "n3", 1).andExpect(status().isOk());
        writeNumeric("w-4", "subj-x", "n4", 1).andExpect(status().isOk());
        writeNumeric("w-5", "subj-x", "n5", 1).andExpect(status().isOk());
        // 第 1 层：g1 = n1 + n2
        derive("d-1", "subj-x", "g1", "[" + source("subj-x", 1, "n1") + "," + source("subj-x", 1, "n2") + "]")
                .andExpect(status().isOk());
        // 第 2 层：g2 = g1 + n3
        derive("d-2", "subj-x", "g2", "[" + source("subj-x", 1, "g1") + "," + source("subj-x", 1, "n3") + "]")
                .andExpect(status().isOk());
        // 第 3 层：g3 = g2 + n4
        derive("d-3", "subj-x", "g3", "[" + source("subj-x", 1, "g2") + "," + source("subj-x", 1, "n4") + "]")
                .andExpect(status().isOk());
        // 第 4 层超限：422，不生成记录、不占目标键
        derive("d-4", "subj-x", "g4", "[" + source("subj-x", 1, "g3") + "," + source("subj-x", 1, "n5") + "]")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DERIVATION_DEPTH_EXCEEDED"));
        assertThat(count("SELECT COUNT(*) FROM numeric_record WHERE record_key = 'g4'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'd-4'")).isZero();
        // 目标键未被占用：以合法来源派生同键成功
        derive("d-5", "subj-x", "g4", "[" + source("subj-x", 1, "n1") + "," + source("subj-x", 1, "n5") + "]")
                .andExpect(status().isOk());
    }
}
