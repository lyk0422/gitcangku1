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
 * 测量修订版本链与旧证据固化 H2 测试：
 * 修订产生新版本、旧 PENDING 置 SUPERSEDED、旧 RELEASED 历史保留不回写、
 * 新版本无预算需重新登记、旧放行快照不被预算/阈值后续修改改写。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MeasurementRevisionTest {

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

    private void setup() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-V","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2030-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/thresholds").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-V","validFrom":"2026-01-01T00:00:00Z",
                                 "threshold":"9"}"""))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-V","instrumentId":"INS-V",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}"""))
                .andExpect(status().isCreated());
    }

    private String reviseBody(String key, int expected, String at, String reading) {
        return """
                {"measurementKey":"%s","expectedVersion":%d,"instrumentId":"INS-V",
                 "measuredAt":"%s","reading":"%s",
                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}""".formatted(key, expected, at, reading);
    }

    @Test
    void reviseCreatesNewVersionAndSupersedesPendingOldVersion() throws Exception {
        setup();
        mvc.perform(post("/api/measurements/revise").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody("M-V", 1, "2026-06-02T00:00:00Z", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.computedValue").value("2"))
                .andExpect(jsonPath("$.budgetVersion").value(0))
                .andExpect(jsonPath("$.combinedUncertainty").value("0.0000"));

        // 版本历史按版本号升序：v1 SUPERSEDED，v2 PENDING
        mvc.perform(get("/api/measurements/M-V/versions"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$[1].version").value(2))
                .andExpect(jsonPath("$[1].status").value("PENDING"));
        // 明细默认最新版本
        mvc.perform(get("/api/measurements/M-V"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void staleExpectedVersionOnReviseReturns410() throws Exception {
        setup();
        mvc.perform(post("/api/measurements/revise").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody("M-V", 1, "2026-06-02T00:00:00Z", "2")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/measurements/revise").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody("M-V", 1, "2026-06-03T00:00:00Z", "3")))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("STALE_MEASUREMENT_VERSION"));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key='M-V'", Integer.class));
    }

    @Test
    void reviseUnknownMeasurement404AndNoMatchingCertificate422() throws Exception {
        setup();
        mvc.perform(post("/api/measurements/revise").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody("NOPE", 1, "2026-06-02T00:00:00Z", "2")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/measurements/revise").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody("M-V", 1, "2031-06-02T00:00:00Z", "2")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void releasedOldVersionKeepsHistoryAndSnapshotAfterRevision() throws Exception {
        setup();
        // v1 登记预算并放行
        mvc.perform(post("/api/measurements/M-V/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-V","components":[
                                 {"name":"a","magnitude":"3"},{"name":"b","magnitude":"4"}]}"""))
                .andExpect(status().isOk());
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-V\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/measurements/usable")).andExpect(jsonPath("$.length()").value(1));

        // 修订：v1 已放行保持 RELEASED（不回写），v2 PENDING；当前可用结果归零
        mvc.perform(post("/api/measurements/revise").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody("M-V", 1, "2026-06-02T00:00:00Z", "2")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/measurements/M-V/versions"))
                .andExpect(jsonPath("$[0].status").value("RELEASED"))
                .andExpect(jsonPath("$[1].status").value("PENDING"));
        mvc.perform(get("/api/measurements/usable")).andExpect(jsonPath("$.length()").value(0));

        // 旧放行快照仍归属于 v1，不被修订改写
        mvc.perform(get("/api/measurements/M-V/snapshots"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].measurementVersion").value(1))
                .andExpect(jsonPath("$[0].combinedUncertainty").value("5.0000"));

        // v2 是全新预算（budgetVersion=0）：阈值存在但无分量，合成 0.0000 可放行
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-V\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots[0].measurementVersion").value(2))
                .andExpect(jsonPath("$.snapshots[0].budgetVersion").value(0));
        // 同一业务键两个版本各一条快照历史
        mvc.perform(get("/api/measurements/M-V/snapshots"))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void laterBudgetChangeDoesNotRewriteFrozenSnapshot() throws Exception {
        setup();
        mvc.perform(post("/api/measurements/M-V/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-V","components":[{"name":"a","magnitude":"3"}]}"""))
                .andExpect(status().isOk());
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-V\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots[0].combinedUncertainty").value("3.0000"));

        // 放行后修改预算为 4：当前明细变 4.0000，但固化快照仍是 3.0000
        mvc.perform(post("/api/measurements/M-V/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-V","components":[{"name":"a","magnitude":"4"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetVersion").value(2));
        mvc.perform(get("/api/measurements/M-V"))
                .andExpect(jsonPath("$.combinedUncertainty").value("4.0000"));
        mvc.perform(get("/api/measurements/M-V/snapshots"))
                .andExpect(jsonPath("$[0].combinedUncertainty").value("3.0000"))
                .andExpect(jsonPath("$[0].budgetVersion").value(1));
        // 快照分量明细仍是旧值
        assertEquals("3.000000", jdbc.queryForObject(
                "SELECT magnitude FROM release_snapshot_component ORDER BY id LIMIT 1", String.class));
    }
}
