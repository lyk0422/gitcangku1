package com.example.starter.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 高度层容量与垂直分离 HTTP API 端到端测试（H2 + 真实 Spring MVC）：
 * 覆盖带配置、垂直分离明细、占用 429、取消释放与查询，以及 400/404/409/422 失败分支。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("高度层容量与垂直分离 HTTP API 端到端")
class AltitudeControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long T0 = 1_700_000_000_000L;
    private static final long H1 = 3_600_000L;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM altitude_occupancy");
        jdbc.update("DELETE FROM review_vertical_detail");
        jdbc.update("DELETE FROM altitude_band");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private String createZoneAndBand(int capacity) throws Exception {
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "requestId":"req-zone-1"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/zones/bands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zoneId\":\"z1\",\"expectedVersion\":1,\"requestId\":\"req-band-1\","
                                + "\"bands\":[{\"bandLower\":1000,\"bandUpper\":2000,"
                                + "\"capacity\":" + capacity + "}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.zoneVersion").value(2));
        return "z1";
    }

    private String createAltRouteAndReview(String routeId, int altitude, long start, long end,
                                           String reqRoute, String reqReview) throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"" + routeId + "\",\"requestId\":\"" + reqRoute + "\","
                                + "\"cruiseAltitude\":" + altitude + ",\"startTime\":" + start
                                + ",\"endTime\":" + end + ","
                                + "\"points\":[{\"x\":0,\"y\":10},{\"x\":100,\"y\":10}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value(1));
        // 当前空域版本为 2（建区 +1、配带 +1）
        MvcResult result = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"" + routeId + "\",\"routeVersion\":1,"
                                + "\"airspaceVersion\":2,\"requestId\":\"" + reqReview + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();
    }

    @Test
    @DisplayName("主流程：带配置→审查明细→占用→容量满429→取消释放→再次占用→查询")
    void fullAltitudeFlowOverHttp() throws Exception {
        createZoneAndBand(1);
        String rv1 = createAltRouteAndReview("r1", 1500, T0, T0 + H1, "rr1", "rv1");

        // 垂直分离明细：命中带
        mockMvc.perform(get("/api/airspace/reviews/" + rv1 + "/vertical-details"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].zoneId").value("z1"))
                .andExpect(jsonPath("$[0].verticalHit").value(true))
                .andExpect(jsonPath("$[0].bandLower").value(1000));

        // 首次占用成功
        MvcResult occResult = mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewId\":\"" + rv1 + "\",\"zoneId\":\"z1\","
                                + "\"bandLower\":1000,\"requestId\":\"occ1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.capacity").value(1))
                .andExpect(jsonPath("$.data.activeCount").value(1))
                .andReturn();
        String occupancyId = objectMapper.readTree(occResult.getResponse().getContentAsString())
                .path("data").path("occupancyId").asText();

        // 第二条时间重叠占用 → 429
        String rv2 = createAltRouteAndReview("r2", 1600, T0, T0 + H1, "rr2", "rv2");
        mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewId\":\"" + rv2 + "\",\"zoneId\":\"z1\","
                                + "\"bandLower\":1000,\"requestId\":\"occ2\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("BAND_CAPACITY_EXCEEDED"));

        // 取消 → 200，容量释放
        mockMvc.perform(post("/api/airspace/occupancies/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"occupancyId\":\"" + occupancyId + "\",\"requestId\":\"can1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        // 释放后占用成功
        mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewId\":\"" + rv2 + "\",\"zoneId\":\"z1\","
                                + "\"bandLower\":1000,\"requestId\":\"occ2b\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // 按时段查询：含历史，返回两条
        mockMvc.perform(get("/api/airspace/occupancies")
                        .param("startTime", String.valueOf(T0))
                        .param("endTime", String.valueOf(T0 + H1))
                        .param("zoneId", "z1").param("bandLower", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("垂直分离：高度不在带内 → verticalHit=false，占用 422")
    void verticalSeparationOverHttp() throws Exception {
        createZoneAndBand(1);
        String rv = createAltRouteAndReview("sep", 500, T0, T0 + H1, "rrs", "rvs");
        mockMvc.perform(get("/api/airspace/reviews/" + rv + "/vertical-details"))
                .andExpect(jsonPath("$[0].verticalHit").value(false))
                .andExpect(jsonPath("$[0].bandLower").doesNotExist());
        mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewId\":\"" + rv + "\",\"zoneId\":\"z1\","
                                + "\"bandLower\":1000,\"requestId\":\"occs\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VERTICAL_SEPARATION"));
    }

    @Test
    @DisplayName("高度带配置失败分支：重叠带400、容量下调400、版本冲突409")
    void bandConfigFailuresOverHttp() throws Exception {
        createZoneAndBand(2);
        // 新增重叠带 → 400
        mockMvc.perform(post("/api/airspace/zones/bands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","expectedVersion":2,"requestId":"bad1",
                                 "bands":[{"bandLower":1000,"bandUpper":2000,"capacity":2},
                                          {"bandLower":1500,"bandUpper":2500,"capacity":2}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BANDS_OVERLAP"));
        // 容量下调 → 400
        mockMvc.perform(post("/api/airspace/zones/bands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","expectedVersion":2,"requestId":"bad2",
                                 "bands":[{"bandLower":1000,"bandUpper":2000,"capacity":1}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPACITY_ONLY_INCREASE"));
        // 版本冲突 → 409
        mockMvc.perform(post("/api/airspace/zones/bands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","expectedVersion":99,"requestId":"bad3",
                                 "bands":[{"bandLower":1000,"bandUpper":2000,"capacity":5}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ZONE_VERSION_CONFLICT"));
        // 容量越界（0）→ 400 校验失败
        mockMvc.perform(post("/api/airspace/zones/bands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","expectedVersion":2,"requestId":"bad4",
                                 "bands":[{"bandLower":1000,"bandUpper":2000,"capacity":0}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("查询不存在的资源 404；STALE 占用 422")
    void notFoundAndStaleOverHttp() throws Exception {
        mockMvc.perform(get("/api/airspace/zones/ghost/bands"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/airspace/occupancies/oc_ghost"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/airspace/reviews/rv_ghost/vertical-details"))
                .andExpect(status().isNotFound());

        createZoneAndBand(2);
        String rv = createAltRouteAndReview("st", 1500, T0, T0 + H1, "rst", "rvst");
        // 上调容量推进空域版本，使旧审查 STALE
        mockMvc.perform(post("/api/airspace/zones/bands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","expectedVersion":2,"requestId":"up1",
                                 "bands":[{"bandLower":1000,"bandUpper":2000,"capacity":9}]}"""))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewId\":\"" + rv + "\",\"zoneId\":\"z1\","
                                + "\"bandLower\":1000,\"requestId\":\"ocst\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVIEW_STALE"));
    }

    @Test
    @DisplayName("占用同键同参重放：第二次 replayed=true 且 occupancyId 相同")
    void occupancyReplayOverHttp() throws Exception {
        createZoneAndBand(5);
        String rv = createAltRouteAndReview("rp", 1500, T0, T0 + H1, "rrp", "rvrp");
        String body = "{\"reviewId\":\"" + rv + "\",\"zoneId\":\"z1\","
                + "\"bandLower\":1000,\"requestId\":\"fixed-occ\"}";
        MvcResult first = mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
        JsonNode firstNode = objectMapper.readTree(first.getResponse().getContentAsString());
        mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.occupancyId")
                        .value(firstNode.path("data").path("occupancyId").asText()));
    }
}
