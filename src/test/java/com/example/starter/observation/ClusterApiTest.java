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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 冲突簇判定测试：统一坐标球面距离 50 米与采集时刻差 60 秒的边界精确包含、
 * 连通分量归簇、跨基准同点归簇与胜出记录规则（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClusterApiTest {

    /**
     * 恰好 50 米球面距离对应的纬度差（度），与服务端使用同一地球半径常量。
     */
    private static final double D50 = Math.toDegrees(50.0 / SphericalDistance.EARTH_RADIUS_METERS);

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

    private ResultActions getCoordinates(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/coordinates", observationId));
    }

    private ResultActions getCluster(String clusterId) throws Exception {
        return mockMvc.perform(get("/api/clusters/{id}", clusterId));
    }

    // ---------- 50 米 / 60 秒边界 ----------

    @Test
    void exactlyFiftyMetersAndSixtySecondsFormsCluster() throws Exception {
        // 距离恰好 50 米、时刻差恰好 60 秒：边界精确包含，进入同簇
        submit("req-c1", "obs-a", "dev-1", "WGS84", 30.0, 120.0, "2026-09-25T10:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clusterId").doesNotExist());
        String clusterId = submitAndGetClusterId("req-c2", "obs-b", "dev-2", "WGS84",
                30.0 + D50, 120.0, "2026-09-25T10:01:00Z");
        getCoordinates("obs-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clusterId").value(clusterId));
        getCluster(clusterId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberObservationIds[0]").value("obs-a"))
                .andExpect(jsonPath("$.memberObservationIds[1]").value("obs-b"))
                .andExpect(jsonPath("$.winnerObservationId").value("obs-b"))
                .andExpect(jsonPath("$.manuallyResolved").value(false));
    }

    @Test
    void beyondFiftyMetersOrSixtySecondsDoesNotCluster() throws Exception {
        double dOver = Math.toDegrees(50.01 / SphericalDistance.EARTH_RADIUS_METERS);
        submit("req-c3", "obs-a", "dev-1", "WGS84", 30.0, 120.0, "2026-09-25T10:00:00Z")
                .andExpect(status().isCreated());
        // 距离 50.01 米：超出 50 米阈值，不入簇
        String clusterB = submitAndGetClusterId("req-c4", "obs-b", "dev-2", "WGS84",
                30.0 + dOver, 120.0, "2026-09-25T10:00:00Z");
        // 时刻差 61 秒：超出 60 秒阈值，不入簇
        String clusterC = submitAndGetClusterId("req-c5", "obs-c", "dev-3", "WGS84",
                30.0, 120.0, "2026-09-25T10:01:01Z");
        org.assertj.core.api.Assertions.assertThat(clusterB).isNull();
        org.assertj.core.api.Assertions.assertThat(clusterC).isNull();
        getCoordinates("obs-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clusterId").doesNotExist());
        Integer clusterRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conflict_cluster", Integer.class);
        org.assertj.core.api.Assertions.assertThat(clusterRows).isZero();
    }

    @Test
    void transitiveClusteringMergesThroughChain() throws Exception {
        double d40 = Math.toDegrees(40.0 / SphericalDistance.EARTH_RADIUS_METERS);
        submit("req-c6", "obs-a", "dev-1", "WGS84", 30.0, 120.0, "2026-09-25T10:00:00Z")
                .andExpect(status().isCreated());
        String clusterId = submitAndGetClusterId("req-c7", "obs-b", "dev-2", "WGS84",
                30.0 + d40, 120.0, "2026-09-25T10:00:00Z");
        // obs-c 与 obs-a 相距 80 米，但与 obs-b 相距 40 米：经 obs-b 连通进入同簇
        String clusterC = submitAndGetClusterId("req-c8", "obs-c", "dev-3", "WGS84",
                30.0 + 2 * d40, 120.0, "2026-09-25T10:00:00Z");
        org.assertj.core.api.Assertions.assertThat(clusterC).isEqualTo(clusterId);
        getCluster(clusterId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberObservationIds.length()").value(3))
                .andExpect(jsonPath("$.memberObservationIds[2]").value("obs-c"));
    }

    @Test
    void samePhysicalLocationInDifferentFramesClusters() throws Exception {
        // 同一物理位置：obs-1 以 GCJ02 提交，obs-2 以 WGS84 提交，转换后统一坐标相同
        submit("req-c9", "obs-1", "dev-1", "GCJ02", 39.90625, 116.40625, "2026-09-25T10:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.unifiedLatitude").value(39.8984375))
                .andExpect(jsonPath("$.unifiedLongitude").value(116.40234375));
        String clusterId = submitAndGetClusterId("req-c10", "obs-2", "dev-2", "WGS84",
                39.8984375, 116.40234375, "2026-09-25T10:00:30Z");
        getCluster(clusterId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberObservationIds[0]").value("obs-1"))
                .andExpect(jsonPath("$.memberObservationIds[1]").value("obs-2"))
                .andExpect(jsonPath("$.winnerObservationId").value("obs-2"));
    }

    @Test
    void winnerIsLatestCapturedAt() throws Exception {
        submit("req-c11", "obs-a", "dev-1", "WGS84", 30.0, 120.0, "2026-09-25T10:00:00Z")
                .andExpect(status().isCreated());
        String clusterId = submitAndGetClusterId("req-c12", "obs-b", "dev-2", "WGS84",
                30.0, 120.0, "2026-09-25T10:00:30Z");
        // 采集时刻更新者胜出
        getCluster(clusterId)
                .andExpect(jsonPath("$.winnerObservationId").value("obs-b"));
        // 采集时刻更早的新成员加入不改变胜出记录
        String clusterC = submitAndGetClusterId("req-c13", "obs-c", "dev-3", "WGS84",
                30.0, 120.0, "2026-09-25T09:59:30Z");
        org.assertj.core.api.Assertions.assertThat(clusterC).isEqualTo(clusterId);
        getCluster(clusterId)
                .andExpect(jsonPath("$.winnerObservationId").value("obs-b"))
                .andExpect(jsonPath("$.memberObservationIds.length()").value(3));
    }

    @Test
    void getClusterReturns404ForUnknown() throws Exception {
        getCluster("clu-missing").andExpect(status().isNotFound());
    }
}
