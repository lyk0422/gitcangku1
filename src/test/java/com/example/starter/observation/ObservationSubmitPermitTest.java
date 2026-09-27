package com.example.starter.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 提交观测的许可强制测试：唯一命中、计数上限、只扣减命中许可、许可版本与复核标记查询、
 * permitKey 重放与失败不占键（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ObservationSubmitPermitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PermitService permitService;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM permit_review_snapshot");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        jdbcTemplate.update("DELETE FROM sampling_permit");
        jdbcTemplate.update("DELETE FROM permit_device_lock");
    }

    private void createPermit(String requestId, String permitId, String deviceId,
                              int xMin, int xMax, int yMin, int yMax,
                              String validFrom, String validTo, int maxObservations) {
        permitService.create(new CreatePermitRequest(requestId, permitId, deviceId,
                xMin, xMax, yMin, yMax, Instant.parse(validFrom), Instant.parse(validTo),
                maxObservations, 1));
    }

    private ResultActions submit(String requestId, String observationId, String deviceId,
                                 int gridX, int gridY, String collectedAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", "站点A");
        body.put("reading", "1.0");
        body.put("note", "备注");
        body.put("deviceId", deviceId);
        body.put("gridX", gridX);
        body.put("gridY", gridY);
        body.put("collectedAtUtc", collectedAt);
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int usedCount(String permitId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT used_count FROM sampling_permit WHERE permit_id = ?", Integer.class, permitId);
        return count == null ? -1 : count;
    }

    @Test
    void submitWithoutPermitReturns422AndLeavesNothing() throws Exception {
        submit("req-n1", "obs-1", "DEV-1", 5, 5, "2026-03-01T00:00:00Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("PERMIT_NOT_FOUND"));
        // 不留半成品状态：无观测、无版本、不占键
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(status().isNotFound());
        Integer versions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-1'", Integer.class);
        assertThat(versions).isZero();
        Integer logRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-n1'", Integer.class);
        assertThat(logRows).isZero();
    }

    @Test
    void submitOutsideGridOrTimeReturns422() throws Exception {
        createPermit("req-g1", "permit-1", "DEV-1", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z", 100);
        // 网格越界
        submit("req-g2", "obs-1", "DEV-1", 50, 5, "2026-03-01T00:00:00Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("PERMIT_NOT_FOUND"));
        // 时刻早于有效期
        submit("req-g3", "obs-2", "DEV-1", 5, 5, "2025-12-01T00:00:00Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("PERMIT_NOT_FOUND"));
        // 时刻等于有效期结束（右开）：不命中
        submit("req-g4", "obs-3", "DEV-1", 5, 5, "2026-06-01T00:00:00Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("PERMIT_NOT_FOUND"));
        // 时刻等于有效期起始（左闭）：命中
        submit("req-g5", "obs-4", "DEV-1", 5, 5, "2026-01-01T00:00:00Z")
                .andExpect(status().isCreated());
        assertThat(usedCount("permit-1")).isEqualTo(1);
    }

    @Test
    void submitWithAmbiguousPermitReturns422() throws Exception {
        createPermit("req-a1", "permit-1", "DEV-1", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z", 100);
        // 直接写入一个与现有许可重叠的许可（绕过创建时的交集校验），制造命中不唯一
        jdbcTemplate.update(
                "INSERT INTO sampling_permit (permit_id, device_id, grid_x_min, grid_x_max, "
                        + "grid_y_min, grid_y_max, valid_from_utc, valid_to_utc, max_observations, "
                        + "used_count, permit_version, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                "permit-dup", "DEV-1", 0, 10, 0, 10,
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-12-31T00:00:00Z")),
                100, 0, 1, "ACTIVE");
        submit("req-a2", "obs-1", "DEV-1", 5, 5, "2026-03-01T00:00:00Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("PERMIT_AMBIGUOUS"));
        // 命中不唯一时不扣减任何许可计数
        assertThat(usedCount("permit-1")).isZero();
        assertThat(usedCount("permit-dup")).isZero();
    }

    @Test
    void submitExceedingPermitLimitReturns422() throws Exception {
        createPermit("req-l1", "permit-1", "DEV-1", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z", 1);
        submit("req-l2", "obs-1", "DEV-1", 5, 5, "2026-03-01T00:00:00Z")
                .andExpect(status().isCreated());
        submit("req-l3", "obs-2", "DEV-1", 5, 5, "2026-03-01T00:00:00Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("PERMIT_LIMIT_EXCEEDED"));
        // 超限不扣减、不留观测
        assertThat(usedCount("permit-1")).isEqualTo(1);
        mockMvc.perform(get("/api/observations/{id}", "obs-2"))
                .andExpect(status().isNotFound());
    }

    @Test
    void submitDecrementsOnlyTheHitPermit() throws Exception {
        createPermit("req-d1", "permit-a", "DEV-1", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z", 100);
        createPermit("req-d2", "permit-b", "DEV-1", 20, 30, 0, 10,
                "2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z", 100);
        submit("req-d3", "obs-1", "DEV-1", 25, 5, "2026-03-01T00:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permitId").value("permit-b"));
        assertThat(usedCount("permit-a")).isZero();
        assertThat(usedCount("permit-b")).isEqualTo(1);
    }

    @Test
    void submitResponseExposesPermitVersionAndReviewFlag() throws Exception {
        createPermit("req-q1", "permit-1", "DEV-1", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z", 100);
        submit("req-q2", "obs-1", "DEV-1", 5, 5, "2026-03-01T00:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceId").value("DEV-1"))
                .andExpect(jsonPath("$.gridX").value(5))
                .andExpect(jsonPath("$.gridY").value(5))
                .andExpect(jsonPath("$.collectedAtUtc").value("2026-03-01T00:00:00Z"))
                .andExpect(jsonPath("$.permitId").value("permit-1"))
                .andExpect(jsonPath("$.permitVersion").value(1))
                .andExpect(jsonPath("$.reviewStatus").value("NONE"));
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permitId").value("permit-1"))
                .andExpect(jsonPath("$.permitVersion").value(1))
                .andExpect(jsonPath("$.reviewStatus").value("NONE"));
    }

    @Test
    void samePermitKeyReplayReturnsOriginalWithoutDoubleCount() throws Exception {
        createPermit("req-k1", "permit-1", "DEV-1", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z", 100);
        submit("req-k2", "obs-1", "DEV-1", 5, 5, "2026-03-01T00:00:00Z")
                .andExpect(status().isCreated());
        // 同键同参重放：返回原结果，不重复扣减计数、不产生新版本
        submit("req-k2", "obs-1", "DEV-1", 5, 5, "2026-03-01T00:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(usedCount("permit-1")).isEqualTo(1);
        Integer versions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-1'", Integer.class);
        assertThat(versions).isEqualTo(1);
        // 同键异参：409
        submit("req-k2", "obs-1", "DEV-1", 6, 5, "2026-03-01T00:00:00Z")
                .andExpect(status().isConflict());
    }

    @Test
    void replayAfterPermitRevokedStillReturnsOriginalResult() throws Exception {
        createPermit("req-w1", "permit-1", "DEV-1", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z", 100);
        submit("req-w2", "obs-1", "DEV-1", 5, 5, "2026-03-01T00:00:00Z")
                .andExpect(status().isCreated());
        permitService.revoke("permit-1", new RevokePermitRequest("req-w3"));
        // 许可已撤销，但同键重放仍返回首次成功结果
        submit("req-w2", "obs-1", "DEV-1", 5, 5, "2026-03-01T00:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permitId").value("permit-1"));
        assertThat(usedCount("permit-1")).isEqualTo(1);
    }
}
