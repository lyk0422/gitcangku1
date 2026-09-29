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
 * 阈值版本与放行不确定度门限 H2 测试：阈值缺失 422、超阈值整批 409 且不部分提交、
 * 含端点放行、阈值左闭右开版本匹配、阈值修订不改写旧放行快照。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ThresholdAndReleaseGateTest {

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

    private void cert() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-G","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2030-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
    }

    private void measurement(String key) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"INS-G",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}""".formatted(key)))
                .andExpect(status().isCreated());
    }

    private void threshold(String from, String value) throws Exception {
        mvc.perform(post("/api/thresholds").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-G","validFrom":"%s","threshold":"%s"}"""
                                .formatted(from, value)))
                .andExpect(status().isCreated());
    }

    private void budget(String key, String components) throws Exception {
        mvc.perform(post("/api/measurements/" + key + "/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","components":%s}""".formatted(key, components)))
                .andExpect(status().isOk());
    }

    private int release(String key) throws Exception {
        return mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"" + key + "\"]}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void missingThresholdRejectsReleaseWith422AndKeepsPending() throws Exception {
        cert();
        measurement("M-1");
        budget("M-1", "[{\"name\":\"a\",\"magnitude\":\"1\"}]");
        // 未登记任何阈值 → 422
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-1\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_UNPROCESSABLE"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("THRESHOLD_MISSING"));
        assertEquals("PENDING", jdbc.queryForMap(
                "SELECT status FROM measurement WHERE measurement_key='M-1'").get("status"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
    }

    @Test
    void combinedUncertaintyExceedingThresholdRejectsWholeBatchWithoutPartialResult() throws Exception {
        cert();
        threshold("2026-01-01T00:00:00Z", "4.9999");
        measurement("M-A");
        measurement("M-B");
        budget("M-A", "[{\"name\":\"a\",\"magnitude\":\"3\"},{\"name\":\"b\",\"magnitude\":\"4\"}]"); // 5.0000 > 4.9999
        budget("M-B", "[{\"name\":\"a\",\"magnitude\":\"1\"}]");                              // 1.0000 ≤ 4.9999
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-A\",\"M-B\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_REJECTED"))
                .andExpect(jsonPath("$.failures[0].key").value("M-A"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("UNCERTAINTY_EXCEEDS_THRESHOLD"))
                .andExpect(jsonPath("$.failures.length()").value(1));
        // 整批回滚：M-B 不得部分放行
        assertEquals("PENDING", jdbc.queryForMap(
                "SELECT status FROM measurement WHERE measurement_key='M-B'").get("status"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_snapshot", Integer.class));
    }

    @Test
    void combinedUncertaintyExactlyAtThresholdIsApprovedAndSnapshotFreezesBudgetAndThreshold() throws Exception {
        cert();
        threshold("2026-01-01T00:00:00Z", "5.0000");
        measurement("M-1");
        budget("M-1", "[{\"name\":\"a\",\"magnitude\":\"3\"},{\"name\":\"b\",\"magnitude\":\"4\"}]"); // 恰好 5.0000
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots[0].combinedUncertainty").value("5.0000"))
                .andExpect(jsonPath("$.snapshots[0].uncertaintyOk").value(true))
                .andExpect(jsonPath("$.snapshots[0].thresholdValue").value("5"))
                .andExpect(jsonPath("$.snapshots[0].thresholdVersion").value(1))
                .andExpect(jsonPath("$.snapshots[0].formula").value("RSS_ROOT_SUM_OF_SQUARES"))
                .andExpect(jsonPath("$.snapshots[0].decision").value("APPROVED"))
                .andExpect(jsonPath("$.snapshots[0].components.length()").value(2));

        // 固化快照可查询：测量键维度 + 数据库行
        mvc.perform(get("/api/measurements/M-1/snapshots"))
                .andExpect(jsonPath("$[0].measurementKey").value("M-1"))
                .andExpect(jsonPath("$[0].measurementVersion").value(1))
                .andExpect(jsonPath("$[0].passedBias").value(true))
                .andExpect(jsonPath("$[0].components[0].name").value("a"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot_component WHERE name='a'", Integer.class));
    }

    @Test
    void laterThresholdRevisionDoesNotRewriteOldSnapshotAndGovernsNewReleases() throws Exception {
        cert();
        threshold("2026-01-01T00:00:00Z", "5.0000");
        measurement("M-1");
        measurement("M-2");
        budget("M-1", "[{\"name\":\"a\",\"magnitude\":\"3\"},{\"name\":\"b\",\"magnitude\":\"4\"}]"); // 5.0000
        assertEquals(200, release("M-1"));

        // 阈值修订收紧到 0.0001，左闭右开：新版本 2027-01-01 生效；旧快照仍记录 thresholdVersion=1/value=5.0000
        threshold("2027-01-01T00:00:00Z", "0.0001");
        mvc.perform(get("/api/measurements/M-1/snapshots"))
                .andExpect(jsonPath("$[0].thresholdVersion").value(1))
                .andExpect(jsonPath("$[0].thresholdValue").value("5"));

        // 版本历史按版本号升序
        mvc.perform(get("/api/thresholds/INS-G"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[1].version").value(2))
                .andExpect(jsonPath("$[1].threshold").value("0.0001"));

        // 放行时刻 2026-06 仍落在 v1 区间 [v1.from, v2.from)，5.0000 仍满足 → 成功且快照引用 v1
        budget("M-2", "[{\"name\":\"a\",\"magnitude\":\"3\"},{\"name\":\"b\",\"magnitude\":\"4\"}]");
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots[0].thresholdVersion").value(1));

        // 新版本起点必须严格晚于旧版本 → 409
        mvc.perform(post("/api/thresholds").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-G","validFrom":"2026-06-01T00:00:00Z","threshold":"1"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("THRESHOLD_VERSION_ORDER"));
        // 负阈值 → 422
        mvc.perform(post("/api/thresholds").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-G","validFrom":"2028-01-01T00:00:00Z","threshold":"-1"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_THRESHOLD"));
    }
}
