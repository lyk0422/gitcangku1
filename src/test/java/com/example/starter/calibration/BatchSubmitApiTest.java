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
 * 批量测量提交测试：先按最终引用预校验、整批原子写入、
 * 失败不留半成品状态、同键重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchSubmitApiTest {

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

    private void createCert(String instrument) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0",
                                 "certVersion":"CV-1","compensationCoeff":"0.01",
                                 "uncertaintyVersion":"UV-1"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated());
    }

    private String item(String key, String referenceKey, String instrument, String reading) {
        String rk = referenceKey == null ? "" : "\"referenceKey\":\"%s\",".formatted(referenceKey);
        return """
                {"measurementKey":"%s",%s"instrumentId":"%s","measuredAt":"2026-06-01T00:00:00Z",
                 "reading":"%s","lowerLimit":"0","upperLimit":"100","submittedBy":"alice"}
                """.formatted(key, rk, instrument, reading);
    }

    @Test
    void batchSubmitCreatesAllAtomically() throws Exception {
        createCert("INS-1");
        mvc.perform(post("/api/measurements/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":"B-1","items":[%s,%s]}
                                """.formatted(item("B-1-1", "RK-B1", "INS-1", "10"),
                                item("B-1-2", "RK-B2", "INS-1", "20"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchId").value("B-1"))
                .andExpect(jsonPath("$.measurements.length()").value(2))
                .andExpect(jsonPath("$.measurements[0].measurementKey").value("B-1-1"))
                .andExpect(jsonPath("$.measurements[0].batchId").value("B-1"))
                .andExpect(jsonPath("$.measurements[0].version").value(1))
                .andExpect(jsonPath("$.measurements[0].uncertainty").value("0.1"))
                .andExpect(jsonPath("$.measurements[1].uncertainty").value("0.2"));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE batch_id = 'B-1'", Integer.class);
        assertEquals(2, count);
        Integer versionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement_version", Integer.class);
        assertEquals(2, versionCount);
    }

    @Test
    void batchSubmitPrevalidatesAndRejectsAtomically() throws Exception {
        createCert("INS-1");
        // 第二条仪器无有效证书 → 整批 422，且第一条也不写入
        mvc.perform(post("/api/measurements/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":"B-2","items":[%s,%s]}
                                """.formatted(item("B-2-1", null, "INS-1", "10"),
                                item("B-2-2", null, "INS-MISSING", "20"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_SUBMIT_REJECTED"))
                .andExpect(jsonPath("$.failures[?(@.key=='B-2-2')].reasons[0]")
                        .value("NO_MATCHING_CERTIFICATE"))
                .andExpect(jsonPath("$.failures.length()").value(1));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM measurement_version", Integer.class));
    }

    @Test
    void batchSubmitReportsInvalidInputAndDuplicateKey() throws Exception {
        createCert("INS-1");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"B-3-0","instrumentId":"INS-1",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"100","submittedBy":"alice"}"""))
                .andExpect(status().isCreated());

        // 第一条读数非法、第二条测量键已存在 → 两项失败，整批拒绝
        mvc.perform(post("/api/measurements/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":"B-3","items":[
                                 {"measurementKey":"B-3-1","instrumentId":"INS-1",
                                  "measuredAt":"2026-06-01T00:00:00Z","reading":"1.0000001",
                                  "lowerLimit":"0","upperLimit":"100","submittedBy":"alice"},
                                 {"measurementKey":"B-3-0","instrumentId":"INS-1",
                                  "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                  "lowerLimit":"0","upperLimit":"100","submittedBy":"alice"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[?(@.key=='B-3-1')].reasons[0]").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.failures[?(@.key=='B-3-0')].reasons[0]")
                        .value("DUPLICATE_MEASUREMENT_KEY"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class));
    }

    @Test
    void batchSubmitStructuralValidation() throws Exception {
        createCert("INS-1");
        // 空批次 → 400
        mvc.perform(post("/api/measurements/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":\"B-4\",\"items\":[]}"))
                .andExpect(status().isBadRequest());
        // 批次内重复测量键 → 400
        mvc.perform(post("/api/measurements/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":"B-4","items":[%s,%s]}
                                """.formatted(item("B-4-1", null, "INS-1", "1"),
                                item("B-4-1", null, "INS-1", "2"))))
                .andExpect(status().isBadRequest());
        // 批次内重复引用键 → 400
        mvc.perform(post("/api/measurements/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":"B-4","items":[%s,%s]}
                                """.formatted(item("B-4-2", "RK-DUP", "INS-1", "1"),
                                item("B-4-3", "RK-DUP", "INS-1", "2"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void batchReplayReturnsExistingWithoutNewRows() throws Exception {
        createCert("INS-1");
        String payload = """
                {"batchId":"B-5","items":[%s,%s]}
                """.formatted(item("B-5-1", "RK-R1", "INS-1", "10"),
                item("B-5-2", "RK-R2", "INS-1", "20"));
        mvc.perform(post("/api/measurements/batch").contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        // 整批同键重放 → 200，不新增行
        mvc.perform(post("/api/measurements/batch").contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements.length()").value(2))
                .andExpect(jsonPath("$.measurements[0].measurementKey").value("B-5-1"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class));

        // 血缘：每个测量只有 1 个版本且为当前版本
        mvc.perform(get("/api/measurements/{key}/lineage", "B-5-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.versions.length()").value(1))
                .andExpect(jsonPath("$.versions[0].current").value(true))
                .andExpect(jsonPath("$.versions[0].certVersion").value("CV-1"));
    }
}
