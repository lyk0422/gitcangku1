package com.example.starter.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 集成测试基类：启动完整 Spring 上下文与 H2（MODE=MySQL），
 * 每个用例前按外键顺序清理业务数据，避免用例间顺序依赖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(H2ShutdownConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM unblind_request");
        jdbcTemplate.update("DELETE FROM allocation");
        jdbcTemplate.update("DELETE FROM experiment_seat");
        jdbcTemplate.update("DELETE FROM experiment");
        jdbcTemplate.update("DELETE FROM idempotency_record");
    }

    protected MvcResult postJson(String path, String actorId, String role, Object body) throws Exception {
        return mockMvc.perform(post(path)
                        .header("X-Actor-Id", actorId)
                        .header("X-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    protected MvcResult postJsonNoHeaders(String path, Object body) throws Exception {
        return mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    protected MvcResult getJson(String path, String actorId, String role) throws Exception {
        return mockMvc.perform(get(path)
                        .header("X-Actor-Id", actorId)
                        .header("X-Role", role))
                .andReturn();
    }

    protected JsonNode readBody(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
