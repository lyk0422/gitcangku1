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
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 双分区试启动 API 测试：59 秒健康确认提交、60 秒到期回滚、试启动字段不可改、
 * 分区/版本校验、失败回滚与重试新任务ID（H2 内存库，MODE=MySQL，可控时钟）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class FirmwareBootPartitionTest {

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

    private void registerDevice(String requestId, String deviceId, String version) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"m1","currentVersion":"%s","bucketNo":1}
                """.formatted(requestId, deviceId, version))).andExpect(status().isOk());
    }

    private long createDualRelease(String requestId, int sampleFloor, int threshold) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"m1","fromVersion":"v1","toVersion":"v2","ratio":100,
                 "sampleFloor":%d,"failureThresholdPercent":%d,"dualPartition":true}
                """.formatted(requestId, sampleFloor, threshold)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseKind").value("DUAL_PARTITION"))
                .andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),
                "$.releaseId");
        return id.longValue();
    }

    private long pullBootTask(String deviceId, String requestId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json")
                        .content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist())
                .andExpect(jsonPath("$.bootTask.status").value("PENDING"))
                .andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),
                "$.bootTask.taskId");
        return id.longValue();
    }

    private ResultActions bootReceipt(long taskId, String requestId, String result, String phase,
                                      String targetPartition, String targetVersion) throws Exception {
        StringBuilder body = new StringBuilder("{\"requestId\":\"").append(requestId)
                .append("\",\"result\":\"").append(result).append("\"");
        if (phase != null) {
            body.append(",\"phase\":\"").append(phase).append("\"");
        }
        if (targetPartition != null) {
            body.append(",\"targetPartition\":\"").append(targetPartition).append("\"");
        }
        if (targetVersion != null) {
            body.append(",\"targetVersion\":\"").append(targetVersion).append("\"");
        }
        body.append("}");
        return mockMvc.perform(post("/api/boot-tasks/" + taskId + "/receipt")
                .contentType("application/json").content(body.toString()));
    }

    private void trialBoot(long taskId, String requestId) throws Exception {
        bootReceipt(taskId, requestId, "SUCCESS", "TRIAL_BOOT", "B", "v2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andExpect(jsonPath("$.targetPartition").value("B"))
                .andExpect(jsonPath("$.targetVersion").value("v2"))
                .andExpect(jsonPath("$.trialStartedAtUtc").isNotEmpty());
    }

    @Test
    void 主流程_试启动59秒健康确认_COMMITTED并切换版本与分区() throws Exception {
        registerDevice("r1", "d1", "v1");
        long releaseId = createDualRelease("r2", 2, 100);
        long taskId = pullBootTask("d1", "r3");

        trialBoot(taskId, "r4");
        // 进入 TRIAL 不更新 currentVersion 与分区
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("v1"))
                .andExpect(jsonPath("$.activePartition").value("A"));

        clock.advance(Duration.ofSeconds(59));
        mockMvc.perform(post("/api/boot-tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"result\":\"SUCCESS\",\"phase\":\"HEALTH_CONFIRM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"));

        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("v2"))
                .andExpect(jsonPath("$.activePartition").value("B"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(1))
                .andExpect(jsonPath("$.roundFailed").value(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM boot_rollback_record", Long.class))
                .isZero();
    }

    @Test
    void 到期60秒_健康确认409_仍为v1_回滚只记录一次() throws Exception {
        registerDevice("r1", "d1", "v1");
        long releaseId = createDualRelease("r2", 2, 100);
        long taskId = pullBootTask("d1", "r3");
        trialBoot(taskId, "r4");

        clock.advance(Duration.ofSeconds(60));
        mockMvc.perform(post("/api/boot-tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"result\":\"SUCCESS\",\"phase\":\"HEALTH_CONFIRM\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOT_TASK_TERMINATED"));

        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("v1"))
                .andExpect(jsonPath("$.activePartition").value("A"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/boot-tasks"))
                .andExpect(jsonPath("$.tasks[0].status").value("ROLLED_BACK"));
        // 只记录一次回滚：再次回执与查询不重复记录
        mockMvc.perform(post("/api/boot-tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r6\",\"result\":\"SUCCESS\",\"phase\":\"HEALTH_CONFIRM\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/releases/" + releaseId + "/boot-tasks"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM boot_rollback_record WHERE task_id = ?", Long.class, taskId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT reason FROM boot_rollback_record WHERE task_id = ?", String.class, taskId))
                .isEqualTo("EXPIRED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(1));
    }

    @Test
    void 试启动字段不可改_旧试启动键重放冻结响应() throws Exception {
        registerDevice("r1", "d1", "v1");
        createDualRelease("r2", 2, 100);
        long taskId = pullBootTask("d1", "r3");

        MvcResult trial = bootReceipt(taskId, "r4", "SUCCESS", "TRIAL_BOOT", "B", "v2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andReturn();
        String startedAt = com.jayway.jsonpath.JsonPath.read(trial.getResponse().getContentAsString(),
                "$.trialStartedAtUtc");

        // 新键同字段重报：冻结的 TRIAL 阶段视图，试启动时刻不变
        bootReceipt(taskId, "r5", "SUCCESS", "TRIAL_BOOT", "B", "v2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andExpect(jsonPath("$.trialStartedAtUtc").value(startedAt));
        // 新键异字段：409
        bootReceipt(taskId, "r6", "SUCCESS", "TRIAL_BOOT", "A", "v2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRIAL_FIELDS_IMMUTABLE"));

        clock.advance(Duration.ofSeconds(10));
        bootReceipt(taskId, "r7", "SUCCESS", "HEALTH_CONFIRM", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"));

        // 提交旧试启动键：重放首次 TRIAL 响应，不返回后来终态 COMMITTED
        MvcResult replay = bootReceipt(taskId, "r4", "SUCCESS", "TRIAL_BOOT", "B", "v2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(trial.getResponse().getContentAsString());
        // 新键在终态上任何回执：409
        bootReceipt(taskId, "r8", "SUCCESS", "TRIAL_BOOT", "B", "v2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOT_TASK_TERMINATED"));
    }

    @Test
    void 只能切换非当前分区_目标版本校验_失败不占键() throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r1","deviceId":"d1","model":"m1","currentVersion":"v1","bucketNo":1,
                 "activePartition":"B"}
                """)).andExpect(status().isOk());
        createDualRelease("r2", 2, 100);
        long taskId = pullBootTask("d1", "r3");

        // 目标分区为当前分区 B：409
        bootReceipt(taskId, "r4", "SUCCESS", "TRIAL_BOOT", "B", "v2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TARGET_PARTITION"));
        // 失败不占键：同 requestId 修正参数后成功（非当前分区为 A）
        bootReceipt(taskId, "r4", "SUCCESS", "TRIAL_BOOT", "A", "v2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andExpect(jsonPath("$.targetPartition").value("A"));

        // 目标版本错误：409
        registerDevice("r5", "d2", "v1");
        long task2 = pullBootTask("d2", "r6");
        bootReceipt(task2, "r7", "SUCCESS", "TRIAL_BOOT", "B", "v9")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TARGET_VERSION"));
        // SUCCESS 缺少 phase：400
        bootReceipt(task2, "r8", "SUCCESS", null, "B", "v2")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PHASE_REQUIRED"));
    }

    @Test
    void FAILED回执回滚_重试产生新任务ID() throws Exception {
        registerDevice("r1", "d1", "v1");
        long releaseId = createDualRelease("r2", 2, 100);
        long task1 = pullBootTask("d1", "r3");

        // PENDING 直接 FAILED：回滚
        bootReceipt(task1, "r4", "FAILED", null, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("v1"))
                .andExpect(jsonPath("$.activePartition").value("A"));

        // 重试产生新任务ID，尝试序号递增
        MvcResult pull2 = mockMvc.perform(post("/api/devices/d1/pull")
                        .contentType("application/json").content("{\"requestId\":\"r5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bootTask.status").value("PENDING"))
                .andExpect(jsonPath("$.bootTask.attemptNo").value(2))
                .andReturn();
        Number id2 = com.jayway.jsonpath.JsonPath.read(pull2.getResponse().getContentAsString(),
                "$.bootTask.taskId");
        long task2 = id2.longValue();
        assertThat(task2).isNotEqualTo(task1);

        // TRIAL 中 FAILED：回滚，统计只计失败
        trialBoot(task2, "r6");
        bootReceipt(task2, "r7", "FAILED", null, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM boot_rollback_record WHERE release_id = ? AND reason = 'FAILED'",
                Long.class, releaseId)).isEqualTo(2);
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(2));
    }
}
