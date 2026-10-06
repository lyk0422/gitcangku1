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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分层密度估计成果的幂等边界测试：同参重放（含层与成员换序）、异参 409、失败不占键。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EstimateIdempotencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM density_estimate_member");
        jdbcTemplate.update("DELETE FROM density_estimate_layer");
        jdbcTemplate.update("DELETE FROM density_estimate");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private void createObs(String requestId, String obsId, int count, int area) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", obsId);
        body.put("location", "样方");
        body.put("reading", "1.0");
        body.put("note", "备注");
        body.put("individualCount", count);
        body.put("sampledArea", area);
        mockMvc.perform(post("/api/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private Map<String, Object> member(String obsId, int version) {
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("observationId", obsId);
        member.put("version", version);
        return member;
    }

    private Map<String, Object> layer(int totalArea, Map<String, Object>... members) {
        Map<String, Object> layer = new LinkedHashMap<>();
        layer.put("totalArea", totalArea);
        layer.put("members", List.of(members));
        return layer;
    }

    private ResultActions estimate(String requestId, String estimateId, String operator,
                                   List<Map<String, Object>> layers) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("estimateId", estimateId);
        body.put("operator", operator);
        body.put("layers", layers);
        return mockMvc.perform(post("/api/estimates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int estimateCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM density_estimate", Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    void sameRequestIdSameParamsReplaysFirstResponse() throws Exception {
        createObs("req-o1", "obs-1", 1, 3);
        createObs("req-o2", "obs-2", 2, 3);
        List<Map<String, Object>> layers =
                List.of(layer(100, member("obs-1", 1)), layer(200, member("obs-2", 1)));

        MvcResult first = estimate("req-i1", "est-1", "operator-a", layers)
                .andExpect(status().isCreated())
                .andReturn();
        String firstBody = first.getResponse().getContentAsString();

        // 同键同参重放：返回首次完整响应原文，不生成新成果
        estimate("req-i1", "est-1", "operator-a", layers)
                .andExpect(status().isCreated())
                .andExpect(content().string(firstBody));
        assertThat(estimateCount()).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-i1'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void reorderedLayersAndMembersAreSameParams() throws Exception {
        createObs("req-o1", "obs-1", 1, 3);
        createObs("req-o2", "obs-2", 2, 3);
        createObs("req-o3", "obs-3", 4, 5);

        MvcResult first = estimate("req-r1", "est-1", "operator-a", List.of(
                layer(100, member("obs-1", 1), member("obs-2", 1)),
                layer(200, member("obs-3", 1))))
                .andExpect(status().isCreated())
                .andReturn();
        String firstBody = first.getResponse().getContentAsString();

        // 层换序且层内成员换序：视为同参，返回首次完整响应（保留首次的层顺序）
        estimate("req-r1", "est-1", "operator-a", List.of(
                layer(200, member("obs-3", 1)),
                layer(100, member("obs-2", 1), member("obs-1", 1))))
                .andExpect(status().isCreated())
                .andExpect(content().string(firstBody))
                .andExpect(jsonPath("$.layers[0].totalArea").value(100));
        assertThat(estimateCount()).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        createObs("req-o1", "obs-1", 1, 3);
        createObs("req-o2", "obs-2", 2, 3);
        estimate("req-d1", "est-1", "operator-a",
                List.of(layer(100, member("obs-1", 1)), layer(200, member("obs-2", 1))))
                .andExpect(status().isCreated());

        // 改面积、改成果标识、改操作者均为异参：409
        estimate("req-d1", "est-1", "operator-a",
                List.of(layer(101, member("obs-1", 1)), layer(200, member("obs-2", 1))))
                .andExpect(status().isConflict());
        estimate("req-d1", "est-2", "operator-a",
                List.of(layer(100, member("obs-1", 1)), layer(200, member("obs-2", 1))))
                .andExpect(status().isConflict());
        estimate("req-d1", "est-1", "operator-b",
                List.of(layer(100, member("obs-1", 1)), layer(200, member("obs-2", 1))))
                .andExpect(status().isConflict());
        assertThat(estimateCount()).isEqualTo(1);
    }

    @Test
    void estimateIdReuseWithDifferentRequestIdReturns409() throws Exception {
        createObs("req-o1", "obs-1", 1, 3);
        createObs("req-o2", "obs-2", 2, 3);
        List<Map<String, Object>> layers =
                List.of(layer(100, member("obs-1", 1)), layer(200, member("obs-2", 1)));
        estimate("req-a1", "est-1", "operator-a", layers).andExpect(status().isCreated());

        estimate("req-a2", "est-1", "operator-a", layers)
                .andExpect(status().isConflict());
        assertThat(estimateCount()).isEqualTo(1);
        // 冲突请求失败回滚，不占其 requestId
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-a2'", Integer.class);
        assertThat(requestRows).isZero();
    }
}
