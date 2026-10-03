package com.example.starter.weighbridge;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 测试基类：真实 Spring 上下文 + H2 内存库（MODE=MySQL）+ MockMvc，逐用例清理数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public abstract class AbstractWebTest {

    /** 固定基准时间：2026-10-04 09:00 Asia/Shanghai。 */
    protected static final Instant T0 = Instant.parse("2026-10-04T01:00:00Z");
    protected static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected ObjectMapper om;


    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM errata_application");
        jdbc.update("DELETE FROM adjudication");
        jdbc.update("DELETE FROM weighing_record");
        jdbc.update("DELETE FROM dispute");
        jdbc.update("DELETE FROM net_weight_freeze");
        jdbc.update("DELETE FROM transport_order");
        clock.setInstant(T0);
    }

    protected JsonNode postJson(String url, Object body, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("POST %s 应返回 %d，实际响应：%s", url, expectedStatus,
                        result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : om.readTree(content);
    }

    protected JsonNode getJson(String url, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(get(url)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return om.readTree(result.getResponse().getContentAsString());
    }

    protected long createOrder(String orderNo, String vehicleNo) throws Exception {
        JsonNode node = postJson("/api/transport-orders",
                Map.of("orderNo", orderNo, "vehicleNo", vehicleNo), 201);
        return node.get("id").asLong();
    }

    protected long weigh(long orderId, String phase, int weightKg, String weighedAt,
                         String requestId) throws Exception {
        JsonNode node = postJson("/api/transport-orders/" + orderId + "/weighings",
                Map.of("phase", phase, "weightKg", weightKg, "weighedAt", weighedAt,
                        "requestId", requestId), 201);
        return node.get("id").asLong();
    }

    protected long applyReweigh(long orderId, String reason, String requestId) throws Exception {
        JsonNode node = postJson("/api/transport-orders/" + orderId + "/reweigh-requests",
                Map.of("reason", reason, "requestId", requestId), 201);
        return node.get("id").asLong();
    }

    protected long reweigh(long disputeId, int weightKg, String weighedAt, String requestId)
            throws Exception {
        JsonNode node = postJson("/api/disputes/" + disputeId + "/weighings",
                Map.of("weightKg", weightKg, "weighedAt", weighedAt, "requestId", requestId),
                201);
        return node.get("id").asLong();
    }

    protected long adjudicate(long disputeId, long adoptedWeighingId, String requestId)
            throws Exception {
        JsonNode node = postJson("/api/disputes/" + disputeId + "/adjudications",
                Map.of("adoptedWeighingId", adoptedWeighingId, "reason", "裁决留痕理由",
                        "decidedBy", "referee-01", "requestId", requestId), 201);
        return node.get("id").asLong();
    }

    protected JsonNode freeze(long orderId, String requestId) throws Exception {
        return postJson("/api/transport-orders/" + orderId + "/net-weight-freeze",
                Map.of("requestId", requestId), 201);
    }
}