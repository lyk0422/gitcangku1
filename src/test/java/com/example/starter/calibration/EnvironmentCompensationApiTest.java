package com.example.starter.calibration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 环境补偿端到端（真实 H2 + MockMvc）：系数版本、环境边界、六位精度、整批门禁、
 * 版本更新只影响后续、重算链与已放行快照、驳回、放行诊断、calcKey 失败不占键。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EnvironmentCompensationApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM calc_log");
        jdbc.update("DELETE FROM measurement_version");
        jdbc.update("DELETE FROM reject_record");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM compensation_profile");
        jdbc.update("DELETE FROM compensation_model_lock");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private void createCert(String instrument, String a, String b) {
        try {
            mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                     "validTo":"2028-01-01T00:00:00Z","a":"%s","b":"%s"}
                                    """.formatted(instrument, a, b)))
                    .andExpect(status().isCreated());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private long createProfile(String model, String kT, String kH) throws Exception {
        return createProfile(model, kT, kH, "10", "40", "20", "80");
    }

    private long createProfile(String model, String kT, String kH, String tMin, String tMax,
                               String hMin, String hMax) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("instrumentModel", model);
        body.put("tempCoeff", kT);
        body.put("humidityCoeff", kH);
        body.put("tempMin", tMin);
        body.put("tempMax", tMax);
        body.put("humidityMin", hMin);
        body.put("humidityMax", hMax);
        MvcResult result = mvc.perform(post("/api/compensation-profiles")
                        .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private String measurementBody(String key, String instrument, String model, String reading,
                                   String lower, String upper, String temp, String humidity,
                                   String uncertainty, String by) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("measurementKey", key);
        body.put("instrumentId", instrument);
        if (model != null) {
            body.put("instrumentModel", model);
        }
        body.put("measuredAt", "2026-06-01T00:00:00Z");
        body.put("reading", reading);
        body.put("lowerLimit", lower);
        body.put("upperLimit", upper);
        if (temp != null) {
            body.put("temperature", temp);
        }
        if (humidity != null) {
            body.put("humidity", humidity);
        }
        if (uncertainty != null) {
            body.put("uncertainty", uncertainty);
        }
        body.put("submittedBy", by);
        return JSON.writeValueAsString(body);
    }

    @Test
    void profileUpdateAppendsVersionAndDeactivatesPrevious() throws Exception {
        long v1 = createProfile("MODEL-A", "0.01", "0.001");
        long v2 = createProfile("MODEL-A", "0.02", "0.002");

        mvc.perform(get("/api/compensation-profiles/{id}", v1))
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/compensation-profiles/{id}", v2))
                .andExpect(jsonPath("$.versionNo").value(2))
                .andExpect(jsonPath("$.active").value(true));

        mvc.perform(get("/api/compensation-profiles").param("instrumentModel", "MODEL-A"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].versionNo").value(1))
                .andExpect(jsonPath("$[1].versionNo").value(2));

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM compensation_profile WHERE instrument_model = 'MODEL-A' AND active = TRUE",
                Integer.class);
        assertEquals(1, rows, "同一型号恰好一个生效版本");
    }

    @Test
    void submitWithEnvironmentComputesSixDecimalCompensatedValueAndPinsProfile() throws Exception {
        createCert("INS-1", "1", "0");
        long profileId = createProfile("MODEL-A", "0.01", "0.001");
        // raw 10 + 0.01×25(0.25) + 0.001×50(0.05) = 10.300000
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementBody("E-1", "INS-1", "MODEL-A", "10", "10.2", "10.4",
                                "25", "50", "0.05", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reading").value("10"))
                .andExpect(jsonPath("$.computedValue").value("10"))
                .andExpect(jsonPath("$.temperature").value("25"))
                .andExpect(jsonPath("$.humidity").value("50"))
                .andExpect(jsonPath("$.uncertainty").value("0.05"))
                .andExpect(jsonPath("$.compensationProfileId").value((int) profileId))
                .andExpect(jsonPath("$.compensationVersionNo").value(1))
                .andExpect(jsonPath("$.compensatedValue").value("10.300000"))
                .andExpect(jsonPath("$.compensatedPassed").value(true))
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.versions.length()").value(1));
    }

    @Test
    void environmentOutsideRangeReturns422WithRangeAndDoesNotPersist() throws Exception {
        createCert("INS-1", "1", "0");
        createProfile("MODEL-A", "0.01", "0.001");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementBody("E-2", "INS-1", "MODEL-A", "10", "0", "99",
                                "41", "50", null, "alice")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ENVIRONMENT_OUT_OF_RANGE"))
                .andExpect(jsonPath("$.range.tempMin").value("10"))
                .andExpect(jsonPath("$.range.tempMax").value("40"))
                .andExpect(jsonPath("$.range.humidityMin").value("20"))
                .andExpect(jsonPath("$.range.humidityMax").value("80"));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM calc_log WHERE operation='SUBMIT'", Integer.class),
                "失败提交不占 calcKey");
    }

    @Test
    void partialEnvironmentAndMissingModelOrProfileRejected() throws Exception {
        createCert("INS-1", "1", "0");
        createProfile("MODEL-A", "0.01", "0.001");
        // 只给温度不给湿度 → 400
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementBody("E-3", "INS-1", "MODEL-A", "10", "0", "99",
                                "25", null, null, "alice")))
                .andExpect(status().isBadRequest());
        // 有环境无型号 → 400
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementBody("E-4", "INS-1", null, "10", "0", "99",
                                "25", "50", null, "alice")))
                .andExpect(status().isBadRequest());
        // 型号无生效系数版本 → 422
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementBody("E-5", "INS-1", "NO-PROFILE", "10", "0", "99",
                                "25", "50", null, "alice")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void gateReleasePassesWhenEnvironmentSpecAndUncertaintyAllValid() throws Exception {
        createCert("INS-1", "1", "0");
        createProfile("MODEL-A", "0.01", "0.001");
        submitEnv("G-1", "10", "10.2", "10.4", "25", "50", "0.05");
        submitEnv("G-2", "10", "10.2", "10.4", "26", "60", "0.10");

        releaseGate(List.of("G-1", "G-2"), "0.10", "carol")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released.length()").value(2));

        mvc.perform(get("/api/measurements/{key}", "G-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }

    @Test
    void gateRejectsWholeBatchForMissingEnvironmentAndKeepsState() throws Exception {
        createCert("INS-1", "1", "0");
        createProfile("MODEL-A", "0.01", "0.001");
        // 无环境提交
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementBody("G-MISS", "INS-1", null, "10", "0", "99",
                                null, null, null, "alice")))
                .andExpect(status().isCreated());
        submitEnv("G-OK", "10", "10.2", "10.4", "25", "50", "0.05");

        releaseGate(List.of("G-MISS", "G-OK"), "0.10", "carol")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_GATE_REJECTED"))
                // 字典序稳定列出：G-MISS 在前
                .andExpect(jsonPath("$.failures[0].key").value("G-MISS"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("MISSING_ENVIRONMENT"))
                .andExpect(jsonPath("$.failures.length()").value(1));

        mvc.perform(get("/api/measurements/{key}", "G-OK"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/measurements/{key}", "G-MISS"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM calc_log WHERE operation='RELEASE'", Integer.class),
                "门禁失败回滚不占 calcKey");
    }

    @Test
    void gateRejectsCompensatedOutOfSpecAndUncertaintyExceeded() throws Exception {
        createCert("INS-1", "1", "0");
        createProfile("MODEL-A", "0.01", "0.001");
        // 补偿后 10.3 超出上限 10.25 → COMPENSATED_OUT_OF_SPEC
        submitEnv("G-SPEC", "10", "0", "10.25", "25", "50", "0.05");
        // 不确定度 0.2 > 0.1 → UNCERTAINTY_EXCEEDED（补偿值合格）
        submitEnv("G-UNC", "10", "10.2", "10.4", "25", "50", "0.2");

        releaseGate(List.of("G-SPEC", "G-UNC"), "0.1", "carol")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[?(@.key=='G-SPEC')].reasons[0]")
                        .value("COMPENSATED_OUT_OF_SPEC"))
                .andExpect(jsonPath("$.failures[?(@.key=='G-UNC')].reasons[0]")
                        .value("UNCERTAINTY_EXCEEDED"))
                .andExpect(jsonPath("$.failures.length()").value(2));
    }

    @Test
    void profileVersionUpdateOnlyAffectsLaterMeasurements() throws Exception {
        createCert("INS-1", "1", "0");
        long v1 = createProfile("MODEL-A", "0.01", "0.001");
        submitEnv("OLD", "10", "0", "99", "25", "50", "0.05");          // 10.300000 @ v1
        long v2 = createProfile("MODEL-A", "0.02", "0.002");          // 新版本
        submitEnv("NEW", "10", "0", "99", "25", "50", "0.05");          // 10 + 0.5 + 0.1 = 10.600000 @ v2

        mvc.perform(get("/api/measurements/{key}", "OLD"))
                .andExpect(jsonPath("$.compensationProfileId").value((int) v1))
                .andExpect(jsonPath("$.compensationVersionNo").value(1))
                .andExpect(jsonPath("$.compensatedValue").value("10.300000"));
        mvc.perform(get("/api/measurements/{key}", "NEW"))
                .andExpect(jsonPath("$.compensationProfileId").value((int) v2))
                .andExpect(jsonPath("$.compensationVersionNo").value(2))
                .andExpect(jsonPath("$.compensatedValue").value("10.600000"));
    }

    @Test
    void recalculateCreatesVersionChainAndReleasedSnapshotIsImmutable() throws Exception {
        createCert("INS-1", "1", "0");
        createProfile("MODEL-A", "0.01", "0.001");
        submitEnv("RC-1", "10", "0", "99", "25", "50", "0.05");        // v1: 10.300000

        // 更新系数版本后重算
        createProfile("MODEL-A", "0.02", "0.002");
        recalculate("RC-1", "MODEL-A", "25", "50", "0.05")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.compensationVersionNo").value(2))
                .andExpect(jsonPath("$.compensatedValue").value("10.600000"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].compensationVersionNo").value(1))
                .andExpect(jsonPath("$.versions[1].compensationVersionNo").value(2))
                .andExpect(jsonPath("$.versions[1].parentVersionNo").value(1));

        // 相同环境/系数重算：重放首次结果，不新增版本
        recalculate("RC-1", "MODEL-A", "25", "50", "0.05")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.versions.length()").value(2));

        // 放行后重算被拒，快照不改写
        releaseGate(List.of("RC-1"), "0.10", "carol").andExpect(status().isOk());
        recalculate("RC-1", "MODEL-A", "30", "50", "0.05")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_RELEASED"));
        mvc.perform(get("/api/measurements/{key}", "RC-1"))
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.versions.length()").value(2));
    }

    @Test
    void rejectTransitionsAndRecalculateRestoresPending() throws Exception {
        createCert("INS-1", "1", "0");
        createProfile("MODEL-A", "0.01", "0.001");
        submitEnv("RJ-1", "10", "0", "99", "25", "50", "0.05");

        mvc.perform(post("/api/measurements/{key}/reject", "RJ-1")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"环境异常\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejects[0].rejectedBy").value("carol"))
                .andExpect(jsonPath("$.rejects[0].reason").value("环境异常"));

        // 重复驳回 409
        mvc.perform(post("/api/measurements/{key}/reject", "RJ-1")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"再次驳回\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REJECTED"));

        // 重算修订后回到待放行，驳回历史保留
        recalculate("RJ-1", "MODEL-A", "26", "55", "0.05")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.rejects.length()").value(1));

        // 已放行测量不可驳回
        submitEnv("RJ-2", "10", "0", "99", "25", "50", "0.05");
        releaseGate(List.of("RJ-2"), "0.1", "erin").andExpect(status().isOk());
        mvc.perform(post("/api/measurements/{key}/reject", "RJ-2")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"试图驳回\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_RELEASED"));
    }

    @Test
    void diagnosticsReportsGateWithoutStateChange() throws Exception {
        createCert("INS-1", "1", "0");
        createProfile("MODEL-A", "0.01", "0.001");
        submitEnv("D-OK", "10", "10.2", "10.4", "25", "50", "0.05");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementBody("D-MISS", "INS-1", null, "10", "0", "99",
                                null, null, null, "alice")))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/measurements/release/diagnostics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"D-OK\",\"D-MISS\"],\"uncertaintyLimit\":\"0.1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pass").value(false))
                .andExpect(jsonPath("$.uncertaintyLimit").value("0.1"))
                .andExpect(jsonPath("$.items[?(@.key=='D-OK')].reasons.length()").value(0))
                .andExpect(jsonPath("$.items[?(@.key=='D-MISS')].reasons[0]")
                        .value("MISSING_ENVIRONMENT"));

        // 诊断不改变状态
        mvc.perform(get("/api/measurements/{key}", "D-OK"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void successfulOperationsRecordCalcFingerprint() throws Exception {
        createCert("INS-1", "1", "0");
        long profileId = createProfile("MODEL-A", "0.01", "0.001");
        submitEnv("F-1", "10", "10.2", "10.4", "25", "50", "0.05");
        releaseGate(List.of("F-1"), "0.1", "carol").andExpect(status().isOk());

        Map<String, Object> submitLog = jdbc.queryForMap(
                "SELECT * FROM calc_log WHERE operation='SUBMIT'");
        assertNotNull(submitLog.get("fingerprint"));
        assertTrue(((String) submitLog.get("fingerprint")).length() == 64);
        String stored = (String) submitLog.get("result_json");
        JsonNode node = JSON.readTree(stored);
        assertEquals("F-1", node.get("measurementKey").asText());
        assertEquals(profileId, node.get("compensationProfileId").asLong());

        Integer releases = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calc_log WHERE operation='RELEASE' AND http_status=200", Integer.class);
        assertEquals(1, releases);
    }

    private org.springframework.test.web.servlet.ResultActions submitEnv(
            String key, String reading, String lower, String upper,
            String temp, String humidity, String uncertainty) throws Exception {
        return mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                .content(measurementBody(key, "INS-1", "MODEL-A", reading, lower, upper,
                        temp, humidity, uncertainty, "alice")));
    }

    private org.springframework.test.web.servlet.ResultActions releaseGate(
            List<String> keys, String limit, String actor) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keys", keys);
        body.put("uncertaintyLimit", limit);
        return mvc.perform(post("/api/measurements/release")
                .header("X-Actor-Id", "carol".equals(actor) ? "carol" : actor)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private org.springframework.test.web.servlet.ResultActions recalculate(
            String key, String model, String temp, String humidity, String uncertainty) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("measurementKey", key);
        body.put("instrumentModel", model);
        body.put("temperature", temp);
        body.put("humidity", humidity);
        if (uncertainty != null) {
            body.put("uncertainty", uncertainty);
        }
        return mvc.perform(post("/api/measurements/recalculate")
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }
}
