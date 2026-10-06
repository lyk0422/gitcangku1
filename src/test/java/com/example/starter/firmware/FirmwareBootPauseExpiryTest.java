package com.example.starter.firmware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 双分区试启动：暂停禁止新试启动但不撤销已有 TRIAL 健康确认、查询惰性结算到期任务、
 * 幂等重放与异参 409、取消级联、普通发布回执规则不变（H2 内存库，MODE=MySQL，可控时钟）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class FirmwareBootPauseExpiryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM boot_rollback_record");
        jdbc.update("DELETE FROM boot_task");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        clock.set(TestClockConfig.T0);
    }

    private void registerDevice(String requestId, String deviceId) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"m1","currentVersion":"v1","bucketNo":1}
                """.formatted(requestId, deviceId))).andExpect(status().isOk());
    }

    private long createDualRelease(String requestId, int sampleFloor, int threshold) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"m1","fromVersion":"v1","toVersion":"v2","ratio":100,
                 "sampleFloor":%d,"failureThresholdPercent":%d,"dualPartition":true}
                """.formatted(requestId, sampleFloor, threshold))).andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),
                "$.releaseId");
        return id.longValue();
    }

    private long pullBootTask(String deviceId, String requestId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json")
                        .content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bootTask.status").value("PENDING"))
                .andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),
                "$.bootTask.taskId");
        return id.longValue();
    }

    private void trialBoot(long taskId, String requestId) throws Exception {
        mockMvc.perform(post("/api/boot-tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("""
                                {"requestId":"%s","result":"SUCCESS","phase":"TRIAL_BOOT",
                                 "targetPartition":"B","targetVersion":"v2"}
                                """.formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"));
    }

    private void failBoot(long taskId, String requestId) throws Exception {
        mockMvc.perform(post("/api/boot-tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"result\":\"FAILED\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));
    }

    @Test
    void 暂停禁止新试启动_但不撤销已有TRIAL的健康确认() throws Exception {
        for (int i = 1; i <= 4; i++) {
            registerDevice("rd" + i, "d" + i);
        }
        long releaseId = createDualRelease("rr", 2, 50);
        long t1 = pullBootTask("d1", "rp1");
        long t2 = pullBootTask("d2", "rp2");
        long t3 = pullBootTask("d3", "rp3");
        long t4 = pullBootTask("d4", "rp4");
        // d4 先进入 TRIAL
        trialBoot(t4, "rt4");
        // d1、d2 失败使失败率达到阈值，发布单自动暂停
        failBoot(t1, "rf1");
        failBoot(t2, "rf2");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"));

        // 暂停禁止新试启动
        mockMvc.perform(post("/api/boot-tasks/" + t3 + "/receipt").contentType("application/json")
                        .content("""
                                {"requestId":"rt3","result":"SUCCESS","phase":"TRIAL_BOOT",
                                 "targetPartition":"B","targetVersion":"v2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_ACTIVE"));
        // 已有 TRIAL 的健康确认不被撤销
        mockMvc.perform(post("/api/boot-tasks/" + t4 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rh4\",\"result\":\"SUCCESS\",\"phase\":\"HEALTH_CONFIRM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"));
        mockMvc.perform(get("/api/devices/d4"))
                .andExpect(jsonPath("$.currentVersion").value("v2"))
                .andExpect(jsonPath("$.activePartition").value("B"));
        // 暂停不创建新任务：未命中设备拉取为空
        registerDevice("rd5", "d5");
        mockMvc.perform(post("/api/devices/d5/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist())
                .andExpect(jsonPath("$.bootTask").doesNotExist());
    }

    @Test
    void 查询结算到期任务_不依赖后台线程() throws Exception {
        registerDevice("r1", "d1");
        long releaseId = createDualRelease("r2", 2, 100);
        long taskId = pullBootTask("d1", "r3");
        trialBoot(taskId, "r4");

        clock.advance(Duration.ofSeconds(61));
        mockMvc.perform(get("/api/releases/" + releaseId + "/boot-tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].status").value("ROLLED_BACK"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/boot-tasks")
                        .param("status", "TRIAL"))
                .andExpect(jsonPath("$.tasks.length()").value(0));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("v1"))
                .andExpect(jsonPath("$.activePartition").value("A"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM boot_rollback_record WHERE task_id = ?", Long.class, taskId))
                .isEqualTo(1);
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(1));
    }

    @Test
    void 幂等_同键同参重放首次响应_异参409() throws Exception {
        registerDevice("r1", "d1");
        createDualRelease("r2", 2, 100);
        long taskId = pullBootTask("d1", "r3");

        MvcResult first = mockMvc.perform(post("/api/boot-tasks/" + taskId + "/receipt")
                        .contentType("application/json").content("""
                                {"requestId":"rk","result":"SUCCESS","phase":"TRIAL_BOOT",
                                 "targetPartition":"B","targetVersion":"v2"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult replay = mockMvc.perform(post("/api/boot-tasks/" + taskId + "/receipt")
                        .contentType("application/json").content("""
                                {"requestId":"rk","result":"SUCCESS","phase":"TRIAL_BOOT",
                                 "targetPartition":"B","targetVersion":"v2"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 同键异参：409（结构化指纹，含分隔符的参数也不会误判为同参）
        mockMvc.perform(post("/api/boot-tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("""
                                {"requestId":"rk","result":"SUCCESS","phase":"TRIAL_BOOT",
                                 "targetPartition":"B","targetVersion":"v2|x"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void 取消级联PENDING试启动任务_后到回执409() throws Exception {
        registerDevice("r1", "d1");
        long releaseId = createDualRelease("r2", 2, 100);
        long taskId = pullBootTask("d1", "r3");

        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/boot-tasks"))
                .andExpect(jsonPath("$.tasks[0].status").value("CANCELLED"));
        mockMvc.perform(post("/api/boot-tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("""
                                {"requestId":"r5","result":"SUCCESS","phase":"TRIAL_BOOT",
                                 "targetPartition":"B","targetVersion":"v2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("v1"));
    }

    @Test
    void 普通发布默认NORMAL_回执规则不变() throws Exception {
        registerDevice("r1", "d1");
        MvcResult release = mockMvc.perform(post("/api/releases").contentType("application/json")
                        .content("""
                                {"requestId":"r2","model":"m1","fromVersion":"v1","toVersion":"v2","ratio":100}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseKind").value("NORMAL"))
                .andReturn();
        Number releaseId = com.jayway.jsonpath.JsonPath.read(release.getResponse().getContentAsString(),
                "$.releaseId");

        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.bootTask").doesNotExist())
                .andReturn();
        Number taskId = com.jayway.jsonpath.JsonPath.read(pull.getResponse().getContentAsString(),
                "$.task.taskId");

        // 普通任务 SUCCESS 回执直接终结并切换版本，无需阶段
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("v2"))
                .andExpect(jsonPath("$.activePartition").value("A"));
        // 普通发布无试启动任务
        mockMvc.perform(get("/api/releases/" + releaseId + "/boot-tasks"))
                .andExpect(jsonPath("$.tasks.length()").value(0));
    }
}
