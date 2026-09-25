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
 * 观测提交（坐标基准转换）API 的主流程、坐标边界、未知基准与幂等边界测试
 * （真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GeoSubmitApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM frame_recalc");
        jdbcTemplate.update("DELETE FROM observation_geo");
        jdbcTemplate.update("DELETE FROM conflict_cluster");
        jdbcTemplate.update("DELETE FROM device_frame");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private ResultActions submit(String requestId, String observationId, String deviceId,
                                 String frameVersion, double latitude, double longitude,
                                 String capturedAt, String location, String reading, String note)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("deviceId", deviceId);
        body.put("frameVersion", frameVersion);
        body.put("latitude", latitude);
        body.put("longitude", longitude);
        body.put("capturedAt", capturedAt);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        return mockMvc.perform(post("/api/observations/submit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getCoordinates(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/coordinates", observationId));
    }

    private int versionCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    // ---------- 提交与坐标转换 ----------

    @Test
    void submitConvertsAndPersistsCoordinates() throws Exception {
        // GCJ02 固定偏移 (-0.0078125, -0.00390625)：统一坐标 = 原始坐标 + 偏移
        submit("req-s1", "geo-1", "dev-1", "GCJ02", 39.90625, 116.40625,
                "2026-09-25T10:00:00Z", "站点A", "1.0", "备注")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observationId").value("geo-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.deviceId").value("dev-1"))
                .andExpect(jsonPath("$.originalFrameVersion").value("GCJ02"))
                .andExpect(jsonPath("$.currentFrameVersion").value("GCJ02"))
                .andExpect(jsonPath("$.rawLatitude").value(39.90625))
                .andExpect(jsonPath("$.rawLongitude").value(116.40625))
                .andExpect(jsonPath("$.unifiedLatitude").value(39.8984375))
                .andExpect(jsonPath("$.unifiedLongitude").value(116.40234375))
                .andExpect(jsonPath("$.capturedAt").value("2026-09-25T10:00:00Z"))
                .andExpect(jsonPath("$.clusterId").doesNotExist())
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.reading").value("1.0"))
                .andExpect(jsonPath("$.note").value("备注"));

        // 坐标查询：原始与统一坐标均可查
        getCoordinates("geo-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalFrameVersion").value("GCJ02"))
                .andExpect(jsonPath("$.rawLatitude").value(39.90625))
                .andExpect(jsonPath("$.unifiedLatitude").value(39.8984375))
                .andExpect(jsonPath("$.unifiedLongitude").value(116.40234375))
                .andExpect(jsonPath("$.capturedAt").value("2026-09-25T10:00:00Z"))
                .andExpect(jsonPath("$.clusterId").doesNotExist());

        // 提交的观测记录可经既有接口查询（版本 1）
        mockMvc.perform(get("/api/observations/{id}", "geo-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.location").value("站点A"));
        assertThat(versionCount("geo-1")).isEqualTo(1);
    }

    @Test
    void submitWithUnknownFrameReturns422AndDoesNotOccupyRequestId() throws Exception {
        submit("req-s2", "geo-2", "dev-1", "MARS2000", 30.0, 120.0,
                "2026-09-25T10:00:00Z", "站点A", "1.0", "未知基准")
                .andExpect(status().isUnprocessableEntity());
        getCoordinates("geo-2").andExpect(status().isNotFound());
        // 失败不占键：同一 requestId 换合法参数后正常执行
        submit("req-s2", "geo-2", "dev-1", "WGS84", 30.0, 120.0,
                "2026-09-25T10:00:00Z", "站点A", "1.0", "未知基准")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.unifiedLatitude").value(30.0))
                .andExpect(jsonPath("$.unifiedLongitude").value(120.0));
    }

    @Test
    void submitWithCoordinateBoundaries() throws Exception {
        // 边界值合法：纬度 ±90、经度 ±180
        submit("req-b1", "geo-b1", "dev-1", "WGS84", 90.0, 180.0,
                "2026-09-25T10:00:00Z", "北极", "1.0", "边界")
                .andExpect(status().isCreated());
        submit("req-b2", "geo-b2", "dev-1", "WGS84", -90.0, -180.0,
                "2026-09-25T10:00:00Z", "南极", "1.0", "边界")
                .andExpect(status().isCreated());
        // 越界一律 422
        submit("req-b3", "geo-b3", "dev-1", "WGS84", 90.0000001, 0.0,
                "2026-09-25T10:00:00Z", "越界", "1.0", "纬度越界")
                .andExpect(status().isUnprocessableEntity());
        submit("req-b4", "geo-b4", "dev-1", "WGS84", -90.0000001, 0.0,
                "2026-09-25T10:00:00Z", "越界", "1.0", "纬度越界")
                .andExpect(status().isUnprocessableEntity());
        submit("req-b5", "geo-b5", "dev-1", "WGS84", 0.0, 180.0000001,
                "2026-09-25T10:00:00Z", "越界", "1.0", "经度越界")
                .andExpect(status().isUnprocessableEntity());
        submit("req-b6", "geo-b6", "dev-1", "WGS84", 0.0, -180.0000001,
                "2026-09-25T10:00:00Z", "越界", "1.0", "经度越界")
                .andExpect(status().isUnprocessableEntity());
        // 越界请求未产生任何坐标记录
        getCoordinates("geo-b3").andExpect(status().isNotFound());
        getCoordinates("geo-b5").andExpect(status().isNotFound());
    }

    @Test
    void submitWithConvertedCoordinatesOutOfRangeReturns422() throws Exception {
        // 原始纬度合法，但按 BD09 偏移转换后统一纬度低于 -90：转换失败 422
        submit("req-s3", "geo-3", "dev-1", "BD09", -89.99609375, 100.0,
                "2026-09-25T10:00:00Z", "站点A", "1.0", "转换越界")
                .andExpect(status().isUnprocessableEntity());
        getCoordinates("geo-3").andExpect(status().isNotFound());
    }

    // ---------- 幂等 ----------

    @Test
    void submitReplaysSameRequestIdWithSameParams() throws Exception {
        submit("req-s4", "geo-4", "dev-1", "GCJ02", 39.90625, 116.40625,
                "2026-09-25T10:00:00Z", "站点A", "1.0", "备注")
                .andExpect(status().isCreated());
        // 同键同参重放：返回首个结果，不产生新观测
        submit("req-s4", "geo-4", "dev-1", "GCJ02", 39.90625, 116.40625,
                "2026-09-25T10:00:00Z", "站点A", "1.0", "备注")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.unifiedLatitude").value(39.8984375));
        assertThat(versionCount("geo-4")).isEqualTo(1);
        Integer geoRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_geo WHERE observation_id = 'geo-4'", Integer.class);
        assertThat(geoRows).isEqualTo(1);
        // 同键异参：指纹含设备、基准版本、原始坐标、时刻和字段内容，任一不同即 409
        submit("req-s4", "geo-4", "dev-1", "WGS84", 39.90625, 116.40625,
                "2026-09-25T10:00:00Z", "站点A", "1.0", "备注")
                .andExpect(status().isConflict());
        submit("req-s4", "geo-4", "dev-2", "GCJ02", 39.90625, 116.40625,
                "2026-09-25T10:00:00Z", "站点A", "1.0", "备注")
                .andExpect(status().isConflict());
        submit("req-s4", "geo-4", "dev-1", "GCJ02", 39.9, 116.40625,
                "2026-09-25T10:00:00Z", "站点A", "1.0", "备注")
                .andExpect(status().isConflict());
        submit("req-s4", "geo-4", "dev-1", "GCJ02", 39.90625, 116.40625,
                "2026-09-25T11:00:00Z", "站点A", "1.0", "备注")
                .andExpect(status().isConflict());
        submit("req-s4", "geo-4", "dev-1", "GCJ02", 39.90625, 116.40625,
                "2026-09-25T10:00:00Z", "站点B", "1.0", "备注")
                .andExpect(status().isConflict());
    }

    @Test
    void submitDuplicateObservationIdReturns409() throws Exception {
        submit("req-s5", "geo-5", "dev-1", "WGS84", 30.0, 120.0,
                "2026-09-25T10:00:00Z", "站点A", "1.0", "备注")
                .andExpect(status().isCreated());
        submit("req-s6", "geo-5", "dev-2", "WGS84", 31.0, 121.0,
                "2026-09-25T10:00:00Z", "站点B", "2.0", "重复标识")
                .andExpect(status().isConflict());
        assertThat(versionCount("geo-5")).isEqualTo(1);
    }

    @Test
    void getCoordinatesForObservationWithoutGeoReturns404() throws Exception {
        // 经既有创建接口建立的观测没有坐标信息
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-p1");
        body.put("observationId", "plain-1");
        body.put("location", "站点A");
        body.put("reading", "1.0");
        body.put("note", "无坐标");
        mockMvc.perform(post("/api/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
        getCoordinates("plain-1").andExpect(status().isNotFound());
        getCoordinates("missing").andExpect(status().isNotFound());
    }
}
