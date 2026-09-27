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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 站点矩形地理围栏 API 测试：主流程、422 校验分支、同站点区间重叠 409、
 * 版本化历史保留与幂等边界（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GeofenceApiTest {

    private static final String FROM = "2026-01-01T00:00:00Z";
    private static final String TO = "2027-01-01T00:00:00Z";

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
        jdbcTemplate.update("DELETE FROM geofence");
        jdbcTemplate.update("DELETE FROM geofence_site_lock");
        jdbcTemplate.update("DELETE FROM request_log");
    }

    private ResultActions createFence(String requestId, String fenceId, String siteId,
                                      String minLat, String maxLat, String minLon, String maxLon,
                                      String from, String to) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("fenceId", fenceId);
        body.put("siteId", siteId);
        body.put("minLatitude", new BigDecimal(minLat));
        body.put("maxLatitude", new BigDecimal(maxLat));
        body.put("minLongitude", new BigDecimal(minLon));
        body.put("maxLongitude", new BigDecimal(maxLon));
        body.put("effectiveFromUtc", from);
        body.put("effectiveToUtc", to);
        body.put("operator", "tester");
        return mockMvc.perform(post("/api/geofences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions updateFence(String requestId, String fenceId, int expectedVersion,
                                      String minLat, String maxLat, String minLon, String maxLon,
                                      String from, String to) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        body.put("minLatitude", new BigDecimal(minLat));
        body.put("maxLatitude", new BigDecimal(maxLat));
        body.put("minLongitude", new BigDecimal(minLon));
        body.put("maxLongitude", new BigDecimal(maxLon));
        body.put("effectiveFromUtc", from);
        body.put("effectiveToUtc", to);
        body.put("operator", "tester");
        return mockMvc.perform(post("/api/geofences/{id}/updates", fenceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int fenceRowCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM geofence", Integer.class);
        return count == null ? 0 : count;
    }

    private int requestLogCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class);
        return count == null ? 0 : count;
    }

    // ---------- 创建主流程 ----------

    @Test
    void createFenceReturnsVersionOne() throws Exception {
        createFence("req-g1", "fence-1", "site-A", "30", "40", "120", "130", FROM, TO)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fenceId").value("fence-1"))
                .andExpect(jsonPath("$.fenceVersion").value(1))
                .andExpect(jsonPath("$.siteId").value("site-A"))
                .andExpect(jsonPath("$.minLatitude").value(30))
                .andExpect(jsonPath("$.maxLatitude").value(40))
                .andExpect(jsonPath("$.minLongitude").value(120))
                .andExpect(jsonPath("$.maxLongitude").value(130))
                .andExpect(jsonPath("$.effectiveFromUtc").value(FROM))
                .andExpect(jsonPath("$.effectiveToUtc").value(TO))
                .andExpect(jsonPath("$.operator").value("tester"));

        mockMvc.perform(get("/api/geofences/{id}", "fence-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fenceVersion").value(1));
        mockMvc.perform(get("/api/sites/{siteId}/geofences", "site-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fenceId").value("fence-1"));
    }

    @Test
    void createDuplicateFenceIdReturns409() throws Exception {
        createFence("req-g2", "fence-1", "site-A", "30", "40", "120", "130", FROM, TO)
                .andExpect(status().isCreated());
        createFence("req-g3", "fence-1", "site-A", "30", "40", "120", "130", FROM, TO)
                .andExpect(status().isConflict());
        assertThat(fenceRowCount()).isEqualTo(1);
    }

    // ---------- 422 语义校验 ----------

    @Test
    void createWithLatitudeOutOfRangeReturns422() throws Exception {
        createFence("req-g4", "fence-1", "site-A", "-91", "40", "120", "130", FROM, TO)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.actualValue").value("-91"))
                .andExpect(jsonPath("$.requiredValue").value("[-90, 90]"));
        createFence("req-g5", "fence-1", "site-A", "30", "90.5", "120", "130", FROM, TO)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.actualValue").value("90.5"));
        assertThat(fenceRowCount()).isZero();
        assertThat(requestLogCount()).isZero();
    }

    @Test
    void createWithLongitudeOutOfRangeReturns422() throws Exception {
        createFence("req-g6", "fence-1", "site-A", "30", "40", "-180.5", "130", FROM, TO)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.actualValue").value("-180.5"))
                .andExpect(jsonPath("$.requiredValue").value("[-180, 180]"));
        createFence("req-g7", "fence-1", "site-A", "30", "40", "120", "181", FROM, TO)
                .andExpect(status().isUnprocessableEntity());
        assertThat(fenceRowCount()).isZero();
    }

    @Test
    void createWithInvertedBoundaryReturns422() throws Exception {
        createFence("req-g8", "fence-1", "site-A", "40", "30", "120", "130", FROM, TO)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.actualValue").value("40 >= 30"))
                .andExpect(jsonPath("$.requiredValue").value("minLatitude < maxLatitude"));
        createFence("req-g9", "fence-1", "site-A", "30", "40", "130", "130", FROM, TO)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.requiredValue").value("minLongitude < maxLongitude"));
        assertThat(fenceRowCount()).isZero();
    }

    @Test
    void createWithInvalidEffectiveIntervalReturns422() throws Exception {
        // 起始等于结束：空区间非法
        createFence("req-g10", "fence-1", "site-A", "30", "40", "120", "130", FROM, FROM)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.requiredValue").value("effectiveFromUtc < effectiveToUtc"));
        // 起始晚于结束
        createFence("req-g11", "fence-1", "site-A", "30", "40", "120", "130", TO, FROM)
                .andExpect(status().isUnprocessableEntity());
        assertThat(fenceRowCount()).isZero();
    }

    // ---------- 生效区间重叠 ----------

    @Test
    void overlappingFenceOnSameSiteReturns409() throws Exception {
        createFence("req-g12", "fence-1", "site-A", "30", "40", "120", "130", FROM, TO)
                .andExpect(status().isCreated());
        // 区间相交：409
        createFence("req-g13", "fence-2", "site-A", "35", "45", "120", "130",
                "2026-06-01T00:00:00Z", "2028-01-01T00:00:00Z")
                .andExpect(status().isConflict());
        // 左闭右开：紧邻区间 [TO, ...) 不重叠，允许
        createFence("req-g14", "fence-2", "site-A", "35", "45", "120", "130",
                TO, "2028-01-01T00:00:00Z")
                .andExpect(status().isCreated());
        // 不同站点相同区间不冲突
        createFence("req-g15", "fence-3", "site-B", "30", "40", "120", "130", FROM, TO)
                .andExpect(status().isCreated());
        assertThat(fenceRowCount()).isEqualTo(3);
    }

    // ---------- 变更与历史版本 ----------

    @Test
    void updateFenceCreatesNewVersionAndKeepsHistory() throws Exception {
        createFence("req-g16", "fence-1", "site-A", "30", "40", "120", "130", FROM, TO)
                .andExpect(status().isCreated());
        updateFence("req-g17", "fence-1", 1, "31", "39", "121", "129", FROM, TO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fenceVersion").value(2))
                .andExpect(jsonPath("$.minLatitude").value(31));

        // 当前版本为新值
        mockMvc.perform(get("/api/geofences/{id}", "fence-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fenceVersion").value(2))
                .andExpect(jsonPath("$.minLatitude").value(31));
        // 历史版本保持原值，不可变
        mockMvc.perform(get("/api/geofences/{id}/versions/{v}", "fence-1", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fenceVersion").value(1))
                .andExpect(jsonPath("$.minLatitude").value(30));
        assertThat(fenceRowCount()).isEqualTo(2);
    }

    @Test
    void updateWithMismatchedVersionReturns409() throws Exception {
        createFence("req-g18", "fence-1", "site-A", "30", "40", "120", "130", FROM, TO)
                .andExpect(status().isCreated());
        updateFence("req-g19", "fence-1", 7, "31", "39", "121", "129", FROM, TO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(1));
        assertThat(fenceRowCount()).isEqualTo(1);
    }

    @Test
    void updateOnMissingFenceReturns404() throws Exception {
        updateFence("req-g20", "fence-x", 1, "31", "39", "121", "129", FROM, TO)
                .andExpect(status().isNotFound());
        assertThat(requestLogCount()).isZero();
    }

    @Test
    void updateOverlappingOtherFenceReturns409() throws Exception {
        createFence("req-g21", "fence-1", "site-A", "30", "40", "120", "130", FROM, TO)
                .andExpect(status().isCreated());
        createFence("req-g22", "fence-2", "site-A", "50", "60", "120", "130",
                TO, "2028-01-01T00:00:00Z")
                .andExpect(status().isCreated());
        // 变更 fence-2 的区间使其与 fence-1 重叠：409
        updateFence("req-g23", "fence-2", 1, "50", "60", "120", "130",
                "2026-06-01T00:00:00Z", "2028-01-01T00:00:00Z")
                .andExpect(status().isConflict());
        // fence-2 当前版本仍为 1
        mockMvc.perform(get("/api/geofences/{id}", "fence-2"))
                .andExpect(jsonPath("$.fenceVersion").value(1));
    }

    // ---------- 查询 ----------

    @Test
    void getMissingFenceReturns404() throws Exception {
        mockMvc.perform(get("/api/geofences/{id}", "fence-x"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/geofences/{id}/versions/{v}", "fence-x", 1))
                .andExpect(status().isNotFound());
    }

    @Test
    void getMissingFenceVersionReturns404() throws Exception {
        createFence("req-g24", "fence-1", "site-A", "30", "40", "120", "130", FROM, TO)
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/geofences/{id}/versions/{v}", "fence-1", 9))
                .andExpect(status().isNotFound());
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdAndParamsReplaysOriginalResult() throws Exception {
        createFence("req-g25", "fence-1", "site-A", "30", "40", "120", "130", FROM, TO)
                .andExpect(status().isCreated());
        // 同键同参重放：返回首次成功结果，不新增围栏版本
        createFence("req-g25", "fence-1", "site-A", "30", "40", "120", "130", FROM, TO)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fenceVersion").value(1));
        assertThat(fenceRowCount()).isEqualTo(1);
        assertThat(requestLogCount()).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        createFence("req-g26", "fence-1", "site-A", "30", "40", "120", "130", FROM, TO)
                .andExpect(status().isCreated());
        createFence("req-g26", "fence-2", "site-A", "30", "40", "120", "130", FROM, TO)
                .andExpect(status().isConflict());
        createFence("req-g26", "fence-1", "site-A", "31", "40", "120", "130", FROM, TO)
                .andExpect(status().isConflict());
        assertThat(fenceRowCount()).isEqualTo(1);
    }

    @Test
    void failedRequestDoesNotOccupyRequestId() throws Exception {
        // 422 失败：不占键
        createFence("req-g27", "fence-1", "site-A", "-91", "40", "120", "130", FROM, TO)
                .andExpect(status().isUnprocessableEntity());
        // 同一 requestId 换上合法参数后应正常执行
        createFence("req-g27", "fence-1", "site-A", "30", "40", "120", "130", FROM, TO)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fenceVersion").value(1));
    }
}
