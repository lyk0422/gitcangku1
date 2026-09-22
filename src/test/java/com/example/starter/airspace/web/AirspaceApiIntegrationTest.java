package com.example.starter.airspace.web;

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

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 基于 H2（MODE=MySQL）真实建表与事务的 API 集成测试：
 * 主流程、失败分支、版本失效与幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AirspaceApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private final AtomicLong seq = new AtomicLong();

    @BeforeEach
    void cleanTables() {
        jdbc.execute("DELETE FROM request_record");
        jdbc.execute("DELETE FROM route_review");
        jdbc.execute("DELETE FROM route_point");
        jdbc.execute("DELETE FROM route");
        jdbc.execute("DELETE FROM no_fly_zone");
        jdbc.execute("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
    }

    private String rid() {
        return "req-" + seq.incrementAndGet() + "-" + UUID.randomUUID();
    }

    private String zoneId() {
        return "zone-" + seq.incrementAndGet() + "-" + UUID.randomUUID();
    }

    private String routeId() {
        return "route-" + seq.incrementAndGet() + "-" + UUID.randomUUID();
    }

    private MvcResult createZone(String requestId, String zoneId,
                                 int xMin, int yMin, int xMax, int yMax) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "xMin", xMin, "yMin", yMin, "xMax", xMax, "yMax", yMax));
        return mockMvc.perform(post("/api/airspace/zones/{zoneId}", zoneId)
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private MvcResult revokeZone(String requestId, String zoneId) throws Exception {
        return mockMvc.perform(post("/api/airspace/zones/{zoneId}/revoke", zoneId)
                        .header("X-Request-Id", requestId))
                .andReturn();
    }

    private MvcResult createRoute(String requestId, String routeId, int[][] points)
            throws Exception {
        StringBuilder sb = new StringBuilder("{\"points\":[");
        for (int i = 0; i < points.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"x\":").append(points[i][0]).append(",\"y\":")
                    .append(points[i][1]).append('}');
        }
        sb.append("]}");
        return mockMvc.perform(post("/api/airspace/routes/{routeId}", routeId)
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON).content(sb.toString()))
                .andReturn();
    }

    private MvcResult replaceRoute(String requestId, String routeId,
                                   int expectedVersion, int[][] points) throws Exception {
        StringBuilder sb = new StringBuilder("{\"expectedVersion\":")
                .append(expectedVersion).append(",\"points\":[");
        for (int i = 0; i < points.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"x\":").append(points[i][0]).append(",\"y\":")
                    .append(points[i][1]).append('}');
        }
        sb.append("]}");
        return mockMvc.perform(post("/api/airspace/routes/{routeId}/replace", routeId)
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON).content(sb.toString()))
                .andReturn();
    }

    private MvcResult submitReview(String requestId, String routeId,
                                   int routeVersion, int airspaceVersion) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "routeId", routeId,
                "routeVersion", routeVersion,
                "airspaceVersion", airspaceVersion));
        return mockMvc.perform(post("/api/airspace/reviews")
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ---------- 禁飞区主流程 ----------

    @Test
    void zoneCreateBumpsGlobalVersionAndIsReplayable() throws Exception {
        String zoneId = zoneId();
        String requestId = rid();
        MvcResult first = createZone(requestId, zoneId, -10, -10, 10, 10);
        assertEquals(201, first.getResponse().getStatus());
        JsonNode body = json(first);
        assertEquals("ACTIVE", body.path("state").asText());
        assertEquals(1, body.path("airspaceVersion").asInt());
        String firstBody = first.getResponse().getContentAsString();

        // 同键同参重放：原成功结果
        MvcResult replay = createZone(requestId, zoneId, -10, -10, 10, 10);
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(firstBody, replay.getResponse().getContentAsString());

        // 同键异参：409
        MvcResult mismatch = createZone(requestId, zoneId, 0, 0, 20, 20);
        assertEquals(409, mismatch.getResponse().getStatus());
        assertEquals("IDEMPOTENCY_PARAM_MISMATCH", json(mismatch).path("error").asText());

        // 区域仍然只有一个，版本未因重放/冲突而增加
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM no_fly_zone", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id=1", Integer.class));
    }

    @Test
    void zoneCreateFailuresDoNotOccupyKey() throws Exception {
        String zoneId = zoneId();
        String requestId = rid();

        // 退化矩形：422（业务失败，不占键）
        MvcResult degenerate = createZone(requestId, zoneId, 10, 10, 10, 20);
        assertEquals(422, degenerate.getResponse().getStatus());

        // 同一 requestId 随后可用于成功请求
        MvcResult ok = createZone(requestId, zoneId, -10, -10, 10, 10);
        assertEquals(201, ok.getResponse().getStatus());

        // 已存在再创建：409，失败事务回滚（不留占位键、全局版本不增加）
        String failedReq = rid();
        MvcResult duplicate = createZone(failedReq, zoneId, -10, -10, 10, 10);
        assertEquals(409, duplicate.getResponse().getStatus());
        assertEquals("ZONE_ALREADY_EXISTS", json(duplicate).path("error").asText());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_record WHERE request_id = ?",
                Integer.class, failedReq));
        assertEquals(1, jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id=1", Integer.class));
    }

    @Test
    void revokeZoneFlowAndReplay() throws Exception {
        String zoneId = zoneId();
        createZone(rid(), zoneId, -10, -10, 10, 10);

        assertEquals(404, revokeZone(rid(), zoneId() + "x").getResponse().getStatus());

        String revokeReq = rid();
        MvcResult revoked = revokeZone(revokeReq, zoneId);
        assertEquals(200, revoked.getResponse().getStatus());
        JsonNode body = json(revoked);
        assertEquals("REVOKED", body.path("state").asText());
        assertEquals(2, body.path("airspaceVersion").asInt());

        // 同键重放
        MvcResult replay = revokeZone(revokeReq, zoneId);
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(revoked.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());

        // 新请求重复撤销：409
        MvcResult again = revokeZone(rid(), zoneId);
        assertEquals(409, again.getResponse().getStatus());
        assertEquals("ZONE_ALREADY_REVOKED", json(again).path("error").asText());
    }

    // ---------- 航线校验与版本 ----------

    @Test
    void routeValidationAndCreate() throws Exception {
        String routeId = routeId();

        // 点不足
        assertEquals(422, createRoute(rid(), routeId,
                new int[][]{{0, 0}}).getResponse().getStatus());
        // 全部点相同
        assertEquals(422, createRoute(rid(), routeId,
                new int[][]{{1, 1}, {1, 1}}).getResponse().getStatus());
        // 坐标越界
        assertEquals(422, createRoute(rid(), routeId,
                new int[][]{{0, 0}, {100001, 0}}).getResponse().getStatus());
        // 51 个点
        int[][] tooMany = new int[51][2];
        for (int i = 0; i < 51; i++) {
            tooMany[i] = new int[]{i, i == 50 ? 1 : 0};
        }
        assertEquals(422, createRoute(rid(), routeId, tooMany).getResponse().getStatus());

        MvcResult ok = createRoute(rid(), routeId, new int[][]{{0, 0}, {100, 100}});
        assertEquals(201, ok.getResponse().getStatus());
        assertEquals(1, json(ok).path("version").asInt());

        assertEquals(409, createRoute(rid(), routeId,
                new int[][]{{0, 0}, {1, 1}}).getResponse().getStatus());
    }

    @Test
    void replaceRequiresExpectedVersionAndInvalidatesReview() throws Exception {
        String routeId = routeId();
        createRoute(rid(), routeId, new int[][]{{-50, 0}, {50, 0}});

        assertEquals(404, replaceRoute(rid(), routeId() + "x", 1,
                new int[][]{{0, 0}, {1, 1}}).getResponse().getStatus());

        assertEquals(409, replaceRoute(rid(), routeId, 9,
                new int[][]{{0, 0}, {1, 1}}).getResponse().getStatus());

        MvcResult replaced = replaceRoute(rid(), routeId, 1,
                new int[][]{{-50, 50}, {50, 50}});
        assertEquals(200, replaced.getResponse().getStatus());
        assertEquals(2, json(replaced).path("version").asInt());

        // 再次用旧 expectedVersion：409
        assertEquals(409, replaceRoute(rid(), routeId, 1,
                new int[][]{{-50, 60}, {50, 60}}).getResponse().getStatus());

        // 新版本点列已落库
        Integer y = jdbc.queryForObject(
                "SELECT y FROM route_point WHERE route_id=? AND version=2 AND seq=0",
                Integer.class, routeId);
        assertEquals(50, y);
    }

    // ---------- 审核主流程 ----------

    @Test
    void reviewClearBlockedAndAllHits() throws Exception {
        String zoneA = zoneId();
        String zoneB = zoneId();
        createZone(rid(), zoneA, -10, -10, 10, 10);   // 版本1
        createZone(rid(), zoneB, 40, -10, 60, 10);    // 版本2
        String routeId = routeId();
        // 航点全部在区域外，但线段先后穿越两个矩形
        createRoute(rid(), routeId, new int[][]{{-50, 0}, {100, 0}});

        // 版本不匹配
        assertEquals(409, submitReview(rid(), routeId, 1, 1).getResponse().getStatus());
        assertEquals(409, submitReview(rid(), routeId, 2, 2).getResponse().getStatus());
        assertEquals(404, submitReview(rid(), routeId() + "x", 1, 2).getResponse().getStatus());

        MvcResult blocked = submitReview(rid(), routeId, 1, 2);
        assertEquals(201, blocked.getResponse().getStatus());
        JsonNode body = json(blocked);
        assertEquals("BLOCKED", body.path("conclusion").asText());
        assertEquals(2, body.path("hitZoneIds").size());
        // 字典序去重
        assertEquals(zoneA, body.path("hitZoneIds").get(0).asText());
        assertEquals(zoneB, body.path("hitZoneIds").get(1).asText());
        assertEquals(2, body.path("airspaceVersion").asInt());

        // 绕行航线：CLEAR
        String clearRoute = routeId();
        createRoute(rid(), clearRoute, new int[][]{{-50, 50}, {100, 50}});
        MvcResult clear = submitReview(rid(), clearRoute, 1, 2);
        assertEquals(201, clear.getResponse().getStatus());
        assertEquals("CLEAR", json(clear).path("conclusion").asText());
        assertEquals(0, json(clear).path("hitZoneIds").size());
    }

    @Test
    void boundaryTouchIsBlocked() throws Exception {
        createZone(rid(), "edge-zone", 0, 0, 10, 10);
        String routeId = routeId();
        // 线段仅接触矩形角点 (0,10)
        createRoute(rid(), routeId, new int[][]{{-20, 20}, {20, 0}});
        MvcResult result = submitReview(rid(), routeId, 1, 1);
        assertEquals(201, result.getResponse().getStatus());
        assertEquals("BLOCKED", json(result).path("conclusion").asText());
        assertEquals("edge-zone", json(result).path("hitZoneIds").get(0).asText());
    }

    @Test
    void historyIsImmutableAndCurrentGoesStaleAfterZoneChangeAndRouteReplace() throws Exception {
        String zoneId = zoneId();
        createZone(rid(), zoneId, -10, -10, 10, 10); // v1
        String routeId = routeId();
        createRoute(rid(), routeId, new int[][]{{-50, 0}, {50, 0}});

        MvcResult blocked = submitReview(rid(), routeId, 1, 1);
        String blockedReviewId = json(blocked).path("reviewId").asText();

        // 撤销区域 → 空域 v2；旧 BLOCKED 对当前视图变 STALE，但历史保留原结论
        revokeZone(rid(), zoneId);
        mockMvc.perform(get("/api/airspace/routes/{routeId}/reviews/current", routeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STALE"))
                .andExpect(jsonPath("$.review.conclusion").value("BLOCKED"));

        // 用新版本重新审核 → CLEAR 且当前视图 CURRENT
        MvcResult clear = submitReview(rid(), routeId, 1, 2);
        assertEquals("CLEAR", json(clear).path("conclusion").asText());
        mockMvc.perform(get("/api/airspace/routes/{routeId}/reviews/current", routeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CURRENT"))
                .andExpect(jsonPath("$.review.conclusion").value("CLEAR"));

        // 替换航线 → 即使最新结论是 CLEAR，也必须 STALE，不能当成当前通过
        replaceRoute(rid(), routeId, 1, new int[][]{{-50, 30}, {50, 30}});
        mockMvc.perform(get("/api/airspace/routes/{routeId}/reviews/current", routeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STALE"))
                .andExpect(jsonPath("$.review.conclusion").value("CLEAR"));

        // 历史按时间保留两条原结论
        mockMvc.perform(get("/api/airspace/routes/{routeId}/reviews", routeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].reviewId").value(blockedReviewId))
                .andExpect(jsonPath("$[0].conclusion").value("BLOCKED"))
                .andExpect(jsonPath("$[1].conclusion").value("CLEAR"));
    }

    @Test
    void missingRequestHeaderIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/airspace/zones/{zoneId}", zoneId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"xMin\":0,\"yMin\":0,\"xMax\":1,\"yMax\":1}"))
                .andExpect(status().isBadRequest());
    }
}
