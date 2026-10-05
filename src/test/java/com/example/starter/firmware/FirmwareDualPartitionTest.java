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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 双分区试启动 API 测试（H2 内存库，MODE=MySQL，可控时钟）：
 * 试启动进入 TRIAL 不切换版本；60 秒内健康确认才 COMMITTED 并切换分区与版本；
 * 达到 60 秒或 FAILED 即回滚（保留旧分区旧版本），重试产生新任务ID。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class FirmwareDualPartitionTest {

    private static final Instant T0 = Instant.parse("2026-10-06T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM rollout_rollback_record");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        clock.setInstant(T0);
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void registerDevice(String requestId, String deviceId, String model, int bucket) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"v1","bucketNo":%d}
                """.formatted(requestId, deviceId, model, bucket)))
                .andExpect(status().isOk());
    }

    private long createDualRelease(String requestId, String model, int sampleFloor, int threshold)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/dual-releases").contentType("application/json").content("""
                {"requestId":"%s","operator":"tester","model":"%s","fromVersion":"v1","toVersion":"v2",
                "ratio":100,"sampleFloor":%d,"failureThresholdPercent":%d}
                """.formatted(requestId, model, sampleFloor, threshold)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("DUAL_PARTITION"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private long pullTask(String requestId, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        return idOf(result, "$.task.taskId");
    }

    private void trial(String requestId, long taskId) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"%s","result":"SUCCESS","targetPartition":"B","targetVersion":"v2"}
                        """.formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andExpect(jsonPath("$.targetPartition").value("B"))
                .andExpect(jsonPath("$.targetVersion").value("v2"));
    }

    private void receipt(String requestId, long taskId, String result) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"result\":\"%s\"}".formatted(requestId, result)))
                .andExpect(status().isOk());
    }

    @Test
    void 主流程_试启动后59秒健康确认_COMMITTED并切换分区与版本() throws Exception {
        registerDevice("r-dev", "d1", "m1", 1);
        long releaseId = createDualRelease("r-rel", "m1", 2, 100);
        long taskId = pullTask("r-pull", "d1");

        // 试启动：记录目标分区、目标版本与服务端 UTC 试启动时刻，不更新 currentVersion
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r-trial","result":"SUCCESS","targetPartition":"B","targetVersion":"v2"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andExpect(jsonPath("$.targetPartition").value("B"))
                .andExpect(jsonPath("$.targetVersion").value("v2"))
                .andExpect(jsonPath("$.trialStartedAtUtc").value("2026-10-06T00:00:00Z"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("v1"))
                .andExpect(jsonPath("$.activePartition").value("A"));

        // 59 秒时健康确认：COMMITTED 并切换分区与版本
        clock.setInstant(T0.plusSeconds(59));
        receipt("r-health", taskId, "SUCCESS");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("v2"))
                .andExpect(jsonPath("$.activePartition").value("B"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(1))
                .andExpect(jsonPath("$.roundFailed").value(0));
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks[0].status").value("COMMITTED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_rollback_record", Long.class)).isZero();
    }
    @Test
    void 到期60秒健康确认409_仍为v1_回滚只记录一次_重试产生新任务ID() throws Exception {
        registerDevice("r-dev", "d1", "m1", 1);
        long releaseId = createDualRelease("r-rel", "m1", 2, 100);
        long taskId = pullTask("r-pull", "d1");
        trial("r-trial", taskId);

        // 达到 60 秒：健康确认 409，任务回滚，设备保留旧分区旧版本
        clock.setInstant(T0.plusSeconds(60));
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-health\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_ROLLED_BACK"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("v1"))
                .andExpect(jsonPath("$.activePartition").value("A"));

        // 查询与监控结算到期任务，回滚只记录一次、失败统计只计一次
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks[0].status").value("ROLLED_BACK"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.roundSuccess").value(0));
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_rollback_record WHERE task_id = ?", Long.class, taskId))
                .isEqualTo(1);

        // 回滚不计为升级成功；重试产生新任务ID，尝试序号递增
        MvcResult retry = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.attemptNo").value(2))
                .andReturn();
        long newTaskId = idOf(retry, "$.task.taskId");
        assertThat(newTaskId).isNotEqualTo(taskId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_rollback_record", Long.class))
                .isEqualTo(1);
    }

    @Test
    void FAILED回执即回滚_不计成功_重复回执不重复计数() throws Exception {
        registerDevice("r-dev", "d1", "m1", 1);
        long releaseId = createDualRelease("r-rel", "m1", 2, 100);
        long taskId = pullTask("r-pull", "d1");
        trial("r-trial", taskId);

        // TRIAL 中 FAILED：回滚，保留旧分区旧版本
        clock.setInstant(T0.plusSeconds(10));
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-f1\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("v1"))
                .andExpect(jsonPath("$.activePartition").value("A"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.roundSuccess").value(0));

        // 同结果重复回执（新 requestId）：成功但不重复计数、不重复记录回滚
        receipt("r-f1-dup", taskId, "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(1));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_rollback_record WHERE task_id = ?", Long.class, taskId))
                .isEqualTo(1);

        // 改结果：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-s1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_ROLLED_BACK"));

        // PENDING 时 FAILED 同样回滚：重试新任务后直接失败
        long task2 = pullTask("r-pull2", "d1");
        assertThat(task2).isNotEqualTo(taskId);
        receipt("r-f2", task2, "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(2))
                .andExpect(jsonPath("$.roundSuccess").value(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_rollback_record", Long.class))
                .isEqualTo(2);
    }
    @Test
    void 试启动校验_缺字段400_当前分区409_版本不匹配409_试启动字段不可改() throws Exception {
        registerDevice("r-dev", "d1", "m1", 1);
        createDualRelease("r-rel", "m1", 2, 100);
        long taskId = pullTask("r-pull", "d1");

        // 缺少试启动字段：400
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-t0\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TRIAL_FIELDS_REQUIRED"));
        // 目标分区为当前分区：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r-t1","result":"SUCCESS","targetPartition":"A","targetVersion":"v2"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TARGET_PARTITION_CURRENT"));
        // 目标版本与发布单不一致：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r-t2","result":"SUCCESS","targetPartition":"B","targetVersion":"v3"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TARGET_VERSION_MISMATCH"));

        // 失败不占键：被拒绝的 requestId 修正参数后可成功；任务仍未离开 PENDING
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("v1"))
                .andExpect(jsonPath("$.activePartition").value("A"));
        trial("r-t1", taskId);

        // 健康确认携带不同试启动字段：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r-h1","result":"SUCCESS","targetPartition":"A","targetVersion":"v2"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRIAL_FIELDS_IMMUTABLE"));
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-h2\",\"result\":\"SUCCESS\",\"targetVersion\":\"v3\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRIAL_FIELDS_IMMUTABLE"));

        // 字段一致的健康确认：200 COMMITTED
        clock.setInstant(T0.plusSeconds(30));
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r-h3","result":"SUCCESS","targetPartition":"B","targetVersion":"v2"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("v2"))
                .andExpect(jsonPath("$.activePartition").value("B"));
    }

    @Test
    void 发布暂停_禁止新试启动_但不撤销已有TRIAL的健康确认() throws Exception {
        long releaseId = createDualRelease("r-rel", "m1", 2, 100);
        for (int i = 1; i <= 5; i++) {
            registerDevice("r-dev" + i, "d" + i, "m1", i);
        }
        // 暂停前已存在的 PENDING 与 TRIAL 任务
        long t3 = pullTask("r-pull3", "d3");
        long t4 = pullTask("r-pull4", "d4");
        trial("r-trial4", t4);
        // 两次失败使失败率达到阈值，发布单自动暂停
        long t1 = pullTask("r-pull1", "d1");
        trial("r-trial1", t1);
        receipt("r-f1", t1, "FAILED");
        long t2 = pullTask("r-pull2", "d2");
        receipt("r-f2", t2, "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.roundFailed").value(2));

        // 暂停不为新设备创建任务
        mockMvc.perform(post("/api/devices/d5/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());
        // 暂停禁止新试启动
        mockMvc.perform(post("/api/tasks/" + t3 + "/receipt").contentType("application/json").content("""
                        {"requestId":"r-trial3","result":"SUCCESS","targetPartition":"B","targetVersion":"v2"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_ACTIVE"));
        // 已有 TRIAL 的健康确认仍受理
        clock.setInstant(T0.plusSeconds(20));
        receipt("r-health4", t4, "SUCCESS");
        mockMvc.perform(get("/api/devices/d4"))
                .andExpect(jsonPath("$.currentVersion").value("v2"))
                .andExpect(jsonPath("$.activePartition").value("B"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(1));
    }
    @Test
    void 幂等_试启动响应冻结_旧键重放不返回后来终态_异参409() throws Exception {
        registerDevice("r-dev", "d1", "m1", 1);
        createDualRelease("r-rel", "m1", 2, 100);
        long taskId = pullTask("r-pull", "d1");

        String trialBody = """
                {"requestId":"r-trial","result":"SUCCESS","targetPartition":"B","targetVersion":"v2"}
                """;
        MvcResult trialResponse = mockMvc.perform(post("/api/tasks/" + taskId + "/receipt")
                        .contentType("application/json").content(trialBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andReturn();

        clock.setInstant(T0.plusSeconds(30));
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-health\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"));

        // 提交旧试启动键：返回冻结的 TRIAL 响应，不返回后来的 COMMITTED 终态
        MvcResult replay = mockMvc.perform(post("/api/tasks/" + taskId + "/receipt")
                        .contentType("application/json").content(trialBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(trialResponse.getResponse().getContentAsString());

        // 同键异参：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r-trial","result":"SUCCESS","targetPartition":"B","targetVersion":"v9"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 健康确认键重放：冻结的 COMMITTED 响应
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-health\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"));
    }

    @Test
    void 创建双分区发布_操作者入指纹_同键重放_异参409_普通发布规则不变() throws Exception {
        MvcResult first = mockMvc.perform(post("/api/dual-releases").contentType("application/json").content("""
                        {"requestId":"r-c","operator":"alice","model":"m1","fromVersion":"v1","toVersion":"v2","ratio":50}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("DUAL_PARTITION"))
                .andExpect(jsonPath("$.sampleFloor").value(2))
                .andExpect(jsonPath("$.failureThresholdPercent").value(100))
                .andReturn();
        // 同键同参重放：响应一致，不重复创建
        MvcResult replay = mockMvc.perform(post("/api/dual-releases").contentType("application/json").content("""
                        {"requestId":"r-c","operator":"alice","model":"m1","fromVersion":"v1","toVersion":"v2","ratio":50}
                        """))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order", Long.class)).isEqualTo(1);
        // 同键异参（操作者不同）：409
        mockMvc.perform(post("/api/dual-releases").contentType("application/json").content("""
                        {"requestId":"r-c","operator":"bob","model":"m1","fromVersion":"v1","toVersion":"v2","ratio":50}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        // 同型号第二张未终结发布单：409
        mockMvc.perform(post("/api/dual-releases").contentType("application/json").content("""
                        {"requestId":"r-c2","operator":"alice","model":"m1","fromVersion":"v1","toVersion":"v3","ratio":50}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_RELEASE_EXISTS"));
        // 目标与来源相同：400
        mockMvc.perform(post("/api/dual-releases").contentType("application/json").content("""
                        {"requestId":"r-c3","operator":"alice","model":"m9","fromVersion":"v1","toVersion":"v1","ratio":50}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_VERSION"));

        // 普通灰度发布回执规则不变：SUCCESS 立即切换版本，无试启动阶段
        registerDevice("r-dev9", "d9", "m2", 1);
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r-g","model":"m2","fromVersion":"v1","toVersion":"v2","ratio":100}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("GRAY"));
        long grayTask = pullTask("r-p9", "d9");
        mockMvc.perform(post("/api/tasks/" + grayTask + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-r9\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.kind").value("GRAY"));
        mockMvc.perform(get("/api/devices/d9"))
                .andExpect(jsonPath("$.currentVersion").value("v2"));
    }
}