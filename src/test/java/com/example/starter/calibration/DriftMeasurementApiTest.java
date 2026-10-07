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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 参考漂移测量测试：合成场景（10:00 偏 0、11:00 偏 2、10:30 原值 5 得 4）、
 * 精确有理数保存与未舍入判定、两端夹取边界、422/400 失败分支。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DriftMeasurementApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM drift_reference_archive");
        jdbc.update("DELETE FROM instrument_lock");
        jdbc.update("DELETE FROM request_dedup");
    }

    private long createCert(String instrument, String a, String b) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"%s","b":"%s"}
                                """.formatted(instrument, a, b)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private long createArchive(String instrument, String at, String offset) throws Exception {
        String body = mvc.perform(post("/api/drift-archives").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","referencedAt":"%s","offset":"%s"}
                                """.formatted(instrument, at, offset)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.instrumentId").value(instrument))
                .andExpect(jsonPath("$.revoked").value(false))
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private String driftJson(String key, String instrument, String at, String reading,
                             String lower, String upper, String by, long a0, long a1) {
        return """
                {"measurementKey":"%s","instrumentId":"%s","measuredAt":"%s","reading":"%s",
                 "lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s",
                 "archiveId0":%d,"archiveId1":%d}
                """.formatted(key, instrument, at, reading, lower, upper, by, a0, a1);
    }

    @Test
    void syntheticScenarioInterpolatesAndYieldsFour() throws Exception {
        // 合成数据：证书 a=1、b=0；两参考 10:00 偏 0、11:00 偏 2；10:30 原值 5 → 5−1=4
        createCert("INS-D", "1", "0");
        long a0 = createArchive("INS-D", "2026-06-01T10:00:00Z", "0");
        long a1 = createArchive("INS-D", "2026-06-01T11:00:00Z", "2");

        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("D-1", "INS-D", "2026-06-01T10:30:00Z",
                                "5", "0", "9", "alice", a0, a1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("DRIFT_REF"))
                .andExpect(jsonPath("$.computedValue").value("5"))
                .andExpect(jsonPath("$.correctedValue").value("4"))
                .andExpect(jsonPath("$.displayValue").value("4.0000"))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.refArchiveId0").value(a0))
                .andExpect(jsonPath("$.refArchiveId1").value(a1));

        // 边界：测量时刻恰为 t1（闭区间）→ 偏移取上端 2，5−2=3
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("D-2", "INS-D", "2026-06-01T11:00:00Z",
                                "5", "0", "9", "alice", a0, a1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correctedValue").value("3"));
    }

    @Test
    void exactFractionStoredAndJudgedUnrounded() throws Exception {
        // 间隔 7 分钟，t 距 t0 为 1 分钟：偏移 = 1/7，校正值 = 1−1/7 = 6/7（无限循环，保存分数）
        createCert("INS-F", "1", "0");
        long a0 = createArchive("INS-F", "2026-06-01T10:00:00Z", "0");
        long a1 = createArchive("INS-F", "2026-06-01T10:07:00Z", "1");

        // 上限 0.8571 < 6/7 ≈ 0.857143 → 不合格；显示值 0.8571 看似贴边
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("F-1", "INS-F", "2026-06-01T10:01:00Z",
                                "1", "0", "0.8571", "alice", a0, a1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("1"))
                .andExpect(jsonPath("$.correctedValue").value("6/7"))
                .andExpect(jsonPath("$.displayValue").value("0.8571"))
                .andExpect(jsonPath("$.passed").value(false));

        // 上限 0.8572 > 6/7 → 合格
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("F-2", "INS-F", "2026-06-01T10:01:00Z",
                                "1", "0", "0.8572", "alice", a0, a1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correctedValue").value("6/7"))
                .andExpect(jsonPath("$.passed").value(true));
    }

    @Test
    void bracketViolationsReturn422() throws Exception {
        createCert("INS-D", "1", "0");
        long a0 = createArchive("INS-D", "2026-06-01T10:00:00Z", "0");
        long a1 = createArchive("INS-D", "2026-06-01T11:00:00Z", "2");
        long aLate = createArchive("INS-D", "2026-06-01T11:00:00.001Z", "2");
        long aOther = createArchive("INS-OTHER", "2026-06-01T10:30:00Z", "1");

        // t0 不小于 t1（两端颠倒）→ 422
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("B-1", "INS-D", "2026-06-01T10:30:00Z",
                                "5", "0", "9", "alice", a1, a0)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DRIFT_REFERENCE_INVALID"));
        // 间隔超过 1 小时 → 422
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("B-2", "INS-D", "2026-06-01T10:30:00Z",
                                "5", "0", "9", "alice", a0, aLate)))
                .andExpect(status().isUnprocessableEntity());
        // 测量时刻在 [t0,t1] 之外 → 422
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("B-3", "INS-D", "2026-06-01T09:59:00Z",
                                "5", "0", "9", "alice", a0, a1)))
                .andExpect(status().isUnprocessableEntity());
        // 档案仪器不符 → 422
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("B-4", "INS-D", "2026-06-01T10:30:00Z",
                                "5", "0", "9", "alice", a0, aOther)))
                .andExpect(status().isUnprocessableEntity());
        // 档案不存在 → 422，不补查最近档案代替
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("B-5", "INS-D", "2026-06-01T10:30:00Z",
                                "5", "0", "9", "alice", a0, 999999L)))
                .andExpect(status().isUnprocessableEntity());
        // 无匹配有效证书 → 422
        long b0 = createArchive("INS-NOCERT", "2026-06-01T10:00:00Z", "0");
        long b1 = createArchive("INS-NOCERT", "2026-06-01T11:00:00Z", "2");
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("B-6", "INS-NOCERT", "2026-06-01T10:30:00Z",
                                "5", "0", "9", "alice", b0, b1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_MATCHING_CERTIFICATE"));
    }

    @Test
    void overPrecisionInputsReturn400AndNoMeasurementCreated() throws Exception {
        createCert("INS-D", "1", "0");
        long a0 = createArchive("INS-D", "2026-06-01T10:00:00Z", "0");
        long a1 = createArchive("INS-D", "2026-06-01T11:00:00Z", "2");

        // 档案偏移超 6 位小数 → 400
        mvc.perform(post("/api/drift-archives").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-D","referencedAt":"2026-06-01T12:00:00Z",
                                 "offset":"0.0000001"}"""))
                .andExpect(status().isBadRequest());
        // 读数超 6 位小数 → 400
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("P-1", "INS-D", "2026-06-01T10:30:00Z",
                                "1.0000001", "0", "9", "alice", a0, a1)))
                .andExpect(status().isBadRequest());
        // 测量时刻亚毫秒 → 400
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("P-2", "INS-D", "2026-06-01T10:30:00.0001Z",
                                "1", "0", "9", "alice", a0, a1)))
                .andExpect(status().isBadRequest());

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class);
        assertEquals(0, count, "超精度输入不得产生测量记录");
    }
}
