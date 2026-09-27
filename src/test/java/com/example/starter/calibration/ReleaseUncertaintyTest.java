package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 放行与不确定度联合判定测试：阈值缺失/超限整批拒绝（含实际值、要求值、差额）、
 * 放行快照固化（后续阈值修订不改写）、已放行分量固化、放行幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReleaseUncertaintyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM budget_revision_component");
        jdbc.update("DELETE FROM budget_revision");
        jdbc.update("DELETE FROM uncertainty_component");
        jdbc.update("DELETE FROM instrument_threshold");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private void createCert(String instrument) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated());
    }

    private void submit(String key, String instrument, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"%s"}
                                """.formatted(key, instrument, by)))
                .andExpect(status().isCreated());
    }

    private void setThreshold(String instrument, String requestId, String threshold) throws Exception {
        mvc.perform(put("/api/instruments/{id}/threshold", instrument)
                        .header("X-Actor-Id", "setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"threshold\":\"" + threshold + "\"}"))
                .andExpect(status().isOk());
    }

    private void setComponents(String key, String requestId, int expectedVersion, String components)
            throws Exception {
        mvc.perform(put("/api/measurements/{key}/components", key)
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"expectedVersion\":"
                                + expectedVersion + ",\"components\":" + components + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void releaseRejectedWhenThresholdMissing() throws Exception {
        createCert("INS-1");
        submit("U-1", "INS-1", "alice");
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"U-1\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_REJECTED"))
                .andExpect(jsonPath("$.failures[0].key").value("U-1"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("THRESHOLD_MISSING"));
        mvc.perform(get("/api/measurements/{key}", "U-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, count);
    }

    @Test
    void releaseRejectedWhenUncertaintyExceededWithActualRequiredMargin() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "th-1", "0.4");
        submit("U-2", "INS-1", "alice");
        setComponents("U-2", "c-2", 1,
                "[{\"name\":\"a\",\"value\":\"0.3\"},{\"name\":\"b\",\"value\":\"0.4\"}]");

        // 合成 0.5 > 阈值 0.4 → 整批拒绝，明细含实际值、要求值、差额
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"U-2\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("UNCERTAINTY_EXCEEDED"))
                .andExpect(jsonPath("$.failures[0].detail")
                        .value("actual=0.5, required<=0.4, margin=-0.1"));
        mvc.perform(get("/api/measurements/{key}", "U-2"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void releaseSnapshotFrozenAgainstLaterThresholdRevision() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "th-1", "1");
        submit("U-3", "INS-1", "alice");
        setComponents("U-3", "c-3", 1,
                "[{\"name\":\"a\",\"value\":\"0.3\"},{\"name\":\"b\",\"value\":\"0.4\"}]");

        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"U-3\"]}"))
                .andExpect(status().isOk());

        // 放行快照：合成 0.5000、阈值 1、版本 1、口径固化
        mvc.perform(get("/api/measurements/{key}", "U-3"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releases[0].combinedUncertainty").value("0.5000"))
                .andExpect(jsonPath("$.releases[0].thresholdValue").value("1"))
                .andExpect(jsonPath("$.releases[0].thresholdVersion").value(1))
                .andExpect(jsonPath("$.releases[0].formula").value("RSS-4DP-HALF_UP"));

        // 后续阈值修订为 0.4：旧放行快照不改写；当前预算诊断转为超限
        setThreshold("INS-1", "th-2", "0.4");
        mvc.perform(get("/api/measurements/{key}", "U-3"))
                .andExpect(jsonPath("$.releases[0].thresholdValue").value("1"))
                .andExpect(jsonPath("$.releases[0].thresholdVersion").value(1));
        mvc.perform(get("/api/measurements/{key}/budget", "U-3"))
                .andExpect(jsonPath("$.threshold.version").value(2))
                .andExpect(jsonPath("$.margin").value("-0.1"))
                .andExpect(jsonPath("$.blockingReasons[0]").value("UNCERTAINTY_EXCEEDED"));
    }

    @Test
    void componentsFrozenAfterRelease() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "th-1", "1");
        submit("U-4", "INS-1", "alice");
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"U-4\"]}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/measurements/{key}/components", "U-4")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-4","expectedVersion":1,"components":[
                                  {"name":"a","value":"0.1"}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMPONENTS_FROZEN"));
    }

    @Test
    void batchAtomicWhenOneItemExceedsUncertainty() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "th-1", "0.4");
        submit("U-5A", "INS-1", "alice");
        submit("U-5B", "INS-1", "alice");
        setComponents("U-5B", "c-5", 1,
                "[{\"name\":\"a\",\"value\":\"0.3\"},{\"name\":\"b\",\"value\":\"0.4\"}]");

        // U-5B 超限 → 整批拒绝，U-5A 不被部分放行
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"U-5A\",\"U-5B\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures.length()").value(1))
                .andExpect(jsonPath("$.failures[0].key").value("U-5B"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("UNCERTAINTY_EXCEEDED"));
        mvc.perform(get("/api/measurements/{key}", "U-5A"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, count);
    }

    @Test
    void releaseIdempotencyReplayConflictAndFailureNotConsumingKey() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "th-1", "1");
        submit("U-6", "INS-1", "alice");

        String body = "{\"keys\":[\"U-6\"],\"requestId\":\"rel-1\"}";
        String first = mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String batchId = com.jayway.jsonpath.JsonPath.read(first, "$.batchId");

        // 同参重放：返回首次快照（同一 batchId），不产生第二条放行历史
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batchId));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, count);

        // 异参 → 409
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"U-6\",\"U-OTHER\"],\"requestId\":\"rel-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 失败（整批拒绝）不占键：rel-2 用于不存在的测量 → 409；修正后复用 rel-2 可成功
        submit("U-7", "INS-1", "alice");
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"U-MISSING\"],\"requestId\":\"rel-2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_REJECTED"));
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"U-7\"],\"requestId\":\"rel-2\"}"))
                .andExpect(status().isOk());
    }
}
