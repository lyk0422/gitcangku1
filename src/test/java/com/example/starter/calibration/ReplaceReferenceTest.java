package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 替换标准器测试：未放行批次整批生成新版本并重算全部不确定度；
 * 任一重算失败整批回滚，旧版本仍是当前有效版本；已放行批次禁止替换。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReplaceReferenceTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM release_batch");
        jdbc.update("DELETE FROM measurement_version");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long createCert(String instrument, String from, String to, String a, String b,
                            String certVersion, String comp) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"%s","validTo":"%s","a":"%s","b":"%s",
                                 "certVersion":"%s","compensationCoeff":"%s","uncertaintyVersion":"UV-%s"}
                                """.formatted(instrument, from, to, a, b, certVersion, comp, certVersion)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void revoke(long certId) throws Exception {
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
    }

    private void batchSubmit(String batchId, String instrument, String... keysAndTimes) throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < keysAndTimes.length; i += 2) {
            if (i > 0) {
                items.append(',');
            }
            items.append("""
                    {"measurementKey":"%s","instrumentId":"%s","measuredAt":"%s",
                     "reading":"10","lowerLimit":"0","upperLimit":"100","submittedBy":"alice"}
                    """.formatted(keysAndTimes[i], instrument, keysAndTimes[i + 1]));
        }
        mvc.perform(post("/api/measurements/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":\"" + batchId + "\",\"items\":[" + items + "]}"))
                .andExpect(status().isCreated());
    }

    @Test
    void replaceReferenceCreatesNewVersionAndRecomputesAll() throws Exception {
        long c1 = createCert("INS-1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                "1", "0", "CV-1", "0.01");
        batchSubmit("B-1", "INS-1", "R-1", "2026-06-01T00:00:00Z", "R-2", "2026-06-02T00:00:00Z");

        // 撤销旧证书后换发同区间新证书（补偿系数与系数不同）
        revoke(c1);
        long c2 = createCert("INS-1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                "2", "1", "CV-2", "0.05");

        mvc.perform(post("/api/measurement-batches/{batchId}/replace-reference", "B-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateId\":" + c2 + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("B-1"))
                .andExpect(jsonPath("$.recomputed.length()").value(2))
                .andExpect(jsonPath("$.recomputed[0].version").value(2))
                .andExpect(jsonPath("$.recomputed[1].version").value(2));

        // 当前版本切换：computed = 2×10+1 = 21；uncertainty = |0.05×10| = 0.5
        mvc.perform(get("/api/measurements/{key}", "R-1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.certificateId").value(c2))
                .andExpect(jsonPath("$.certVersion").value("CV-2"))
                .andExpect(jsonPath("$.uncertaintyVersion").value("UV-CV-2"))
                .andExpect(jsonPath("$.computedValue").value("21"))
                .andExpect(jsonPath("$.uncertainty").value("0.5"));

        // 血缘：两个版本，v1 保留旧证书快照，v2 为当前版本
        mvc.perform(get("/api/measurements/{key}/lineage", "R-1"))
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].version").value(1))
                .andExpect(jsonPath("$.versions[0].certVersion").value("CV-1"))
                .andExpect(jsonPath("$.versions[0].uncertainty").value("0.1"))
                .andExpect(jsonPath("$.versions[0].current").value(false))
                .andExpect(jsonPath("$.versions[1].version").value(2))
                .andExpect(jsonPath("$.versions[1].certVersion").value("CV-2"))
                .andExpect(jsonPath("$.versions[1].uncertainty").value("0.5"))
                .andExpect(jsonPath("$.versions[1].current").value(true));
    }

    @Test
    void recomputeFailureKeepsOldVersionCurrent() throws Exception {
        long c1 = createCert("INS-1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                "1", "0", "CV-1", "0.01");
        batchSubmit("B-2", "INS-1", "F-1", "2026-06-01T00:00:00Z", "F-2", "2026-12-01T00:00:00Z");

        revoke(c1);
        // 新证书只覆盖 F-1 的测量时刻，F-2（2026-12-01）超出有效期 → 该条重算失败
        long c2 = createCert("INS-1", "2026-01-01T00:00:00Z", "2026-09-01T00:00:00Z",
                "2", "1", "CV-2", "0.05");

        mvc.perform(post("/api/measurement-batches/{batchId}/replace-reference", "B-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateId\":" + c2 + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECOMPUTE_FAILED"))
                .andExpect(jsonPath("$.failures[?(@.key=='F-2')].reasons[0]")
                        .value("CERTIFICATE_NOT_VALID_AT_MEASURED_AT"))
                .andExpect(jsonPath("$.failures.length()").value(1));

        // 整批回滚：两条测量都停留在 v1，旧版本仍是当前有效版本
        mvc.perform(get("/api/measurements/{key}", "F-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.certificateId").value(c1))
                .andExpect(jsonPath("$.uncertainty").value("0.1"));
        mvc.perform(get("/api/measurements/{key}", "F-2"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.certificateId").value(c1));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement_version", Integer.class));
    }

    @Test
    void instrumentMismatchFailsWholeBatch() throws Exception {
        createCert("INS-1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                "1", "0", "CV-1", "0.01");
        batchSubmit("B-3", "INS-1", "M-1", "2026-06-01T00:00:00Z");
        long other = createCert("INS-2", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                "1", "0", "CV-9", "0.01");

        mvc.perform(post("/api/measurement-batches/{batchId}/replace-reference", "B-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateId\":" + other + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("INSTRUMENT_MISMATCH"));
        mvc.perform(get("/api/measurements/{key}", "M-1"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void releasedBatchCannotBeReplaced() throws Exception {
        createCert("INS-1", "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z",
                "1", "0", "CV-1", "0.01");
        batchSubmit("B-4", "INS-1", "L-1", "2026-06-01T00:00:00Z");
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"L-1\"]}"))
                .andExpect(status().isOk());

        long c2 = createCert("INS-2", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                "1", "0", "CV-2", "0.01");
        mvc.perform(post("/api/measurement-batches/{batchId}/replace-reference", "B-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateId\":" + c2 + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_ALREADY_RELEASED"));
        mvc.perform(get("/api/measurements/{key}", "L-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }

    @Test
    void replaceValidationErrors() throws Exception {
        long c1 = createCert("INS-1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                "1", "0", "CV-1", "0.01");
        batchSubmit("B-5", "INS-1", "V-1", "2026-06-01T00:00:00Z");

        // 批次不存在 → 404
        mvc.perform(post("/api/measurement-batches/{batchId}/replace-reference", "NO-SUCH")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateId\":" + c1 + "}"))
                .andExpect(status().isNotFound());
        // 证书不存在 → 404
        mvc.perform(post("/api/measurement-batches/{batchId}/replace-reference", "B-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateId\":999999}"))
                .andExpect(status().isNotFound());
        // 证书已撤销 → 409
        revoke(c1);
        mvc.perform(post("/api/measurement-batches/{batchId}/replace-reference", "B-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateId\":" + c1 + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_REVOKED"));
        // 缺 certificateId → 400
        mvc.perform(post("/api/measurement-batches/{batchId}/replace-reference", "B-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
