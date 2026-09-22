package com.example.starter;

import com.example.starter.api.dto.CreateReviewRequest;
import com.example.starter.api.dto.CreateRouteRequest;
import com.example.starter.api.dto.CreateZoneRequest;
import com.example.starter.api.dto.PointDto;
import com.example.starter.api.dto.ReplaceRouteRequest;
import com.example.starter.api.dto.RevokeZoneRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 集成测试基类：使用独立 H2 内存库，每个用例前清空业务数据，避免顺序依赖。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    /**
     * 清空业务表并重置全局空域版本，保证用例间隔离。
     */
    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM reviews");
        jdbc.update("DELETE FROM idempotency_keys");
        jdbc.update("DELETE FROM routes");
        jdbc.update("DELETE FROM zones");
        jdbc.update("UPDATE airspace_state SET version = 0 WHERE id = 1");
    }

    protected MvcResult createZone(String requestId, String zoneId,
                                   int minX, int minY, int maxX, int maxY) throws Exception {
        return postJson("/api/zones",
                new CreateZoneRequest(requestId, zoneId, minX, minY, maxX, maxY));
    }

    protected MvcResult revokeZone(String requestId, String zoneId) throws Exception {
        return postJson("/api/zones/" + zoneId + "/revoke", new RevokeZoneRequest(requestId));
    }

    protected MvcResult createRoute(String requestId, String routeId, int... coords) throws Exception {
        return postJson("/api/routes",
                new CreateRouteRequest(requestId, routeId, toPoints(coords)));
    }

    protected MvcResult replaceRoute(String requestId, String routeId, int expectedVersion,
                                     int... coords) throws Exception {
        return putJson("/api/routes/" + routeId,
                new ReplaceRouteRequest(requestId, expectedVersion, toPoints(coords)));
    }

    protected MvcResult review(String requestId, String routeId, int routeVersion,
                               long airspaceVersion) throws Exception {
        return postJson("/api/reviews",
                new CreateReviewRequest(requestId, routeId, routeVersion, airspaceVersion));
    }

    protected MvcResult getReview(String reviewId) throws Exception {
        return mockMvc.perform(get("/api/reviews/" + reviewId)).andReturn();
    }

    protected MvcResult getCurrentReview(String routeId) throws Exception {
        return mockMvc.perform(get("/api/routes/" + routeId + "/current-review")).andReturn();
    }

    protected MvcResult postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    protected MvcResult putJson(String url, Object body) throws Exception {
        return mockMvc.perform(put(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    protected long airspaceVersion() {
        Long version = jdbc.queryForObject(
                "SELECT version FROM airspace_state WHERE id = 1", Long.class);
        return version == null ? -1 : version;
    }

    protected int countRows(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? -1 : count;
    }

    private static List<PointDto> toPoints(int... coords) {
        if (coords.length % 2 != 0) {
            throw new IllegalArgumentException("坐标必须成对出现");
        }
        return java.util.stream.IntStream.range(0, coords.length / 2)
                .mapToObj(i -> new PointDto(coords[2 * i], coords[2 * i + 1]))
                .toList();
    }

    protected static List<PointDto> repeatedPoints(int x, int y, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new PointDto(x, y))
                .toList();
    }

    protected static List<PointDto> manyPoints(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new PointDto(i, i))
                .toList();
    }

    protected static String json(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    protected static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }
}
