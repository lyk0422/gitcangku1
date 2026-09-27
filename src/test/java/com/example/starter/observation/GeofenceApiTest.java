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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测地理围栏 API 的主流程、失败分支与幂等边界测试（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GeofenceApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM submission_adjudication");
        jdbcTemplate.update("DELETE FROM observation_submission");
        jdbcTemplate.update("DELETE FROM geofence_version");
        jdbcTemplate.update("DELETE FROM geofence_fence");
        jdbcTemplate.update("DELETE FROM geofence_site");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private ResultActions createFence(String requestId, String operator, String fenceId, String siteId,
                                      String minLat, String maxLat, String minLon, String maxLon,
                                      String fromUtc, String toUtc) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("fenceId", fenceId);
        body.put("siteId", siteId);
        body.put("minLat", minLat);
        body.put("maxLat", maxLat);
        body.put("minLon", minLon);
        body.put("maxLon", maxLon);
        body.put("effectiveFromUtc", fromUtc);
        body.put("effectiveToUtc", toUtc);
        return mockMvc.perform(post("/api/geofences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions reviseFence(String requestId, String operator, String fenceId,
                                      String minLat, String maxLat, String minLon, String maxLon,
                                      String fromUtc, String toUtc) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("minLat", minLat);
        body.put("maxLat", maxLat);
        body.put("minLon", minLon);
        body.put("maxLon", maxLon);
        body.put("effectiveFromUtc", fromUtc);
        body.put("effectiveToUtc", toUtc);
        return mockMvc.perform(post("/api/geofences/{id}/revisions", fenceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    // ---------- 创建与查询 ----------

    @Test
    void createFenceReturnsVersionOneAndIsQueryable() throws Exception {
        createFence("req-g1", "op-1", "fence-1", "site-1",
                "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fenceId").value("fence-1"))
                .andExpect(jsonPath("$.siteId").value("site-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.minLat").value(31.0))
                .andExpect(jsonPath("$.maxLat").value(32.0))
                .andExpect(jsonPath("$.minLon").value(121.0))
                .andExpect(jsonPath("$.maxLon").value(122.0))
                .andExpect(jsonPath("$.effectiveFromUtc").value("2026-09-01T00:00:00Z"))
                .andExpect(jsonPath("$.effectiveToUtc").value("2026-10-01T00:00:00Z"))
                .andExpect(jsonPath("$.operator").value("op-1"))
                .andExpect(jsonPath("$.createdAtUtc").exists());

        mockMvc.perform(get("/api/geofences/{id}", "fence-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.minLat").value(31.0));

        mockMvc.perform(get("/api/geofences").param("siteId", "site-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fenceId").value("fence-1"));

        mockMvc.perform(get("/api/geofences/{id}", "fence-missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createFenceWithInvalidGeometryOrIntervalReturns422() throws Exception {
        // 纬度超范围
        createFence("req-g2", "op-1", "fence-2", "site-1",
                "-91", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isUnprocessableEntity());
        // 经度超范围
        createFence("req-g3", "op-1", "fence-2", "site-1",
                "31.0", "32.0", "121.0", "181",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isUnprocessableEntity());
        // 纬度边界倒置
        createFence("req-g4", "op-1", "fence-2", "site-1",
                "32.0", "31.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isUnprocessableEntity());
        // 经度边界相等（零宽度）
        createFence("req-g5", "op-1", "fence-2", "site-1",
                "31.0", "32.0", "121.0", "121.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isUnprocessableEntity());
        // 生效区间非法（起点不早于终点）
        createFence("req-g6", "op-1", "fence-2", "site-1",
                "31.0", "32.0", "121.0", "122.0",
                "2026-10-01T00:00:00Z", "2026-09-01T00:00:00Z")
                .andExpect(status().isUnprocessableEntity());

        // 全部失败：没有任何围栏与请求记录落库
        Integer fenceRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geofence_fence", Integer.class);
        assertThat(fenceRows).isZero();
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log", Integer.class);
        assertThat(requestRows).isZero();
    }

    @Test
    void failedFenceRequestDoesNotOccupyRequestId() throws Exception {
        createFence("req-g7", "op-1", "fence-7", "site-1",
                "-91", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isUnprocessableEntity());
        // 同一 requestId 修正参数后可成功（失败不占键）
        createFence("req-g7", "op-1", "fence-7", "site-1",
                "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isCreated());
    }

    // ---------- 重叠校验 ----------

    @Test
    void overlappingActiveFenceInSameSiteReturns409() throws Exception {
        createFence("req-g8", "op-1", "fence-a", "site-1",
                "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isCreated());

        // 矩形相交且生效区间相交：409
        createFence("req-g9", "op-1", "fence-b", "site-1",
                "31.5", "32.5", "121.5", "122.5",
                "2026-09-15T00:00:00Z", "2026-11-01T00:00:00Z")
                .andExpect(status().isConflict());

        // 矩形不相交：允许
        createFence("req-g10", "op-1", "fence-c", "site-1",
                "40.0", "41.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isCreated());

        // 区间端点相接（左闭右开，[T2,T3) 与 [T1,T2) 不重叠）：允许
        createFence("req-g11", "op-1", "fence-d", "site-1",
                "31.0", "32.0", "121.0", "122.0",
                "2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z")
                .andExpect(status().isCreated());

        // 不同站点即使完全重叠也允许
        createFence("req-g12", "op-1", "fence-e", "site-2",
                "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isCreated());
    }

    @Test
    void duplicateFenceIdReturns409() throws Exception {
        createFence("req-g13", "op-1", "fence-1", "site-1",
                "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isCreated());
        createFence("req-g14", "op-1", "fence-1", "site-9",
                "40.0", "41.0", "10.0", "11.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isConflict());
    }

    // ---------- 改版与版本历史 ----------

    @Test
    void reviseFenceAppendsImmutableVersion() throws Exception {
        createFence("req-g15", "op-1", "fence-1", "site-1",
                "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isCreated());

        reviseFence("req-g16", "op-2", "fence-1",
                "31.0", "33.0", "121.0", "123.0",
                "2026-10-01T00:00:00Z", "2026-12-01T00:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.maxLat").value(33.0))
                .andExpect(jsonPath("$.operator").value("op-2"));

        // 当前指针指向版本 2
        mockMvc.perform(get("/api/geofences/{id}", "fence-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.maxLat").value(33.0));

        // 版本历史完整且旧版本不可变
        mockMvc.perform(get("/api/geofences/{id}/versions", "fence-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].maxLat").value(32.0))
                .andExpect(jsonPath("$[0].effectiveToUtc").value("2026-10-01T00:00:00Z"))
                .andExpect(jsonPath("$[1].version").value(2))
                .andExpect(jsonPath("$[1].maxLat").value(33.0));
    }

    @Test
    void reviseFenceOverlapExcludesSelf() throws Exception {
        createFence("req-g17", "op-1", "fence-1", "site-1",
                "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isCreated());
        createFence("req-g18", "op-1", "fence-2", "site-1",
                "40.0", "41.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isCreated());

        // 改版与自身旧版本重叠不算冲突
        reviseFence("req-g19", "op-1", "fence-1",
                "31.0", "32.5", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isOk());

        // 改版与兄弟围栏当前版本重叠：409
        reviseFence("req-g20", "op-1", "fence-1",
                "40.5", "41.5", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isConflict());

        reviseFence("req-g21", "op-1", "fence-missing",
                "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isNotFound());
    }

    // ---------- 幂等 ----------

    @Test
    void fenceRequestIdReplayReturnsSnapshotAndDifferentParamsReturn409() throws Exception {
        String firstBody = createFence("req-g22", "op-1", "fence-1", "site-1",
                "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // 同参重放：返回首次完整响应快照
        String replayBody = createFence("req-g22", "op-1", "fence-1", "site-1",
                "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(replayBody).isEqualTo(firstBody);

        // 异参复用：409
        createFence("req-g22", "op-1", "fence-1", "site-1",
                "31.0", "33.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
                .andExpect(status().isConflict());

        // 重放不产生重复围栏版本
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geofence_version WHERE fence_id = 'fence-1'", Integer.class);
        assertThat(versionRows).isEqualTo(1);
    }
}
