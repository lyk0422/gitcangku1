package com.example.starter.calibration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.starter.calibration.service.BusinessClock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * requestId 幂等与时间区间 H2 测试：同参重放返回首次完整响应快照、异参 409、失败不占键、
 * 成功重放读取不改状态；诊断窗口 [from, to) 左闭右开（可控时钟）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyAndTimeWindowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BusinessClock clock;

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
        clock.setClock(Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC));
    }

    private void setup() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-I","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2030-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/thresholds").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-I","validFrom":"2026-01-01T00:00:00Z","threshold":"9"}"""))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-I","instrumentId":"INS-I",
                                 "measuredAt":"2026-03-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}"""))
                .andExpect(status().isCreated());
    }

    private String budgetJson(String magnitude) {
        return """
                {"measurementKey":"M-I","components":[{"name":"a","magnitude":"%s"}]}""".formatted(magnitude);
    }

    @Test
    void sameRequestIdReplaysFirstResponseSnapshotWithoutChangingState() throws Exception {
        setup();
        MvcResult first = mvc.perform(post("/api/measurements/M-I/budget")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "REQ-1")
                        .contentType(MediaType.APPLICATION_JSON).content(budgetJson("3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetVersion").value(1))
                .andReturn();
        String firstJson = first.getResponse().getContentAsString();

        // 推进时钟后重放：仍返回首次响应（时间/内容一致），且不新增预算版本
        clock.setClock(Clock.fixed(Instant.parse("2026-07-01T12:00:00Z"), ZoneOffset.UTC));
        MvcResult replay = mvc.perform(post("/api/measurements/M-I/budget")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "REQ-1")
                        .contentType(MediaType.APPLICATION_JSON).content(budgetJson("3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetVersion").value(1))
                .andReturn();
        assertEquals(firstJson, replay.getResponse().getContentAsString(), "重放必须返回首次完整响应快照");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM uncertainty_budget", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotent_request", Integer.class));
    }

    @Test
    void sameRequestIdDifferentParametersReturns409AndDoesNotApply() throws Exception {
        setup();
        mvc.perform(post("/api/measurements/M-I/budget")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "REQ-2")
                        .contentType(MediaType.APPLICATION_JSON).content(budgetJson("3")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/measurements/M-I/budget")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "REQ-2")
                        .contentType(MediaType.APPLICATION_JSON).content(budgetJson("4")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // 异参未生效：当前预算仍是首次 3
        mvc.perform(get("/api/measurements/M-I/budget"))
                .andExpect(jsonPath("$.combinedUncertainty").value("3.0000"));
    }

    @Test
    void failedRequestDoesNotOccupyKeyAndCanBeRetriedWithSameId() throws Exception {
        setup();
        // 首次失败（分量重名 422）：不占键
        mvc.perform(post("/api/measurements/M-I/budget")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "REQ-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-I","components":[
                                 {"name":"x","magnitude":"1"},{"name":"x","magnitude":"2"}]}"""))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotent_request", Integer.class),
                "失败请求不得占用 requestId");
        // 同一 requestId 可正常重试成功
        mvc.perform(post("/api/measurements/M-I/budget")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "REQ-3")
                        .contentType(MediaType.APPLICATION_JSON).content(budgetJson("1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetVersion").value(1));
    }

    @Test
    void releaseIdempotencyReplayKeepsSingleHistoryAndReturnsFirstSnapshot() throws Exception {
        setup();
        mvc.perform(post("/api/measurements/M-I/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content(budgetJson("1")))
                .andExpect(status().isOk());
        String body = "{\"keys\":[\"M-I\"]}";
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "REQ-R")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots[0].thresholdVersion").value(1));
        // 同参重放：200 且只有一条放行历史与快照（不是 ALREADY_RELEASED 的 409）
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "REQ-R")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots[0].measurementVersion").value(1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM release_snapshot", Integer.class));
        // 不带 requestId 的重复放行仍是 409 ALREADY_RELEASED
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void failedReleaseDoesNotOccupyKeyAndDifferentParamsConflict() throws Exception {
        // 收紧阈值到 0.5000：预算 5.0000 放行失败；失败不占键，改预算后同 requestId 可重试成功
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-I","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2030-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/thresholds").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-I","validFrom":"2026-01-01T00:00:00Z","threshold":"0.5000"}"""))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-I","instrumentId":"INS-I",
                                 "measuredAt":"2026-03-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}"""))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/measurements/M-I/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content(budgetJson("5")))
                .andExpect(status().isOk());

        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "REQ-FAIL")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-I\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("UNCERTAINTY_EXCEEDS_THRESHOLD"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotent_request", Integer.class),
                "放行失败不得占用 requestId");
        assertEquals("PENDING", jdbc.queryForMap(
                "SELECT status FROM measurement WHERE measurement_key='M-I'").get("status"));

        // 修改预算使合成 0.2000 ≤ 0.5000，同 requestId 重试成功
        mvc.perform(post("/api/measurements/M-I/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content(budgetJson("0.2")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "REQ-FAIL")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-I\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots[0].combinedUncertainty").value("0.2000"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));

        // 同 requestId 异参（不同键集合）→ 409
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "REQ-FAIL")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-OTHER\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void diagnosticsWindowIsLeftClosedRightOpenUnderControlledClock() throws Exception {
        setup();
        mvc.perform(post("/api/measurements/M-I/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content(budgetJson("1")))
                .andExpect(status().isOk());
        // 放行发生在固定时钟 2026-06-01T12:00:00Z
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-I\"]}"))
                .andExpect(status().isOk());

        // 起点含（左闭）：from=12:00:00 命中
        mvc.perform(get("/api/diagnostics/snapshots")
                        .param("from", "2026-06-01T12:00:00Z")
                        .param("to", "2026-06-01T12:00:01Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        // 终点不含（右开）：to=12:00:00 不命中
        mvc.perform(get("/api/diagnostics/snapshots")
                        .param("from", "2026-06-01T11:59:59Z")
                        .param("to", "2026-06-01T12:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        // 查询不修改状态
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM release_snapshot", Integer.class));
        // 非法区间 400
        mvc.perform(get("/api/diagnostics/snapshots")
                        .param("from", "2026-06-01T12:00:00Z")
                        .param("to", "2026-06-01T11:00:00Z"))
                .andExpect(status().isBadRequest());
    }
}
