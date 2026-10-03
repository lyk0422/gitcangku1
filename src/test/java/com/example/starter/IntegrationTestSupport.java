package com.example.starter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
 * 集成测试基类：真实 Spring 上下文 + H2 内存库（MODE=MySQL），每个用例前清空业务表并重置时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
abstract class IntegrationTestSupport {

    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper om;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void cleanDatabase() throws Exception {
        jdbc.update("DELETE FROM execution_report");
        jdbc.update("DELETE FROM blast_command");
        jdbc.update("DELETE FROM checkpoint_confirmation");
        jdbc.update("DELETE FROM person_entry_event");
        jdbc.update("DELETE FROM checkpoint");
        jdbc.update("DELETE FROM blast_plan");
        clock.reset(T0);
    }

    protected MvcResult postJson(String url, Object body) throws Exception {
        String json = body instanceof String s ? s : om.writeValueAsString(body);
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .characterEncoding("UTF-8").content(json.getBytes(StandardCharsets.UTF_8)))
                .andReturn();
    }

    protected MvcResult getJson(String url) throws Exception {
        return mvc.perform(get(url)).andReturn();
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return om.readTree(result.getResponse().getContentAsString());
    }

    /** 创建三点检查计划，返回 planId。 */
    protected long createThreePointPlan() throws Exception {
        MvcResult result = postJson("/api/plans", """
                {"name":"采场A区清场","boundary":"东侧 500m 警戒圈","checkpoints":[
                  {"code":"CP-N","description":"北侧入口"},
                  {"code":"CP-E","description":"东侧便道"},
                  {"code":"CP-S","description":"南侧坡道"}]}
                """);
        if (result.getResponse().getStatus() != 201) {
            throw new IllegalStateException("创建计划失败: " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString());
        }
        return body(result).get("planId").asLong();
    }

    protected MvcResult confirm(long planId, String code) throws Exception {
        return postJson("/api/plans/" + planId + "/confirmations",
                "{\"checkpointCode\":\"" + code + "\",\"confirmedBy\":\"安全员甲\",\"headcount\":0}");
    }

    protected void confirmAll(long planId) throws Exception {
        for (String code : new String[] {"CP-N", "CP-E", "CP-S"}) {
            MvcResult r = confirm(planId, code);
            if (r.getResponse().getStatus() != 201) {
                throw new IllegalStateException("确认失败: " + code);
            }
        }
    }

    protected MvcResult issue(long planId, String requestId, String issuedBy) throws Exception {
        return postJson("/api/plans/" + planId + "/commands",
                "{\"requestId\":\"" + requestId + "\",\"issuedBy\":\"" + issuedBy + "\"}");
    }

    protected MvcResult execute(long commandId, String requestId, String reportedBy)
            throws Exception {
        return postJson("/api/commands/" + commandId + "/executions",
                "{\"requestId\":\"" + requestId + "\",\"reportedBy\":\"" + reportedBy
                        + "\",\"result\":\"SUCCESS\"}");
    }

    protected MvcResult entry(long planId, String detectedBy) throws Exception {
        return postJson("/api/plans/" + planId + "/entries",
                "{\"detectedBy\":\"" + detectedBy + "\"}");
    }
}