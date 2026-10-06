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
 * 派生记录 API 集成测试：求和主流程、完整祖先闭包 410 语义与幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DerivedRecordApiTest {

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
        return writeNumeric(requestId, subjectKey, recordKey, value, 1);
    }

    private ResultActions writeNumeric(String requestId, String subjectKey,
                                       String recordKey, long value, int expectedEpoch) throws Exception {
        return mockMvc.perform(post("/api/v1/numeric-records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","operator":"op-1","subjectKey":"%s","recordKey":"%s","value":%d,"expectedEpoch":%d}
                        """.formatted(requestId, subjectKey, recordKey, value, expectedEpoch)));
    }

    private ResultActions derive(String requestId, String targetSubjectKey, String recordKey,
                                 int expectedEpoch, String sourcesJson) throws Exception {
        return mockMvc.perform(post("/api/v1/derived-records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","operator":"op-1","targetSubjectKey":"%s","recordKey":"%s","expectedEpoch":%d,"sources":%s}
                        """.formatted(requestId, targetSubjectKey, recordKey, expectedEpoch, sourcesJson)));
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

    private static String source(String subjectKey, int epoch, String recordKey) {
        return "{\"subjectKey\":\"%s\",\"epoch\":%d,\"recordKey\":\"%s\"}"
                .formatted(subjectKey, epoch, recordKey);
    }

    /**
     * 搭题干合成场景：A 代 1 值 2、B 代 1 值 3、D 代 1 值 4，C、E 持有当前研究授权。
     */
    private void setupSyntheticScenario() throws Exception {
        grant("g-a", "subj-a").andExpect(status().isOk());
        grant("g-b", "subj-b").andExpect(status().isOk());
        grant("g-c", "subj-c").andExpect(status().isOk());
        grant("g-d", "subj-d").andExpect(status().isOk());
        grant("g-e", "subj-e").andExpect(status().isOk());
        writeNumeric("w-a", "subj-a", "n1", 2).andExpect(status().isOk());
        writeNumeric("w-b", "subj-b", "n1", 3).andExpect(status().isOk());
        writeNumeric("w-d", "subj-d", "n1", 4).andExpect(status().isOk());
    }

    private void deriveC() throws Exception {
        derive("d-c", "subj-c", "sum-ab", 1,
                "[" + source("subj-a", 1, "n1") + "," + source("subj-b", 1, "n1") + "]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectKey").value("subj-c"))
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.recordKey").value("sum-ab"))
                .andExpect(jsonPath("$.value").doesNotExist());
    }

    private void deriveE() throws Exception {
        derive("d-e", "subj-e", "sum-cd", 1,
                "[" + source("subj-c", 1, "sum-ab") + "," + source("subj-d", 1, "n1") + "]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").doesNotExist());
    }

    // ---------- 求和主流程与溯源闭包 ----------

    @Test
    void deriveSumsSourcesAndChainedDerivation() throws Exception {
        setupSyntheticScenario();
        // A 代 1 值 2 与 B 代 1 值 3 派生 C 值 5
        deriveC();
        readNumeric("subj-c", "sum-ab")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("DERIVED"))
                .andExpect(jsonPath("$.value").value(5));
        // 再由 C 和 D 值 4 派生 E 值 9
        deriveE();
        readNumeric("subj-e", "sum-cd")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(9));
        // 直接来源与完整祖先闭包均已固化保存
        assertThat(count("SELECT COUNT(*) FROM derived_source WHERE target_record_key = 'sum-cd'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM derived_ancestor WHERE target_record_key = 'sum-cd'")).isEqualTo(4);
    }

    @Test
    void revokingAnyAncestorMakesWholeDerivedLineageGone() throws Exception {
        setupSyntheticScenario();
        deriveC();
        deriveE();
        // 撤回 A 代 1 后，C、E 读取都 410，即使目标授权仍有效
        revoke("r-a", "subj-a", 1).andExpect(status().isOk());
        readNumeric("subj-c", "sum-ab")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("DERIVATION_REVOKED"));
        readNumeric("subj-e", "sum-cd")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("DERIVATION_REVOKED"));
    }

    @Test
    void regrantWithSameNameRecordCannotRestoreOldDerivation() throws Exception {
        setupSyntheticScenario();
        deriveC();
        revoke("r-a", "subj-a", 1).andExpect(status().isOk());
        // 新授权同名记录（新代次）不能恢复旧派生
        grant("g-a2", "subj-a").andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2));
        writeNumeric("w-a2", "subj-a", "n1", 2, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2));
        readNumeric("subj-c", "sum-ab")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("DERIVATION_REVOKED"));
    }

    @Test
    void revokingIntermediateDerivedSubjectAlsoGone() throws Exception {
        setupSyntheticScenario();
        deriveC();
        deriveE();
        // 撤回中间派生记录所属主体 C 的授权：E 闭包含 C，整体 410
        revoke("r-c", "subj-c", 1).andExpect(status().isOk());
        readNumeric("subj-e", "sum-cd")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("DERIVATION_REVOKED"));
    }

    // ---------- 派生幂等与重放 ----------

    @Test
    void deriveReplaySameParamsReturnsFirstResponse() throws Exception {
        setupSyntheticScenario();
        deriveC();
        derive("d-c", "subj-c", "sum-ab", 1,
                "[" + source("subj-a", 1, "n1") + "," + source("subj-b", 1, "n1") + "]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.value").doesNotExist());
        assertThat(count("SELECT COUNT(*) FROM numeric_record WHERE record_key = 'sum-ab'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'd-c'")).isEqualTo(1);
    }

    @Test
    void deriveSameRequestIdWithDifferentParamsReturns409() throws Exception {
        setupSyntheticScenario();
        deriveC();
        derive("d-c", "subj-c", "sum-ab", 1,
                "[" + source("subj-b", 1, "n1") + "," + source("subj-a", 1, "n1") + "]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void deriveReplayAfterAncestorRevokeReturns410() throws Exception {
        setupSyntheticScenario();
        deriveC();
        revoke("r-a", "subj-a", 1).andExpect(status().isOk());
        // 含值成功重放也整体 410：祖先代失效后不得返回成功快照
        derive("d-c", "subj-c", "sum-ab", 1,
                "[" + source("subj-a", 1, "n1") + "," + source("subj-b", 1, "n1") + "]")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("DERIVATION_REVOKED"));
    }

    @Test
    void deriveReplayAfterTargetRevokeReturns410() throws Exception {
        setupSyntheticScenario();
        deriveC();
        revoke("r-c", "subj-c", 1).andExpect(status().isOk());
        derive("d-c", "subj-c", "sum-ab", 1,
                "[" + source("subj-a", 1, "n1") + "," + source("subj-b", 1, "n1") + "]")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }
}
