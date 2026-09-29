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
 * 不确定度预算 H2 数据库测试：多分量登记、RSS 四位小数、重名/非法值 422、
 * 整体替换先校验完整后态（任一失败不写新版本、不改放行状态）、版本只增不改、版本过期 410。
 */
@SpringBootTest
@AutoConfigureMockMvc
class UncertaintyBudgetApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_snapshot_component");
        jdbc.update("DELETE FROM release_snapshot");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM uncertainty_component");
        jdbc.update("DELETE FROM uncertainty_budget");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM uncertainty_threshold");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
        jdbc.update("DELETE FROM idempotent_request");
    }

    private void createCertAndThreshold() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-U","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/thresholds").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-U","validFrom":"2026-01-01T00:00:00Z",
                                 "threshold":"5.0000"}"""))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-U","instrumentId":"INS-U",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}"""))
                .andExpect(status().isCreated());
    }

    private String budgetBody(String key, Integer version, String componentsJson) {
        return """
                {"measurementKey":"%s","expectedVersion":%s,"components":%s}
                """.formatted(key, version == null ? "null" : version.toString(), componentsJson);
    }

    @Test
    void registerMultipleComponentsCombinesRssToFourDecimals() throws Exception {
        createCertAndThreshold();
        mvc.perform(post("/api/measurements/M-U/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(budgetBody("M-U", 1, """
                                [{"name":"repeatability","magnitude":"3"},
                                 {"name":"reference","magnitude":"4"}]""")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurementKey").value("M-U"))
                .andExpect(jsonPath("$.measurementVersion").value(1))
                .andExpect(jsonPath("$.budgetVersion").value(1))
                .andExpect(jsonPath("$.updatedBy").value("dave"))
                .andExpect(jsonPath("$.combinedUncertainty").value("5.0000"))
                .andExpect(jsonPath("$.formula").value("RSS_ROOT_SUM_OF_SQUARES"))
                // 按名称升序（reference < repeatability）
                .andExpect(jsonPath("$.components[0].name").value("reference"))
                .andExpect(jsonPath("$.components[1].name").value("repeatability"));

        mvc.perform(get("/api/measurements/M-U"))
                .andExpect(jsonPath("$.budgetVersion").value(1))
                .andExpect(jsonPath("$.combinedUncertainty").value("5.0000"))
                .andExpect(jsonPath("$.components.length()").value(2));
    }

    @Test
    void duplicateComponentNameRejected422AndNoBudgetWritten() throws Exception {
        createCertAndThreshold();
        mvc.perform(post("/api/measurements/M-U/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(budgetBody("M-U", 1, """
                                [{"name":"repeat","magnitude":"1"},
                                 {"name":"repeat","magnitude":"2"}]""")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_COMPONENT_NAME"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM uncertainty_budget", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM uncertainty_component", Integer.class));
        mvc.perform(get("/api/measurements/M-U/budget"))
                .andExpect(jsonPath("$.budgetVersion").value(0))
                .andExpect(jsonPath("$.combinedUncertainty").value("0.0000"))
                .andExpect(jsonPath("$.updatedAt").doesNotExist());
    }

    @Test
    void negativeAndMalformedMagnitudeRejected422() throws Exception {
        createCertAndThreshold();
        mvc.perform(post("/api/measurements/M-U/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(budgetBody("M-U", 1, """
                                [{"name":"ok","magnitude":"1"},
                                 {"name":"bad","magnitude":"-0.5"}]""")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_COMPONENT"));
        mvc.perform(post("/api/measurements/M-U/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(budgetBody("M-U", 1, """
                                [{"name":"bad","magnitude":"abc"}]""")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/measurements/M-U/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(budgetBody("M-U", 1, """
                                [{"name":"","magnitude":"1"}]""")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_COMPONENT"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM uncertainty_budget", Integer.class));
    }

    @Test
    void failedCompletePoststateKeepsPreviousBudgetAndReleaseStatusUntouched() throws Exception {
        createCertAndThreshold();
        // 先成功登记预算 v1=[3,4] → 5.0000
        mvc.perform(post("/api/measurements/M-U/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(budgetBody("M-U", 1, """
                                [{"name":"a","magnitude":"3"},{"name":"b","magnitude":"4"}]""")))
                .andExpect(status().isOk());
        // 完整后态中混入非法分量：整组拒绝，不产生 v2，放行状态不变
        mvc.perform(post("/api/measurements/M-U/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(budgetBody("M-U", 1, """
                                [{"name":"a","magnitude":"9"},
                                 {"name":"b","magnitude":"-1"}]""")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/measurements/M-U/budget"))
                .andExpect(jsonPath("$.budgetVersion").value(1))
                .andExpect(jsonPath("$.combinedUncertainty").value("5.0000"))
                .andExpect(jsonPath("$.components.length()").value(2));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM uncertainty_budget", Integer.class));
        assertEquals("PENDING", jdbc.queryForMap(
                "SELECT status FROM measurement WHERE measurement_key='M-U'").get("status"));
    }

    @Test
    void budgetVersionsAreAppendOnlyAndEmptyBudgetIsTracked() throws Exception {
        createCertAndThreshold();
        mvc.perform(post("/api/measurements/M-U/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(budgetBody("M-U", 1, "[]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetVersion").value(1))
                .andExpect(jsonPath("$.components.length()").value(0));
        mvc.perform(post("/api/measurements/M-U/budget").header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(budgetBody("M-U", 1, """
                                [{"name":"solo","magnitude":"0.0001"}]""")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetVersion").value(2))
                .andExpect(jsonPath("$.updatedBy").value("erin"));
        // 历史版本只增不改：v1 头与 v2 明细均在
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM uncertainty_budget", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM uncertainty_component WHERE budget_version = 2", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM uncertainty_component WHERE budget_version = 1", Integer.class));
    }

    @Test
    void staleExpectedVersionReturns410() throws Exception {
        createCertAndThreshold();
        // 先修订出 v2（期望版本 1）
        mvc.perform(post("/api/measurements/revise").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-U","expectedVersion":1,"instrumentId":"INS-U",
                                 "measuredAt":"2026-06-02T00:00:00Z","reading":"2",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 再以过期基准版本 1 改预算 → 410
        mvc.perform(post("/api/measurements/M-U/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(budgetBody("M-U", 1, """
                                [{"name":"a","magnitude":"1"}]""")))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("STALE_MEASUREMENT_VERSION"));
        // expectedVersion 缺省时不做版本校验，按最新版本成功
        mvc.perform(post("/api/measurements/M-U/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(budgetBody("M-U", null, """
                                [{"name":"a","magnitude":"1"}]""")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurementVersion").value(2));
    }

    @Test
    void budgetOnUnknownMeasurementReturns404AndZeroMagnitudeAllowed() throws Exception {
        createCertAndThreshold();
        mvc.perform(post("/api/measurements/NOPE/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(budgetBody("NOPE", 1, """
                                [{"name":"a","magnitude":"0"}]""")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/measurements/M-U/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(budgetBody("M-U", 1, """
                                [{"name":"zero","magnitude":"0"}]""")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.combinedUncertainty").value("0.0000"));
    }
}
