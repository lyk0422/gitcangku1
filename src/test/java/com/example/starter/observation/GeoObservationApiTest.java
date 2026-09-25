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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测坐标基准 API 测试：坐标边界、50 米/60 秒簇判定、基准重算原子性、
 * 人工裁决保护与幂等边界（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GeoObservationApiTest {

    private static final String FRAME_ZERO = "FRAME-ZERO";
    private static final String FRAME_SHIFT = "FRAME-SHIFT";
    private static final String FRAME_BIG = "FRAME-BIG";
    private static final String T0 = "2026-09-26T10:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM frame_recalc");
        jdbcTemplate.update("DELETE FROM geo_observation");
        jdbcTemplate.update("DELETE FROM geo_cluster");
        jdbcTemplate.update("DELETE FROM device_frame");
        jdbcTemplate.update("DELETE FROM coordinate_frame");
    }

    // ---------- 请求辅助 ----------

    private ResultActions registerFrame(String requestId, String frameVersion,
                                        double offsetLat, double offsetLon) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("frameVersion", frameVersion);
        body.put("offsetLatDeg", offsetLat);
        body.put("offsetLonDeg", offsetLon);
        return mockMvc.perform(post("/api/geo/frames")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions submitRaw(String requestId, String deviceId, double lat, double lon,
                                    String frameVersion, String capturedAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("latitude", lat);
        body.put("longitude", lon);
        body.put("frameVersion", frameVersion);
        body.put("capturedAt", capturedAt);
        body.put("location", "站点");
        body.put("reading", "1.0");
        body.put("note", "备注");
        return mockMvc.perform(post("/api/geo/devices/{deviceId}/observations", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode submitOk(String requestId, String deviceId, double lat, double lon,
                              String frameVersion, String capturedAt) throws Exception {
        MvcResult result = submitRaw(requestId, deviceId, lat, lon, frameVersion, capturedAt)
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private ResultActions updateDeviceFrame(String requestId, String deviceId,
                                            String frameVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("frameVersion", frameVersion);
        return mockMvc.perform(post("/api/geo/devices/{deviceId}/frame", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions resolveCluster(String requestId, String clusterId,
                                         String winnerObservationId, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("winnerObservationId", winnerObservationId);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/geo/clusters/{clusterId}/resolve", clusterId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode getObservationOk(String observationId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/geo/observations/{id}", observationId))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getClusterOk(String clusterId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/geo/clusters/{id}", clusterId))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int rowCount(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private void registerStandardFrames() throws Exception {
        registerFrame("req-f-zero", FRAME_ZERO, 0.0, 0.0).andExpect(status().isCreated());
        registerFrame("req-f-shift", FRAME_SHIFT, 0.001, 0.0).andExpect(status().isCreated());
        registerFrame("req-f-big", FRAME_BIG, 10.0, 0.0).andExpect(status().isCreated());
    }

    // ---------- 基准登记 ----------

    @Test
    void registerFrameCreatedAndReplayed() throws Exception {
        registerFrame("req-rf1", FRAME_ZERO, 0.0, 0.0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.frameVersion").value(FRAME_ZERO))
                .andExpect(jsonPath("$.offsetLatDeg").value(0.0));
        // 同键同参重放首个结果
        registerFrame("req-rf1", FRAME_ZERO, 0.0, 0.0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.frameVersion").value(FRAME_ZERO));
        assertThat(rowCount("coordinate_frame")).isEqualTo(1);
        // 同键异参 409
        registerFrame("req-rf1", FRAME_ZERO, 1.0, 0.0).andExpect(status().isConflict());
        // 不同键重复登记同版本 409
        registerFrame("req-rf2", FRAME_ZERO, 0.0, 0.0).andExpect(status().isConflict());
        assertThat(rowCount("coordinate_frame")).isEqualTo(1);
    }

    // ---------- 提交与坐标边界 ----------

    @Test
    void submitWithUnknownFrameReturns422() throws Exception {
        submitRaw("req-s1", "dev-1", 30.0, 120.0, "NO-SUCH-FRAME", T0)
                .andExpect(status().isUnprocessableEntity());
        assertThat(rowCount("geo_observation")).isZero();
        // 失败不占键：登记基准后同一 requestId 可正常提交
        registerFrame("req-s2", FRAME_ZERO, 0.0, 0.0).andExpect(status().isCreated());
        submitRaw("req-s1", "dev-1", 30.0, 120.0, FRAME_ZERO, T0)
                .andExpect(status().isCreated());
    }

    @Test
    void submitCoordinateBoundariesAreInclusive() throws Exception {
        registerFrame("req-b0", FRAME_ZERO, 0.0, 0.0).andExpect(status().isCreated());
        // 边界值合法
        submitRaw("req-b1", "dev-1", 90.0, 180.0, FRAME_ZERO, T0).andExpect(status().isCreated());
        submitRaw("req-b2", "dev-1", -90.0, -180.0, FRAME_ZERO, T0).andExpect(status().isCreated());
        submitRaw("req-b3", "dev-1", 0.0, 0.0, FRAME_ZERO, T0).andExpect(status().isCreated());
        // 越界 422
        submitRaw("req-b4", "dev-1", 90.0000001, 0.0, FRAME_ZERO, T0)
                .andExpect(status().isUnprocessableEntity());
        submitRaw("req-b5", "dev-1", -90.0000001, 0.0, FRAME_ZERO, T0)
                .andExpect(status().isUnprocessableEntity());
        submitRaw("req-b6", "dev-1", 0.0, 180.0000001, FRAME_ZERO, T0)
                .andExpect(status().isUnprocessableEntity());
        submitRaw("req-b7", "dev-1", 0.0, -180.0000001, FRAME_ZERO, T0)
                .andExpect(status().isUnprocessableEntity());
        assertThat(rowCount("geo_observation")).isEqualTo(3);
    }

    @Test
    void submitStoresRawAndUnifiedCoordinates() throws Exception {
        registerFrame("req-u0", FRAME_SHIFT, 0.001, 0.002).andExpect(status().isCreated());
        JsonNode body = submitOk("req-u1", "dev-1", 30.0, 120.0, FRAME_SHIFT, T0);
        String observationId = body.get("observationId").asText();
        assertThat(body.get("rawLatitude").asDouble()).isEqualTo(30.0);
        assertThat(body.get("rawLongitude").asDouble()).isEqualTo(120.0);
        assertThat(body.get("rawFrameVersion").asText()).isEqualTo(FRAME_SHIFT);
        assertThat(body.get("unifiedLatitude").asDouble()).isCloseTo(30.001, within(1e-9));
        assertThat(body.get("unifiedLongitude").asDouble()).isCloseTo(120.002, within(1e-9));
        assertThat(body.get("appliedFrameVersion").asText()).isEqualTo(FRAME_SHIFT);
        assertThat(body.get("capturedAt").asText()).isEqualTo("2026-09-26T10:00:00Z");
        assertThat(body.has("clusterId")).isFalse();

        // 查询接口返回相同的原始与统一坐标
        JsonNode queried = getObservationOk(observationId);
        assertThat(queried.get("rawLatitude").asDouble()).isEqualTo(30.0);
        assertThat(queried.get("unifiedLatitude").asDouble()).isCloseTo(30.001, within(1e-9));
    }

    // ---------- 冲突簇判定 ----------

    @Test
    void observationsWithinLimitsFormCluster() throws Exception {
        registerFrame("req-c0", FRAME_ZERO, 0.0, 0.0).andExpect(status().isCreated());
        // 约 44.5 米（0.0004 纬度）、30 秒：进入同簇
        JsonNode first = submitOk("req-c1", "dev-1", 30.0, 120.0, FRAME_ZERO, T0);
        JsonNode second = submitOk("req-c2", "dev-2", 30.0004, 120.0, FRAME_ZERO,
                "2026-09-26T10:00:30Z");
        JsonNode firstStored = getObservationOk(first.get("observationId").asText());
        String clusterId = firstStored.get("clusterId").asText();
        assertThat(clusterId).isEqualTo(second.get("clusterId").asText());

        JsonNode cluster = getClusterOk(clusterId);
        assertThat(cluster.get("memberObservationIds")).hasSize(2);
        // 自动胜出者：采集时刻最早的观测
        assertThat(cluster.get("winnerObservationId").asText())
                .isEqualTo(first.get("observationId").asText());
        assertThat(cluster.get("manuallyResolved").asBoolean()).isFalse();
    }

    @Test
    void distanceBoundaryExactlyFiftyMetersIncluded() throws Exception {
        registerFrame("req-db0", FRAME_ZERO, 0.0, 0.0).andExpect(status().isCreated());
        // 计算恰好不超过 50 米的纬度差（浮点微调至边界），以及恰好超过 50 米的纬度差
        double limit = GeoDistance.CLUSTER_DISTANCE_LIMIT_METERS;
        double withinDeg = Math.toDegrees(limit / GeoDistance.EARTH_RADIUS_METERS);
        while (GeoDistance.haversineMeters(0.0, 0.0, withinDeg, 0.0) > limit) {
            withinDeg = Math.nextDown(withinDeg);
        }
        double beyondDeg = Math.nextUp(withinDeg);
        while (GeoDistance.haversineMeters(0.0, 0.0, beyondDeg, 0.0) <= limit) {
            beyondDeg = Math.nextUp(beyondDeg);
        }
        assertThat(GeoDistance.haversineMeters(0.0, 0.0, withinDeg, 0.0)).isLessThanOrEqualTo(limit);
        assertThat(GeoDistance.haversineMeters(0.0, 0.0, beyondDeg, 0.0)).isGreaterThan(limit);

        // 恰好 50 米（边界包含）：同簇。观测放在原点，使服务端距离计算与上方微调循环
        // 使用完全相同的浮点表达式，边界判定确定可复现。
        JsonNode first = submitOk("req-db1", "dev-1", 0.0, 0.0, FRAME_ZERO, T0);
        JsonNode second = submitOk("req-db2", "dev-2", withinDeg, 0.0, FRAME_ZERO, T0);
        JsonNode firstStored = getObservationOk(first.get("observationId").asText());
        assertThat(firstStored.get("clusterId").asText())
                .isEqualTo(second.get("clusterId").asText());

        // 超过 50 米约 1 厘米（边界外、大于浮点噪声）：不成簇；
        // 采集时刻取一小时后，避免与第一对产生传递分组。
        double clearlyBeyondDeg = withinDeg + 1e-7;
        assertThat(GeoDistance.haversineMeters(0.0, 0.0, clearlyBeyondDeg, 0.0)).isGreaterThan(limit);
        JsonNode third = submitOk("req-db3", "dev-3", 0.0, 30.0, FRAME_ZERO,
                "2026-09-26T11:00:00Z");
        JsonNode fourth = submitOk("req-db4", "dev-4", clearlyBeyondDeg, 30.0, FRAME_ZERO,
                "2026-09-26T11:00:00Z");
        assertThat(third.has("clusterId")).isFalse();
        assertThat(fourth.has("clusterId")).isFalse();
    }

    @Test
    void timeBoundaryExactlySixtySecondsIncluded() throws Exception {
        registerFrame("req-tb0", FRAME_ZERO, 0.0, 0.0).andExpect(status().isCreated());
        // 同一地点、采集时刻恰好相差 60 秒：同簇
        JsonNode first = submitOk("req-tb1", "dev-1", 30.0, 120.0, FRAME_ZERO, T0);
        JsonNode second = submitOk("req-tb2", "dev-2", 30.0, 120.0, FRAME_ZERO,
                "2026-09-26T10:01:00Z");
        JsonNode firstStored = getObservationOk(first.get("observationId").asText());
        assertThat(firstStored.get("clusterId").asText())
                .isEqualTo(second.get("clusterId").asText());

        // 相差 61 秒：不进簇
        JsonNode third = submitOk("req-tb3", "dev-3", 50.0, 120.0, FRAME_ZERO, T0);
        JsonNode fourth = submitOk("req-tb4", "dev-4", 50.0, 120.0, FRAME_ZERO,
                "2026-09-26T10:01:01Z");
        assertThat(third.has("clusterId")).isFalse();
        assertThat(fourth.has("clusterId")).isFalse();
    }

    @Test
    void clusteringIsTransitiveThroughPairs() throws Exception {
        registerFrame("req-tr0", FRAME_ZERO, 0.0, 0.0).andExpect(status().isCreated());
        // A-B 约 44.5 米、B-C 约 44.5 米、A-C 约 89 米：同簇关系经 B 传递，三者同簇
        JsonNode a = submitOk("req-tr1", "dev-1", 30.0, 120.0, FRAME_ZERO, T0);
        JsonNode b = submitOk("req-tr2", "dev-2", 30.0004, 120.0, FRAME_ZERO,
                "2026-09-26T10:00:20Z");
        JsonNode c = submitOk("req-tr3", "dev-3", 30.0008, 120.0, FRAME_ZERO,
                "2026-09-26T10:00:40Z");
        String clusterId = c.get("clusterId").asText();
        JsonNode aStored = getObservationOk(a.get("observationId").asText());
        JsonNode bStored = getObservationOk(b.get("observationId").asText());
        assertThat(aStored.get("clusterId").asText()).isEqualTo(clusterId);
        assertThat(bStored.get("clusterId").asText()).isEqualTo(clusterId);
        assertThat(getClusterOk(clusterId).get("memberObservationIds")).hasSize(3);
    }

    // ---------- 基准修改与重算 ----------

    @Test
    void frameChangeRecalculatesAndWritesRecalcRecord() throws Exception {
        registerStandardFrames();
        updateDeviceFrame("req-df0", "dev-1", FRAME_ZERO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recalced").value(false));
        // dev-1 的 A 与 dev-2 的 B 相距约 44.5 米：同簇
        JsonNode a = submitOk("req-rc1", "dev-1", 30.0, 120.0, FRAME_ZERO, T0);
        JsonNode b = submitOk("req-rc2", "dev-2", 30.0004, 120.0, FRAME_ZERO,
                "2026-09-26T10:00:10Z");
        String clusterId = b.get("clusterId").asText();
        assertThat(getObservationOk(a.get("observationId").asText()).get("clusterId").asText())
                .isEqualTo(clusterId);

        // dev-1 基准改为 FRAME-SHIFT（北偏 0.001 度 ≈ 111 米）：A 移动后与 B 相距约 66.7 米，簇解散
        MvcResult result = updateDeviceFrame("req-df1", "dev-1", FRAME_SHIFT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recalced").value(true))
                .andReturn();
        JsonNode outcome = objectMapper.readTree(result.getResponse().getContentAsString());
        String recalcId = outcome.get("recalcId").asText();

        // A 的统一坐标被重算，原始坐标与原基准版本不变
        JsonNode aAfter = getObservationOk(a.get("observationId").asText());
        assertThat(aAfter.get("unifiedLatitude").asDouble()).isCloseTo(30.001, within(1e-9));
        assertThat(aAfter.get("appliedFrameVersion").asText()).isEqualTo(FRAME_SHIFT);
        assertThat(aAfter.get("rawLatitude").asDouble()).isEqualTo(30.0);
        assertThat(aAfter.get("rawFrameVersion").asText()).isEqualTo(FRAME_ZERO);
        assertThat(aAfter.has("clusterId")).isFalse();
        // dev-2 的 B 不受影响
        JsonNode bAfter = getObservationOk(b.get("observationId").asText());
        assertThat(bAfter.get("unifiedLatitude").asDouble()).isEqualTo(30.0004);
        assertThat(bAfter.has("clusterId")).isFalse();
        // 旧簇已解散
        mockMvc.perform(get("/api/geo/clusters/{id}", clusterId)).andExpect(status().isNotFound());

        // 重算记录固化新旧簇与参数版本
        MvcResult recalcResult = mockMvc.perform(get("/api/geo/recalcs/{id}", recalcId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("dev-1"))
                .andExpect(jsonPath("$.oldFrameVersion").value(FRAME_ZERO))
                .andExpect(jsonPath("$.newFrameVersion").value(FRAME_SHIFT))
                .andReturn();
        JsonNode recalc = objectMapper.readTree(recalcResult.getResponse().getContentAsString());
        assertThat(recalc.get("oldClusters")).hasSize(1);
        assertThat(recalc.get("oldClusters").get(0).get("memberObservationIds")).hasSize(2);
        assertThat(recalc.get("newClusters")).hasSize(0);

        // 按设备查询重算历史
        mockMvc.perform(get("/api/geo/devices/{deviceId}/recalcs", "dev-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].recalcId").value(recalcId));
    }

    @Test
    void frameChangeWithoutClusterChangeWritesNoRecalcRecord() throws Exception {
        registerStandardFrames();
        // 孤立观测：基准变化只更新统一坐标，不产生簇变化与重算记录
        submitOk("req-nc1", "dev-1", 30.0, 120.0, FRAME_ZERO, T0);
        updateDeviceFrame("req-nc2", "dev-1", FRAME_SHIFT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recalced").value(false))
                .andExpect(jsonPath("$.recalcId").doesNotExist());
        assertThat(rowCount("frame_recalc")).isZero();
        // 相同基准版本重复修改：同样不触发重算
        updateDeviceFrame("req-nc3", "dev-1", FRAME_SHIFT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recalced").value(false));
        assertThat(rowCount("frame_recalc")).isZero();
    }

    @Test
    void frameChangeWithUnknownFrameReturns422() throws Exception {
        registerFrame("req-uf0", FRAME_ZERO, 0.0, 0.0).andExpect(status().isCreated());
        updateDeviceFrame("req-uf1", "dev-1", "NO-SUCH-FRAME")
                .andExpect(status().isUnprocessableEntity());
        // 失败不占键
        updateDeviceFrame("req-uf1", "dev-1", FRAME_ZERO).andExpect(status().isOk());
    }

    @Test
    void recalcFailureRollsBackAtomically() throws Exception {
        registerStandardFrames();
        updateDeviceFrame("req-rb0", "dev-1", FRAME_ZERO).andExpect(status().isOk());
        JsonNode a = submitOk("req-rb1", "dev-1", 30.0, 120.0, FRAME_ZERO, T0);
        JsonNode c = submitOk("req-rb2", "dev-1", 85.0, 120.0, FRAME_ZERO, T0);

        // 换成 FRAME_BIG（北偏 10 度）：C 换算后纬度 95 越界，整次回滚
        updateDeviceFrame("req-rb3", "dev-1", FRAME_BIG)
                .andExpect(status().isUnprocessableEntity());

        // 设备基准版本不变，两条观测的统一坐标均未被部分重算
        JsonNode aAfter = getObservationOk(a.get("observationId").asText());
        JsonNode cAfter = getObservationOk(c.get("observationId").asText());
        assertThat(aAfter.get("unifiedLatitude").asDouble()).isEqualTo(30.0);
        assertThat(aAfter.get("appliedFrameVersion").asText()).isEqualTo(FRAME_ZERO);
        assertThat(cAfter.get("unifiedLatitude").asDouble()).isEqualTo(85.0);
        assertThat(cAfter.get("appliedFrameVersion").asText()).isEqualTo(FRAME_ZERO);
        assertThat(rowCount("frame_recalc")).isZero();
        String deviceFrame = jdbcTemplate.queryForObject(
                "SELECT frame_version FROM device_frame WHERE device_id = 'dev-1'", String.class);
        assertThat(deviceFrame).isEqualTo(FRAME_ZERO);

        // 失败的 requestId 不占键：换成合法基准可正常执行
        updateDeviceFrame("req-rb3", "dev-1", FRAME_SHIFT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frameVersion").value(FRAME_SHIFT));
    }

    // ---------- 人工裁决保护 ----------

    @Test
    void manualResolutionIsPreservedAcrossRecalc() throws Exception {
        registerStandardFrames();
        JsonNode a = submitOk("req-mr1", "dev-1", 30.0, 120.0, FRAME_ZERO, T0);
        JsonNode b = submitOk("req-mr2", "dev-2", 30.0004, 120.0, FRAME_ZERO,
                "2026-09-26T10:00:10Z");
        String clusterId = b.get("clusterId").asText();
        String aId = a.get("observationId").asText();
        String bId = b.get("observationId").asText();

        // 人工裁决 B 胜出
        resolveCluster("req-mr3", clusterId, bId, "operator-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manuallyResolved").value(true))
                .andExpect(jsonPath("$.winnerObservationId").value(bId))
                .andExpect(jsonPath("$.resolvedBy").value("operator-1"));

        // dev-1 修改基准：已人工裁决簇的成员与结论冻结，不被自动重算覆盖
        updateDeviceFrame("req-mr4", "dev-1", FRAME_SHIFT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recalced").value(false));
        assertThat(rowCount("frame_recalc")).isZero();

        JsonNode aAfter = getObservationOk(aId);
        assertThat(aAfter.get("unifiedLatitude").asDouble()).isEqualTo(30.0);
        assertThat(aAfter.get("appliedFrameVersion").asText()).isEqualTo(FRAME_ZERO);
        assertThat(aAfter.get("clusterId").asText()).isEqualTo(clusterId);
        JsonNode cluster = getClusterOk(clusterId);
        assertThat(cluster.get("winnerObservationId").asText()).isEqualTo(bId);
        assertThat(cluster.get("manuallyResolved").asBoolean()).isTrue();
        assertThat(cluster.get("memberObservationIds")).hasSize(2);
    }

    @Test
    void resolveClusterValidationAndReplay() throws Exception {
        registerFrame("req-rv0", FRAME_ZERO, 0.0, 0.0).andExpect(status().isCreated());
        JsonNode a = submitOk("req-rv1", "dev-1", 30.0, 120.0, FRAME_ZERO, T0);
        JsonNode b = submitOk("req-rv2", "dev-2", 30.0004, 120.0, FRAME_ZERO, T0);
        JsonNode outsider = submitOk("req-rv3", "dev-3", 50.0, 120.0, FRAME_ZERO, T0);
        String clusterId = b.get("clusterId").asText();
        String aId = a.get("observationId").asText();

        // 簇不存在 404；胜出者非成员 422
        resolveCluster("req-rv4", "CL-nonexistent", aId, "op").andExpect(status().isNotFound());
        resolveCluster("req-rv5", clusterId, outsider.get("observationId").asText(), "op")
                .andExpect(status().isUnprocessableEntity());

        // 正常裁决；同键同参重放首个结果
        resolveCluster("req-rv6", clusterId, aId, "op").andExpect(status().isOk());
        resolveCluster("req-rv6", clusterId, aId, "op").andExpect(status().isOk());
        // 已人工解决的簇保持结论：重复裁决（含改判）一律 409
        resolveCluster("req-rv7", clusterId, aId, "op").andExpect(status().isConflict());
        resolveCluster("req-rv8", clusterId, b.get("observationId").asText(), "op")
                .andExpect(status().isConflict());
        JsonNode cluster = getClusterOk(clusterId);
        assertThat(cluster.get("winnerObservationId").asText()).isEqualTo(aId);
    }

    // ---------- 幂等与查询 ----------

    @Test
    void submitIdempotencyReplayAndConflict() throws Exception {
        registerFrame("req-i0", FRAME_ZERO, 0.0, 0.0).andExpect(status().isCreated());
        JsonNode first = submitOk("req-i1", "dev-1", 30.0, 120.0, FRAME_ZERO, T0);
        // 同键同参重放首个结果：同一观测标识，不产生新行
        JsonNode replay = submitOk("req-i1", "dev-1", 30.0, 120.0, FRAME_ZERO, T0);
        assertThat(replay.get("observationId").asText())
                .isEqualTo(first.get("observationId").asText());
        assertThat(rowCount("geo_observation")).isEqualTo(1);
        // 同键异参 409（指纹含设备、基准版本、原始坐标、时刻与字段内容）
        submitRaw("req-i1", "dev-1", 30.0001, 120.0, FRAME_ZERO, T0)
                .andExpect(status().isConflict());
        submitRaw("req-i1", "dev-2", 30.0, 120.0, FRAME_ZERO, T0)
                .andExpect(status().isConflict());
        assertThat(rowCount("geo_observation")).isEqualTo(1);
    }

    @Test
    void queryUnknownResourcesReturn404() throws Exception {
        mockMvc.perform(get("/api/geo/observations/{id}", "GO-none"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/geo/clusters/{id}", "CL-none"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/geo/recalcs/{id}", "FR-none"))
                .andExpect(status().isNotFound());
        // 无重算历史的设备返回空列表
        mockMvc.perform(get("/api/geo/devices/{deviceId}/recalcs", "dev-none"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }
}
