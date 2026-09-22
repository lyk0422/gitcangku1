package com.example.starter.maintenance;

import com.example.starter.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.UnsupportedEncodingException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 集成测试基类：独立 H2 内存库，每个用例前清空业务数据，避免顺序依赖。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractApiTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM idempotency_key");
        jdbcTemplate.update("DELETE FROM maintenance_record");
        jdbcTemplate.update("DELETE FROM reading_revision");
        jdbcTemplate.update("DELETE FROM equipment_reading");
        jdbcTemplate.update("DELETE FROM equipment");
    }

    protected MvcResult postJson(String url, String json) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn();
    }

    protected MvcResult getJson(String url) throws Exception {
        return mockMvc.perform(get(url)).andReturn();
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(content(result));
    }

    protected String content(MvcResult result) throws UnsupportedEncodingException {
        return result.getResponse().getContentAsString();
    }

    protected String registerEquipment(String requestId, String equipmentId, int interval) {
        return """
                {"requestId":"%s","equipmentId":"%s","maintenanceIntervalMinutes":%d}
                """.formatted(requestId, equipmentId, interval);
    }

    protected String addReading(String requestId, int expectedVersion, String readingId,
                                String sampledAt, long accumulatedMinutes) {
        return """
                {"requestId":"%s","expectedVersion":%d,"readingId":"%s","sampledAt":"%s","accumulatedMinutes":%d}
                """.formatted(requestId, expectedVersion, readingId, sampledAt, accumulatedMinutes);
    }

    protected String reviseReading(String requestId, int expectedVersion, long accumulatedMinutes) {
        return """
                {"requestId":"%s","expectedVersion":%d,"accumulatedMinutes":%d}
                """.formatted(requestId, expectedVersion, accumulatedMinutes);
    }

    protected String completeMaintenance(String requestId, int expectedVersion,
                                         String anchorReadingId, int anchorRevisionNo) {
        return """
                {"requestId":"%s","expectedVersion":%d,"anchorReadingId":"%s","anchorRevisionNo":%d}
                """.formatted(requestId, expectedVersion, anchorReadingId, anchorRevisionNo);
    }
}
