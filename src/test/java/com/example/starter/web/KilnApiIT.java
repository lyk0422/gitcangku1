package com.example.starter.web;

import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 集成测试基类：真实 H2（MODE=MySQL）+ MockMvc，提供建程序、建批、上传、放行等助手。
 * 每个子类使用独立命名库，类结束后 SHUTDOWN 释放内存库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
abstract class KilnApiIT {

    static final Instant T0 = Instant.parse("2026-02-01T00:00:00Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper om;
    @Autowired
    MutableClock clock;

    private static JdbcTemplate staticJdbc;

    @Autowired
    void captureJdbc(JdbcTemplate jdbcTemplate) {
        staticJdbc = jdbcTemplate;
    }

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM anomaly_mark");
        jdbc.update("DELETE FROM reading_upload_request");
        jdbc.update("DELETE FROM temperature_reading");
        jdbc.update("DELETE FROM kiln_batch");
        jdbc.update("DELETE FROM firing_program_segment");
        jdbc.update("DELETE FROM firing_program_version");
        clock.setInstant(T0);
    }

    @AfterAll
    static void shutdownDatabase() {
        if (staticJdbc != null) {
            staticJdbc.execute("SHUTDOWN");
        }
    }

    /** 创建两段程序：00:00-00:20 目标600±15，00:20-00:40 目标800±12。 */
    long createProgram(String name, int version) throws Exception {
        String body = """
                {"name":"%s","versionNo":%d,"segments":[
                  {"startTime":"2026-02-01T00:00:00Z","endTime":"2026-02-01T00:20:00Z",
                   "targetTempC":600,"toleranceC":15},
                  {"startTime":"2026-02-01T00:20:00Z","endTime":"2026-02-01T00:40:00Z",
                   "targetTempC":800,"toleranceC":12}]}
                """.formatted(name, version);
        MvcResult result = mvc.perform(post("/api/program-versions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    long createBatch(String batchNo, long programId) throws Exception {
        MvcResult result = mvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchNo\":\"" + batchNo + "\",\"programVersionId\":" + programId + "}"))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    /** points: 每行 {偏移分钟, 温度}。 */
    ResultActions upload(long batchId, String requestId, int[][] points) throws Exception {
        StringBuilder sb = new StringBuilder("{\"requestId\":\"").append(requestId)
                .append("\",\"readings\":[");
        for (int i = 0; i < points.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"measuredAt\":\"").append(T0.plus(points[i][0], ChronoUnit.MINUTES))
                    .append("\",\"temperatureC\":").append(points[i][1]).append('}');
        }
        sb.append("]}");
        return mvc.perform(post("/api/batches/{id}/readings", batchId)
                .contentType(MediaType.APPLICATION_JSON).content(sb.toString()));
    }

    ResultActions release(long batchId) throws Exception {
        return mvc.perform(post("/api/batches/{id}/release", batchId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"releasedBy\":\"qc-wang\"}"));
    }

    JsonNode detail(long batchId) throws Exception {
        MvcResult result = mvc.perform(get("/api/batches/{id}", batchId)).andReturn();
        return om.readTree(result.getResponse().getContentAsString());
    }

    /** 完整合法轨迹：0..40 分钟每5分钟一个点。 */
    int[][] fullTrajectory() {
        int[] temps = {600, 605, 610, 612, 800, 805, 808, 810, 800};
        int[][] points = new int[temps.length][2];
        for (int i = 0; i < temps.length; i++) {
            points[i] = new int[] {i * 5, temps[i]};
        }
        return points;
    }
}