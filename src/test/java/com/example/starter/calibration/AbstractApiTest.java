package com.example.starter.calibration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * API 集成测试基类：真实 Spring 上下文 + 真实 H2（MODE=MySQL）写入，逐用例清理场景数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
abstract class AbstractApiTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void cleanScenarioData() {
        jdbc.update("DELETE FROM task_binding");
        jdbc.update("DELETE FROM receive_task");
        jdbc.update("DELETE FROM antenna_calibration");
        clock.set(TestClockConfig.BASE_NOW);
    }

    /** 通过 API 上报校准并返回校准编号。 */
    protected long newCalibration(String antenna, String calibratedAt, String az, String el)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/calibrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"antennaCode":"%s","calibratedAt":"%s",
                                 "azimuthDeviationDeg":%s,"elevationDeviationDeg":%s}
                                """.formatted(antenna, calibratedAt, az, el)))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asLong();
    }

    /** 通过 API 创建任务并返回任务主键。 */
    protected long newTask(String code, String antenna, String start, String end) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskCode":"%s","antennaCode":"%s","startTime":"%s","endTime":"%s"}
                                """.formatted(code, antenna, start, end)))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asLong();
    }

    /** 通过 API 发起绑定，返回原始响应用于断言状态码。 */
    protected MvcResult bind(String requestId, long taskId, long calibrationId) throws Exception {
        return mockMvc.perform(post("/api/bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","taskId":%d,"calibrationId":%d}
                                """.formatted(requestId, taskId, calibrationId)))
                .andReturn();
    }

    /** 通过 API 查询任务视图。 */
    protected JsonNode getTask(long taskId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/tasks/{id}", taskId)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 通过 API 查询校准视图。 */
    protected JsonNode getCalibration(long calibrationId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/calibrations/{id}", calibrationId)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 通过 API 作废校准，返回原始响应。 */
    protected MvcResult invalidate(long calibrationId, String reason) throws Exception {
        return mockMvc.perform(post("/api/calibrations/{id}/invalidate", calibrationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andReturn();
    }

    /** 通过 API 标记任务完成。 */
    protected void completeTask(long taskId) throws Exception {
        mockMvc.perform(post("/api/tasks/{id}/complete", taskId)).andReturn();
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}