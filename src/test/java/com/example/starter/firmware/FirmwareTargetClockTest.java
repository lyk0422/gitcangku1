package com.example.starter.firmware;

import com.example.starter.StarterApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 可控时钟测试：固定业务时钟为某 UTC 时刻，验证标签修订时刻、快照解析时刻与暂停时刻
 * 都按 UTC 精确取值（不随 JVM/Jackson 的 Asia/Shanghai 时区漂移）。
 */
@SpringBootTest(classes = {StarterApplication.class, FirmwareTargetClockTest.FixedClockConfig.class})
@AutoConfigureMockMvc
class FirmwareTargetClockTest {

    static final Instant FIXED = Instant.parse("2026-09-28T07:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        /**
         * 与主配置 businessClock 共存，以不同 Bean 名加 @Primary 覆盖所有 Clock 注入点。
         */
        @Bean
        @Primary
        Clock fixedBusinessClock() {
            return Clock.fixed(FIXED, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_target");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device_tag");
        jdbc.update("DELETE FROM tag_revision");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("UPDATE tag_dict_meta SET version = 0 WHERE id = 1");
    }

    @Test
    void 固定时钟_标签修订与快照解析及暂停时刻均为固定UTC值() throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"rd1","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":1}
                """)).andExpect(status().isOk());
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"rd2","deviceId":"d2","model":"m1","currentVersion":"1.0.0","bucketNo":2}
                """)).andExpect(status().isOk());

        // 标签批量修订时刻 = 固定 UTC
        mockMvc.perform(post("/api/devices/tags/batch").contentType("application/json").content("""
                {"requestId":"b1","operator":"alice","items":[
                  {"deviceId":"d1","tags":["beta"]},{"deviceId":"d2","tags":["beta"]}]}
                """)).andExpect(status().isOk());
        mockMvc.perform(get("/api/tags/revisions"))
                .andExpect(jsonPath("$[0].changedAtUtc").value(FIXED.toString()));

        // 快照解析时刻 = 固定 UTC
        MvcResult release = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r1","operator":"alice","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                "ratio":100,"sampleFloor":2,"failureThresholdPercent":100,
                "target":{"include":["beta"]}}
                """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedAtUtc").value(FIXED.toString()))
                .andReturn();
        Number releaseIdValue = com.jayway.jsonpath.JsonPath.read(
                release.getResponse().getContentAsString(), "$.releaseId");
        long releaseId = releaseIdValue.longValue();
        mockMvc.perform(get("/api/releases/" + releaseId + "/target"))
                .andExpect(jsonPath("$.resolvedAtUtc").value(FIXED.toString()));

        // 两台快照成员各拉取并 FAILED，达到 floor=2/阈值100 触发暂停：暂停时刻 = 固定 UTC
        MvcResult t1 = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}")).andReturn();
        MvcResult t2 = mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"p2\"}")).andReturn();
        long task1 = ((Number) com.jayway.jsonpath.JsonPath.read(
                t1.getResponse().getContentAsString(), "$.task.taskId")).longValue();
        long task2 = ((Number) com.jayway.jsonpath.JsonPath.read(
                t2.getResponse().getContentAsString(), "$.task.taskId")).longValue();
        mockMvc.perform(post("/api/tasks/" + task1 + "/receipt").contentType("application/json")
                .content("{\"requestId\":\"c1\",\"result\":\"FAILED\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/" + task2 + "/receipt").contentType("application/json")
                .content("{\"requestId\":\"c2\",\"result\":\"FAILED\"}")).andExpect(status().isOk());
        mockMvc.perform(get("/api/releases/" + releaseId + "/history"))
                .andExpect(jsonPath("$.pauses[0].pausedAtUtc").value(FIXED.toString()));
    }
}
