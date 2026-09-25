package com.example.starter.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设备基准变更与重算测试：同事务重算统一坐标与簇归属、转换失败整次回滚、
 * 不可变重算记录、人工裁决簇保护（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FrameRecalcApiTest {

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
                                 String capturedAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("deviceId", deviceId);
        body.put("frameVersion", frameVersion);
        body.put("latitude", latitude);
        body.put("longitude", longitude);
        body.put("capturedAt", capturedAt);
        body.put("location", "站点");
        body.put("reading", "1.0");
        body.put("note", "备注");
        return mockMvc.perform(post("/api/observations/submit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private String submitAndGetClusterId(String requestId, String observationId, String deviceId,
                                         String frameVersion, double latitude, double longitude,
                                         String capturedAt) throws Exception {
        MvcResult result = submit(requestId, observationId, deviceId, frameVersion,
                latitude, longitude, capturedAt)
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString()).get("clusterId");
        return node == null || node.isNull() ? null : node.asText();
    }

    private ResultActions putFrame(String requestId, String deviceId, String frameVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("frameVersion", frameVersion);
        return mockMvc.perform(put("/api/devices/{id}/frame", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions resolveCluster(String requestId, String clusterId,
                                         String observationId, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/clusters/{id}/resolve", clusterId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getCoordinates(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/coordinates", observationId));
    }

    private ResultActions getCluster(String clusterId) throws Exception {
        return mockMvc.perform(get("/api/clusters/{id}", clusterId));
    }

    private ResultActions getRecalcs(String deviceId) throws Exception {
        return mockMvc.perform(get("/api/devices/{id}/frame-recalcs", deviceId));
    }

    private ResultActions getRecalc(String recalcId) throws Exception {
        return mockMvc.perform(get("/api/frame-recalcs/{id}", recalcId));
    }

    // ---------- 基准登记与重算 ----------

    @Test
    void registerDeviceFrameWithoutObservations() throws Exception {
        putFrame("req-f1", "dev-x", "WGS84")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("dev-x"))
                .andExpect(jsonPath("$.previousFrameVersion").doesNotExist())
                .andExpect(jsonPath("$.frameVersion").value("WGS84"))
                .andExpect(jsonPath("$.recalculatedCount").value(0))
                .andExpect(jsonPath("$.recalcId").doesNotExist());
        getRecalcs("dev-x")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void frameChangeRecalculatesAndDissolvesCluster() throws Exception {
        submit("req-f2a", "obs-1", "dev-1", "GCJ02", 39.90625, 116.40625, "2026-09-25T10:00:00Z")
                .andExpect(status().isCreated());
        String clusterId = submitAndGetClusterId("req-f2b", "obs-2", "dev-2", "WGS84",
                39.8984375, 116.40234375, "2026-09-25T10:00:30Z");
        getCluster(clusterId)
                .andExpect(jsonPath("$.winnerObservationId").value("obs-2"));

        // 设备基准 GCJ02 → WGS84：obs-1 统一坐标按新基准重算后远离 obs-2，簇解散
        putFrame("req-f2", "dev-1", "WGS84")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousFrameVersion").value("GCJ02"))
                .andExpect(jsonPath("$.frameVersion").value("WGS84"))
                .andExpect(jsonPath("$.recalculatedCount").value(1))
                .andExpect(jsonPath("$.recalcId").value("req-f2"));

        // 原始坐标与原基准版本不可改写；统一坐标与当前基准版本已更新
        getCoordinates("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalFrameVersion").value("GCJ02"))
                .andExpect(jsonPath("$.currentFrameVersion").value("WGS84"))
                .andExpect(jsonPath("$.rawLatitude").value(39.90625))
                .andExpect(jsonPath("$.rawLongitude").value(116.40625))
                .andExpect(jsonPath("$.unifiedLatitude").value(39.90625))
                .andExpect(jsonPath("$.unifiedLongitude").value(116.40625))
                .andExpect(jsonPath("$.clusterId").doesNotExist());
        getCoordinates("obs-2")
                .andExpect(jsonPath("$.clusterId").doesNotExist());
        getCluster(clusterId).andExpect(status().isNotFound());

        // 不可变重算记录：固化新旧簇和参数版本
        getRecalcs("dev-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].recalcId").value("req-f2"))
                .andExpect(jsonPath("$[0].deviceId").value("dev-1"))
                .andExpect(jsonPath("$[0].oldFrameVersion").value("GCJ02"))
                .andExpect(jsonPath("$[0].newFrameVersion").value("WGS84"))
                .andExpect(jsonPath("$[0].oldClusters[0].clusterId").value(clusterId))
                .andExpect(jsonPath("$[0].oldClusters[0].memberObservationIds[0]").value("obs-1"))
                .andExpect(jsonPath("$[0].oldClusters[0].memberObservationIds[1]").value("obs-2"))
                .andExpect(jsonPath("$[0].oldClusters[0].winnerObservationId").value("obs-2"))
                .andExpect(jsonPath("$[0].newClusters.length()").value(0));
        getRecalc("req-f2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oldFrameVersion").value("GCJ02"))
                .andExpect(jsonPath("$.newFrameVersion").value("WGS84"));
        // 同键同参重放首个结果
        putFrame("req-f2", "dev-1", "WGS84")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recalcId").value("req-f2"))
                .andExpect(jsonPath("$.recalculatedCount").value(1));
    }

    @Test
    void frameChangeWithConvertedCoordinatesOutOfRangeRollsBack() throws Exception {
        submit("req-f3a", "obs-3", "dev-3", "WGS84", -89.99609375, 100.0, "2026-09-25T10:00:00Z")
                .andExpect(status().isCreated());
        // BD09 偏移使统一纬度低于 -90：转换失败 422，整次回滚
        putFrame("req-f3", "dev-3", "BD09")
                .andExpect(status().isUnprocessableEntity());
        // 设备基准、统一坐标均未变化，无重算记录
        getCoordinates("obs-3")
                .andExpect(jsonPath("$.currentFrameVersion").value("WGS84"))
                .andExpect(jsonPath("$.unifiedLatitude").value(-89.99609375));
        getRecalcs("dev-3")
                .andExpect(jsonPath("$.length()").value(0));
        // 失败不占键：同一 requestId 换合法基准后正常执行，
        // 响应中的 previousFrameVersion 证明设备基准未被 422 请求改动
        putFrame("req-f3", "dev-3", "CGCS2000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousFrameVersion").value("WGS84"))
                .andExpect(jsonPath("$.frameVersion").value("CGCS2000"))
                .andExpect(jsonPath("$.recalculatedCount").value(1));
        getCoordinates("obs-3")
                .andExpect(jsonPath("$.currentFrameVersion").value("CGCS2000"))
                .andExpect(jsonPath("$.unifiedLatitude").value(-89.9921875));
    }

    @Test
    void frameChangeToUnknownFrameReturns422() throws Exception {
        submit("req-f4a", "obs-4", "dev-4", "WGS84", 30.0, 120.0, "2026-09-25T10:00:00Z")
                .andExpect(status().isCreated());
        putFrame("req-f4", "dev-4", "MARS2000")
                .andExpect(status().isUnprocessableEntity());
        // 失败不占键
        putFrame("req-f4", "dev-4", "GCJ02")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frameVersion").value("GCJ02"))
                .andExpect(jsonPath("$.recalculatedCount").value(1));
    }

    @Test
    void frameChangeWithoutClusterChangeWritesNoRecalcRecord() throws Exception {
        // 单条观测、无邻居：基准变更只更新统一坐标，不改变簇成员或胜出记录，不写重算记录
        submit("req-f6a", "obs-6", "dev-6", "WGS84", 30.0, 120.0, "2026-09-25T10:00:00Z")
                .andExpect(status().isCreated());
        putFrame("req-f6", "dev-6", "GCJ02")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recalculatedCount").value(1))
                .andExpect(jsonPath("$.recalcId").doesNotExist());
        getCoordinates("obs-6")
                .andExpect(jsonPath("$.currentFrameVersion").value("GCJ02"))
                .andExpect(jsonPath("$.unifiedLatitude").value(29.9921875))
                .andExpect(jsonPath("$.unifiedLongitude").value(119.99609375));
        getRecalcs("dev-6")
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---------- 人工裁决保护 ----------

    @Test
    void manualResolvedClusterIsProtected() throws Exception {
        submit("req-r1a", "obs-1", "dev-1", "GCJ02", 39.90625, 116.40625, "2026-09-25T10:00:00Z")
                .andExpect(status().isCreated());
        String clusterId = submitAndGetClusterId("req-r1b", "obs-2", "dev-2", "WGS84",
                39.8984375, 116.40234375, "2026-09-25T10:00:30Z");

        // 人工裁决选定 obs-1：胜出记录被固化
        resolveCluster("req-r1", clusterId, "obs-1", "op-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winnerObservationId").value("obs-1"))
                .andExpect(jsonPath("$.manuallyResolved").value(true))
                .andExpect(jsonPath("$.resolvedObservationId").value("obs-1"));
        // 相同选择幂等成功；不同选择 409
        resolveCluster("req-r2", clusterId, "obs-1", "op-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winnerObservationId").value("obs-1"));
        resolveCluster("req-r3", clusterId, "obs-2", "op-1")
                .andExpect(status().isConflict());

        // 基准变更跳过已人工裁决簇中的观测：统一坐标与簇归属不变，选择结论不被覆盖
        putFrame("req-r4", "dev-1", "WGS84")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recalculatedCount").value(0))
                .andExpect(jsonPath("$.recalcId").doesNotExist());
        getCoordinates("obs-1")
                .andExpect(jsonPath("$.currentFrameVersion").value("GCJ02"))
                .andExpect(jsonPath("$.unifiedLatitude").value(39.8984375))
                .andExpect(jsonPath("$.clusterId").value(clusterId));
        getCluster(clusterId)
                .andExpect(jsonPath("$.winnerObservationId").value("obs-1"))
                .andExpect(jsonPath("$.manuallyResolved").value(true));
        getRecalcs("dev-1")
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void resolveClusterValidationAndReplay() throws Exception {
        submit("req-v1", "obs-1", "dev-1", "WGS84", 30.0, 120.0, "2026-09-25T10:00:00Z")
                .andExpect(status().isCreated());
        String clusterId = submitAndGetClusterId("req-v2", "obs-2", "dev-2", "WGS84",
                30.0, 120.0, "2026-09-25T10:00:30Z");
        // 非成员选择：400
        resolveCluster("req-v3", clusterId, "obs-x", "op-1")
                .andExpect(status().isBadRequest());
        // 未知簇：404
        resolveCluster("req-v4", "clu-missing", "obs-1", "op-1")
                .andExpect(status().isNotFound());
        // 同键同参重放首个结果
        resolveCluster("req-v5", clusterId, "obs-2", "op-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winnerObservationId").value("obs-2"));
        resolveCluster("req-v5", clusterId, "obs-2", "op-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winnerObservationId").value("obs-2"));
        // 同键异参：409
        resolveCluster("req-v5", clusterId, "obs-1", "op-1")
                .andExpect(status().isConflict());
        getCluster(clusterId)
                .andExpect(jsonPath("$.winnerObservationId").value("obs-2"))
                .andExpect(jsonPath("$.manuallyResolved").value(true));
    }
}
