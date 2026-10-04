package com.example.starter.firmware;

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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 双分区试启动发布 API 测试（H2 内存库，MODE=MySQL，可控服务端时钟）：
 * 59 秒健康确认提交、60 秒到期回滚且只记一次、FAILED 回滚、试启动字段不可改、
 * 非当前分区校验、响应冻结、暂停/取消行为、查询结算到期任务。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareDualBootTest {

    @TestConfiguration
    static class TestClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-10-04T00:00:00Z"));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM dual_boot_task");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void registerDevice(String requestId, String deviceId, String partition) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                "activePartition":"%s"}
                """.formatted(requestId, deviceId, partition)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activePartition").value(partition));
    }

    private long createDualBootRelease(String requestId, int sampleFloor, int threshold) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/dual-boot/releases")
                        .contentType("application/json").content("""
                        {"requestId":"%s","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                        "sampleFloor":%d,"failureThresholdPercent":%d}
                        """.formatted(requestId, sampleFloor, threshold)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("DUAL_BOOT"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private long pullTask(String requestId, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist())
                .andExpect(jsonPath("$.dualBootTask.status").value("PENDING"))
                .andExpect(jsonPath("$.dualBootTask.fromVersion").value("1.0.0"))
                .andExpect(jsonPath("$.dualBootTask.toVersion").value("2.0.0"))
                .andReturn();
        return idOf(result, "$.dualBootTask.taskId");
    }

    private void trialBoot(String requestId, long taskId, String partition, String version) throws Exception {
        mockMvc.perform(post("/api/dual-boot/tasks/" + taskId + "/receipt")
                        .contentType("application/json").content("""
                        {"requestId":"%s","action":"TRIAL_BOOT","targetPartition":"%s","targetVersion":"%s"}
                        """.formatted(requestId, partition, version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andExpect(jsonPath("$.targetPartition").value(partition))
                .andExpect(jsonPath("$.targetVersion").value(version))
                .andExpect(jsonPath("$.trialStartedAtUtc").exists())
                .andExpect(jsonPath("$.trialDeadlineUtc").exists());
    }

    private void receipt(String requestId, long taskId, String action) throws Exception {
        mockMvc.perform(post("/api/dual-boot/tasks/" + taskId + "/receipt")
                        .contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"action\":\"%s\"}".formatted(requestId, action)))
                .andExpect(status().isOk());
    }

    private void expectDevice(String deviceId, String version, String partition) throws Exception {
        mockMvc.perform(get("/api/devices/" + deviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(version))
                .andExpect(jsonPath("$.activePartition").value(partition));
    }

    @Test
    void 主流程_试启动不切换版本_59秒健康确认提交并切换版本与分区() throws Exception {
        registerDevice("r1", "d1", "A");
        long releaseId = createDualBootRelease("r2", 2, 100);
        long taskId = pullTask("r3", "d1");

        trialBoot("r4", taskId, "B", "2.0.0");
        // 进入 TRIAL 不更新 currentVersion 与分区
        expectDevice("d1", "1.0.0", "A");

        clock.advanceSeconds(59);
        mockMvc.perform(post("/api/dual-boot/tasks/" + taskId + "/receipt")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"action\":\"HEALTHY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"));

        expectDevice("d1", "2.0.0", "B");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(1))
                .andExpect(jsonPath("$.roundFailed").value(0));

        // 重复健康确认返回当前视图，不重复计数
        receipt("r6", taskId, "HEALTHY");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(1));
    }
    @Test
    void 到期60秒_健康确认409_仍为旧版本_回滚只记一次_重试产生新任务ID() throws Exception {
        registerDevice("r1", "d1", "A");
        long releaseId = createDualBootRelease("r2", 2, 100);
        long taskId = pullTask("r3", "d1");
        trialBoot("r4", taskId, "B", "2.0.0");

        clock.advanceSeconds(60);
        mockMvc.perform(post("/api/dual-boot/tasks/" + taskId + "/receipt")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"action\":\"HEALTHY\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HEALTH_CONFIRM_EXPIRED"));

        // 回滚保留旧分区与旧版本
        expectDevice("d1", "1.0.0", "A");
        mockMvc.perform(get("/api/dual-boot/tasks/" + taskId))
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(1));

        // 再次健康确认仍 409，回滚与失败统计只记一次
        mockMvc.perform(post("/api/dual-boot/tasks/" + taskId + "/receipt")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r6\",\"action\":\"HEALTHY\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_ROLLED_BACK"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(1));

        // 重试拉取产生新任务ID
        long retryTaskId = pullTask("r7", "d1");
        assertThat(retryTaskId).isNotEqualTo(taskId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM dual_boot_task WHERE release_id = ? AND device_id = 'd1'",
                Long.class, releaseId)).isEqualTo(2);
    }

    @Test
    void FAILED回执回滚_不计升级成功_设备版本分区不变() throws Exception {
        registerDevice("r1", "d1", "A");
        long releaseId = createDualBootRelease("r2", 2, 100);
        long taskId = pullTask("r3", "d1");
        trialBoot("r4", taskId, "B", "2.0.0");

        mockMvc.perform(post("/api/dual-boot/tasks/" + taskId + "/receipt")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"action\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));

        expectDevice("d1", "1.0.0", "A");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(1));

        // 重复 FAILED 返回当前视图，不重复计数
        receipt("r6", taskId, "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(1));
    }

    @Test
    void 试启动校验_只能切换非当前分区_目标版本匹配_字段不可改_失败不占键() throws Exception {
        registerDevice("r1", "d1", "A");
        createDualBootRelease("r2", 2, 100);
        long taskId = pullTask("r3", "d1");

        // 只能切换非当前分区
        mockMvc.perform(post("/api/dual-boot/tasks/" + taskId + "/receipt")
                        .contentType("application/json").content("""
                        {"requestId":"r4","action":"TRIAL_BOOT","targetPartition":"A","targetVersion":"2.0.0"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TARGET_PARTITION"));

        // 目标版本必须等于发布单目标版本；失败不占键，同 requestId 改正后成功
        mockMvc.perform(post("/api/dual-boot/tasks/" + taskId + "/receipt")
                        .contentType("application/json").content("""
                        {"requestId":"r5","action":"TRIAL_BOOT","targetPartition":"B","targetVersion":"9.9.9"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TARGET_VERSION_MISMATCH"));

        // 缺少试启动字段：400
        mockMvc.perform(post("/api/dual-boot/tasks/" + taskId + "/receipt")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r6\",\"action\":\"TRIAL_BOOT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_TRIAL_FIELDS"));
        trialBoot("r5", taskId, "B", "2.0.0");


        // 同参重复试启动回执（新 requestId）返回当前 TRIAL 视图
        trialBoot("r7", taskId, "B", "2.0.0");

        // 异参试启动回执：字段不可改
        mockMvc.perform(post("/api/dual-boot/tasks/" + taskId + "/receipt")
                        .contentType("application/json").content("""
                        {"requestId":"r8","action":"TRIAL_BOOT","targetPartition":"B","targetVersion":"3.0.0"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRIAL_FIELDS_CONFLICT"));

        // 重复拉取返回同一个 TRIAL 任务，不产生新任务
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r9\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dualBootTask.taskId").value(taskId))
                .andExpect(jsonPath("$.dualBootTask.status").value("TRIAL"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dual_boot_task", Long.class)).isEqualTo(1);
    }
    @Test
    void 响应冻结_旧试启动键重放返回TRIAL而非后来终态_异参409() throws Exception {
        registerDevice("r1", "d1", "A");
        createDualBootRelease("r2", 2, 100);
        long taskId = pullTask("r3", "d1");

        MvcResult trial = mockMvc.perform(post("/api/dual-boot/tasks/" + taskId + "/receipt")
                        .contentType("application/json").content("""
                        {"requestId":"r4","action":"TRIAL_BOOT","targetPartition":"B","targetVersion":"2.0.0"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andReturn();

        clock.advanceSeconds(10);
        receipt("r5", taskId, "HEALTHY");
        mockMvc.perform(get("/api/dual-boot/tasks/" + taskId))
                .andExpect(jsonPath("$.status").value("COMMITTED"));

        // 提交旧试启动键：返回冻结的首次完整响应（TRIAL），不返回后来终态
        mockMvc.perform(post("/api/dual-boot/tasks/" + taskId + "/receipt")
                        .contentType("application/json").content("""
                        {"requestId":"r4","action":"TRIAL_BOOT","targetPartition":"B","targetVersion":"2.0.0"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(trial.getResponse().getContentAsString()));

        // 同键异参：409
        mockMvc.perform(post("/api/dual-boot/tasks/" + taskId + "/receipt")
                        .contentType("application/json").content("""
                        {"requestId":"r4","action":"TRIAL_BOOT","targetPartition":"B","targetVersion":"3.0.0"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void 暂停禁止新试启动_已有TRIAL健康确认仍受理() throws Exception {
        registerDevice("r1", "d1", "A");
        registerDevice("r2", "d2", "A");
        registerDevice("r3", "d3", "A");
        registerDevice("r4", "d4", "A");
        long releaseId = createDualBootRelease("r5", 2, 50);
        long t1 = pullTask("r6", "d1");
        long t2 = pullTask("r7", "d2");
        long t3 = pullTask("r8", "d3");
        long t4 = pullTask("r9", "d4");
        trialBoot("r10", t1, "B", "2.0.0");
        trialBoot("r11", t2, "B", "2.0.0");
        trialBoot("r12", t4, "B", "2.0.0");

        // 两笔失败触发自动暂停
        receipt("r13", t1, "FAILED");
        receipt("r14", t2, "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"));

        // 暂停禁止新试启动
        mockMvc.perform(post("/api/dual-boot/tasks/" + t3 + "/receipt")
                        .contentType("application/json").content("""
                        {"requestId":"r15","action":"TRIAL_BOOT","targetPartition":"B","targetVersion":"2.0.0"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_ACTIVE"));

        // 暂停不撤销已有 TRIAL 的健康确认
        receipt("r16", t4, "HEALTHY");
        mockMvc.perform(get("/api/dual-boot/tasks/" + t4))
                .andExpect(jsonPath("$.status").value("COMMITTED"));
        expectDevice("d4", "2.0.0", "B");

        // 暂停后不投放新任务
        registerDevice("r17", "d5", "A");
        mockMvc.perform(post("/api/devices/d5/pull").contentType("application/json")
                        .content("{\"requestId\":\"r18\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dualBootTask").doesNotExist());
    }

    @Test
    void 取消_TRIAL任务转CANCELLED_健康确认409_设备不变() throws Exception {
        registerDevice("r1", "d1", "A");
        long releaseId = createDualBootRelease("r2", 2, 100);
        long taskId = pullTask("r3", "d1");
        trialBoot("r4", taskId, "B", "2.0.0");

        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel")
                        .contentType("application/json").content("{\"requestId\":\"r5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/dual-boot/tasks/" + taskId))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/api/dual-boot/tasks/" + taskId + "/receipt")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r6\",\"action\":\"HEALTHY\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
        expectDevice("d1", "1.0.0", "A");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0));
    }

    @Test
    void 查询结算到期任务_不依赖后台线程() throws Exception {
        registerDevice("r1", "d1", "A");
        long releaseId = createDualBootRelease("r2", 2, 100);
        long taskId = pullTask("r3", "d1");
        trialBoot("r4", taskId, "B", "2.0.0");

        clock.advanceSeconds(61);
        // 单任务查询触发结算
        mockMvc.perform(get("/api/dual-boot/tasks/" + taskId))
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));
        expectDevice("d1", "1.0.0", "A");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(1));

        // 列表查询同样返回已结算状态，重复查询不重复计数
        mockMvc.perform(get("/api/dual-boot/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].status").value("ROLLED_BACK"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(1));

        // 未知状态过滤：400；发布单不存在：404
        mockMvc.perform(get("/api/dual-boot/releases/" + releaseId + "/tasks").param("status", "BOGUS"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/dual-boot/releases/9999/tasks"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/dual-boot/tasks/9999"))
                .andExpect(status().isNotFound());
    }
}