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

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 双分区试启动 API 测试（H2 内存库，MODE=MySQL，可控时钟）：
 * 59秒确认 COMMITTED、60秒到期回滚、失败回执、字段冻结、暂停语义、幂等重放与结构化指纹。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareDualPartitionApiTest {

    private static final Instant T0 = Instant.parse("2026-10-06T00:00:00Z");

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
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
        jdbc.update("DELETE FROM dual_task");
        jdbc.update("DELETE FROM dual_release");
        jdbc.update("DELETE FROM device_boot_state");
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

    private void registerDevice(String requestId, String deviceId, String model, String version) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":1}
                """.formatted(requestId, deviceId, model, version)))
                .andExpect(status().isOk());
    }

    private long createDualRelease(String requestId, String model, String targetVersion) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/dual-releases").contentType("application/json").content("""
                {"requestId":"%s","operator":"op-1","model":"%s","targetVersion":"%s"}
                """.formatted(requestId, model, targetVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private long dualPull(String requestId, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/dual-pull")
                        .contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"operator\":\"op-1\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        return idOf(result, "$.task.taskId");
    }

    private void trial(long taskId, String requestId, String partition, String version) throws Exception {
        mockMvc.perform(post("/api/dual-tasks/" + taskId + "/trial").contentType("application/json").content("""
                {"requestId":"%s","operator":"op-1","targetPartition":"%s","targetVersion":"%s"}
                """.formatted(requestId, partition, version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"));
    }

    @Test
    void 主流程_试启动后59秒健康确认_COMMITTED并切换版本与分区() throws Exception {
        registerDevice("r-dev", "d1", "m1", "v1");
        long releaseId = createDualRelease("r-rel", "m1", "v2");
        long taskId = dualPull("r-pull", "d1");

        mockMvc.perform(get("/api/devices/d1/boot"))
                .andExpect(jsonPath("$.currentPartition").value("A"));

        mockMvc.perform(post("/api/dual-tasks/" + taskId + "/trial").contentType("application/json").content("""
                {"requestId":"r-trial","operator":"op-1","targetPartition":"B","targetVersion":"v2"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andExpect(jsonPath("$.targetPartition").value("B"))
                .andExpect(jsonPath("$.targetVersion").value("v2"))
                .andExpect(jsonPath("$.trialStartedAtUtc").value("2026-10-06T00:00:00Z"));

        // 进入 TRIAL 不更新 currentVersion，分区不翻转
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("v1"));
        mockMvc.perform(get("/api/devices/d1/boot"))
                .andExpect(jsonPath("$.currentPartition").value("A"));

        clock.advance(Duration.ofSeconds(59));
        mockMvc.perform(post("/api/dual-tasks/" + taskId + "/confirm").contentType("application/json")
                        .content("{\"requestId\":\"r-confirm\",\"operator\":\"op-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.settledAtUtc").value("2026-10-06T00:00:59Z"));

        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("v2"));
        mockMvc.perform(get("/api/devices/d1/boot"))
                .andExpect(jsonPath("$.currentPartition").value("B"));
        mockMvc.perform(get("/api/dual-releases/" + releaseId))
                .andExpect(jsonPath("$.committedCount").value(1))
                .andExpect(jsonPath("$.rolledBackCount").value(0));
    }

    @Test
    void 到期60秒确认409_仍为v1_回滚只记录一次_重试产生新任务ID() throws Exception {
        registerDevice("r-dev", "d1", "m1", "v1");
        long releaseId = createDualRelease("r-rel", "m1", "v2");
        long taskId = dualPull("r-pull", "d1");
        trial(taskId, "r-trial", "B", "v2");

        clock.advance(Duration.ofSeconds(60));
        mockMvc.perform(post("/api/dual-tasks/" + taskId + "/confirm").contentType("application/json")
                        .content("{\"requestId\":\"r-confirm\",\"operator\":\"op-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_ROLLED_BACK"));

        mockMvc.perform(get("/api/dual-tasks/" + taskId))
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"))
                .andExpect(jsonPath("$.rollbackReason").value("EXPIRED"))
                .andExpect(jsonPath("$.settledAtUtc").value("2026-10-06T00:01:00Z"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("v1"));
        mockMvc.perform(get("/api/devices/d1/boot"))
                .andExpect(jsonPath("$.currentPartition").value("A"));
        mockMvc.perform(get("/api/dual-releases/" + releaseId))
                .andExpect(jsonPath("$.committedCount").value(0))
                .andExpect(jsonPath("$.rolledBackCount").value(1));

        // 重复确认与重复查询：回滚不重复记录
        mockMvc.perform(post("/api/dual-tasks/" + taskId + "/confirm").contentType("application/json")
                        .content("{\"requestId\":\"r-confirm2\",\"operator\":\"op-1\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/dual-tasks/" + taskId))
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));
        mockMvc.perform(get("/api/dual-releases/" + releaseId))
                .andExpect(jsonPath("$.rolledBackCount").value(1));

        // 回滚后重试：产生新任务ID，可重新试启动
        long retryTaskId = dualPull("r-pull2", "d1");
        assertThat(retryTaskId).isNotEqualTo(taskId);
        trial(retryTaskId, "r-trial2", "B", "v2");
    }

    @Test
    void 查询惰性结算到期任务_不依赖后台线程() throws Exception {
        registerDevice("r-dev", "d1", "m1", "v1");
        long releaseId = createDualRelease("r-rel", "m1", "v2");
        long taskId = dualPull("r-pull", "d1");
        trial(taskId, "r-trial", "B", "v2");

        clock.advance(Duration.ofSeconds(61));
        // 纯查询触发结算
        mockMvc.perform(get("/api/dual-tasks/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"))
                .andExpect(jsonPath("$.rollbackReason").value("EXPIRED"));
        mockMvc.perform(get("/api/dual-releases/" + releaseId))
                .andExpect(jsonPath("$.rolledBackCount").value(1));
        mockMvc.perform(get("/api/dual-releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].status").value("ROLLED_BACK"));
        // 结算后确认仍 409，计数不变
        mockMvc.perform(post("/api/dual-tasks/" + taskId + "/confirm").contentType("application/json")
                        .content("{\"requestId\":\"r-confirm\",\"operator\":\"op-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_ROLLED_BACK"));
        mockMvc.perform(get("/api/dual-releases/" + releaseId))
                .andExpect(jsonPath("$.rolledBackCount").value(1));
    }

    @Test
    void 失败回执_回滚保留旧分区旧版本_不计升级成功() throws Exception {
        registerDevice("r-dev1", "d1", "m1", "v1");
        registerDevice("r-dev2", "d2", "m1", "v1");
        registerDevice("r-dev3", "d3", "m1", "v1");
        long releaseId = createDualRelease("r-rel", "m1", "v2");

        // PENDING 直接失败：回滚
        long t1 = dualPull("r-pull1", "d1");
        mockMvc.perform(post("/api/dual-tasks/" + t1 + "/fail").contentType("application/json")
                        .content("{\"requestId\":\"r-fail1\",\"operator\":\"op-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"))
                .andExpect(jsonPath("$.rollbackReason").value("FAILED"));

        // TRIAL 失败：回滚，版本与分区不变
        long t2 = dualPull("r-pull2", "d2");
        trial(t2, "r-trial2", "B", "v2");
        mockMvc.perform(post("/api/dual-tasks/" + t2 + "/fail").contentType("application/json")
                        .content("{\"requestId\":\"r-fail2\",\"operator\":\"op-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));
        mockMvc.perform(get("/api/devices/d2"))
                .andExpect(jsonPath("$.currentVersion").value("v1"));
        mockMvc.perform(get("/api/devices/d2/boot"))
                .andExpect(jsonPath("$.currentPartition").value("A"));

        // 失败后确认 409；重复失败回执返回同一终态且统计不重复
        mockMvc.perform(post("/api/dual-tasks/" + t2 + "/confirm").contentType("application/json")
                        .content("{\"requestId\":\"r-confirm2\",\"operator\":\"op-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_ROLLED_BACK"));
        mockMvc.perform(post("/api/dual-tasks/" + t2 + "/fail").contentType("application/json")
                        .content("{\"requestId\":\"r-fail2b\",\"operator\":\"op-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));
        mockMvc.perform(get("/api/dual-releases/" + releaseId))
                .andExpect(jsonPath("$.rolledBackCount").value(2))
                .andExpect(jsonPath("$.committedCount").value(0));

        // 已 COMMITTED 任务拒绝失败回执
        long t3 = dualPull("r-pull3", "d3");
        trial(t3, "r-trial3", "B", "v2");
        mockMvc.perform(post("/api/dual-tasks/" + t3 + "/confirm").contentType("application/json")
                        .content("{\"requestId\":\"r-confirm3\",\"operator\":\"op-1\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/dual-tasks/" + t3 + "/fail").contentType("application/json")
                        .content("{\"requestId\":\"r-fail3\",\"operator\":\"op-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_ALREADY_COMMITTED"));
        mockMvc.perform(get("/api/dual-releases/" + releaseId))
                .andExpect(jsonPath("$.committedCount").value(1))
                .andExpect(jsonPath("$.rolledBackCount").value(2));
    }

    @Test
    void 试启动字段_只能切换非当前分区_字段冻结不可改() throws Exception {
        registerDevice("r-dev1", "d1", "m1", "v1");
        registerDevice("r-dev2", "d2", "m1", "v1");
        createDualRelease("r-rel", "m1", "v2");
        long taskId = dualPull("r-pull", "d1");

        // 目标版本与发布单不一致：409
        mockMvc.perform(post("/api/dual-tasks/" + taskId + "/trial").contentType("application/json").content("""
                {"requestId":"r-t1","operator":"op-1","targetPartition":"B","targetVersion":"v9"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRIAL_FIELD_MISMATCH"));
        // 当前分区 A，只能试启动 B：409
        mockMvc.perform(post("/api/dual-tasks/" + taskId + "/trial").contentType("application/json").content("""
                {"requestId":"r-t2","operator":"op-1","targetPartition":"A","targetVersion":"v2"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRIAL_PARTITION_MISMATCH"));

        trial(taskId, "r-t3", "B", "v2");
        // 同任务试启动字段不可改：409
        mockMvc.perform(post("/api/dual-tasks/" + taskId + "/trial").contentType("application/json").content("""
                {"requestId":"r-t4","operator":"op-1","targetPartition":"A","targetVersion":"v2"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRIAL_FIELD_IMMUTABLE"));
        // 同字段重复上报（新 requestId）：返回当前 TRIAL
        mockMvc.perform(post("/api/dual-tasks/" + taskId + "/trial").contentType("application/json").content("""
                {"requestId":"r-t5","operator":"op-1","targetPartition":"B","targetVersion":"v2"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"));

        // PENDING 任务不能健康确认；不存在任务 404
        long t2 = dualPull("r-pull2", "d2");
        mockMvc.perform(post("/api/dual-tasks/" + t2 + "/confirm").contentType("application/json")
                        .content("{\"requestId\":\"r-c2\",\"operator\":\"op-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_TRIAL"));
        mockMvc.perform(post("/api/dual-tasks/9999/trial").contentType("application/json").content("""
                {"requestId":"r-t9","operator":"op-1","targetPartition":"B","targetVersion":"v2"}
                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/dual-tasks/9999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 暂停_禁止新试启动但不撤销已有TRIAL的健康确认() throws Exception {
        registerDevice("r-dev1", "d1", "m1", "v1");
        registerDevice("r-dev2", "d2", "m1", "v1");
        registerDevice("r-dev3", "d3", "m1", "v1");
        long releaseId = createDualRelease("r-rel", "m1", "v2");
        long t1 = dualPull("r-pull1", "d1");
        long t2 = dualPull("r-pull2", "d2");
        trial(t1, "r-trial1", "B", "v2");

        mockMvc.perform(post("/api/dual-releases/" + releaseId + "/pause").contentType("application/json")
                        .content("{\"requestId\":\"r-pause\",\"operator\":\"op-1\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.version").value(2));

        // 暂停禁止新试启动
        mockMvc.perform(post("/api/dual-tasks/" + t2 + "/trial").contentType("application/json").content("""
                {"requestId":"r-trial2","operator":"op-1","targetPartition":"B","targetVersion":"v2"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_PAUSED"));
        // 暂停不为新设备创建任务
        mockMvc.perform(post("/api/devices/d3/dual-pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull3\",\"operator\":\"op-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());
        // 已有 TRIAL 的健康确认不撤销
        mockMvc.perform(post("/api/dual-tasks/" + t1 + "/confirm").contentType("application/json")
                        .content("{\"requestId\":\"r-confirm1\",\"operator\":\"op-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"));

        // 重复暂停 409；期望版本不匹配 409；不存在 404
        mockMvc.perform(post("/api/dual-releases/" + releaseId + "/pause").contentType("application/json")
                        .content("{\"requestId\":\"r-pause2\",\"operator\":\"op-1\",\"expectedVersion\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_ACTIVE"));
        long other = createDualRelease("r-rel2", "m2", "v3");
        mockMvc.perform(post("/api/dual-releases/" + other + "/pause").contentType("application/json")
                        .content("{\"requestId\":\"r-pause3\",\"operator\":\"op-1\",\"expectedVersion\":99}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mockMvc.perform(post("/api/dual-releases/9999/pause").contentType("application/json")
                        .content("{\"requestId\":\"r-pause4\",\"operator\":\"op-1\",\"expectedVersion\":1}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/dual-releases/9999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 幂等_阶段响应冻结_同键重放异参409_失败不占键_结构化指纹() throws Exception {
        registerDevice("r-dev1", "d1", "m1", "v1");
        registerDevice("r-dev2", "d2", "m1", "v1");
        long releaseId = createDualRelease("r-rel", "m1", "v2");
        long taskId = dualPull("r-pull", "d1");

        // 试启动响应冻结为 TRIAL 阶段
        MvcResult trialFirst = mockMvc.perform(post("/api/dual-tasks/" + taskId + "/trial")
                        .contentType("application/json").content("""
                        {"requestId":"idem-t","operator":"op-1","targetPartition":"B","targetVersion":"v2"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andReturn();
        clock.advance(Duration.ofSeconds(10));
        MvcResult confirmFirst = mockMvc.perform(post("/api/dual-tasks/" + taskId + "/confirm")
                        .contentType("application/json")
                        .content("{\"requestId\":\"idem-c\",\"operator\":\"op-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andReturn();

        // 提交旧试启动键：返回冻结的 TRIAL 响应，而非后来的 COMMITTED 终态
        mockMvc.perform(post("/api/dual-tasks/" + taskId + "/trial").contentType("application/json").content("""
                {"requestId":"idem-t","operator":"op-1","targetPartition":"B","targetVersion":"v2"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(trialFirst.getResponse().getContentAsString()));
        // 同键异参：409
        mockMvc.perform(post("/api/dual-tasks/" + taskId + "/trial").contentType("application/json").content("""
                {"requestId":"idem-t","operator":"op-1","targetPartition":"A","targetVersion":"v2"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        // 确认键重放：返回冻结的 COMMITTED 响应
        mockMvc.perform(post("/api/dual-tasks/" + taskId + "/confirm").contentType("application/json")
                        .content("{\"requestId\":\"idem-c\",\"operator\":\"op-1\"}"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(confirmFirst.getResponse().getContentAsString()));

        // 失败不占键：错误分区 409 后，同 requestId 正确参数成功
        long t2 = dualPull("r-pull2", "d2");
        mockMvc.perform(post("/api/dual-tasks/" + t2 + "/trial").contentType("application/json").content("""
                {"requestId":"idem-t2","operator":"op-1","targetPartition":"A","targetVersion":"v2"}
                """))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/dual-tasks/" + t2 + "/trial").contentType("application/json").content("""
                {"requestId":"idem-t2","operator":"op-1","targetPartition":"B","targetVersion":"v2"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"));

        // 结构化指纹：操作者参与身份，含分隔符的操作者不会与拼接碰撞
        MvcResult created = mockMvc.perform(post("/api/dual-releases").contentType("application/json").content("""
                {"requestId":"idem-r","operator":"op","model":"m9","targetVersion":"v|2"}
                """))
                .andExpect(status().isOk())
                .andReturn();
        mockMvc.perform(post("/api/dual-releases").contentType("application/json").content("""
                {"requestId":"idem-r","operator":"op","model":"m9","targetVersion":"v|2"}
                """))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(created.getResponse().getContentAsString()));
        mockMvc.perform(post("/api/dual-releases").contentType("application/json").content("""
                {"requestId":"idem-r","operator":"op|m9","model":"m9","targetVersion":"v|2"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        mockMvc.perform(post("/api/dual-releases").contentType("application/json").content("""
                {"requestId":"idem-r","operator":"op","model":"m9","targetVersion":"v2"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 同型号第二张未终结双分区发布单：409
        mockMvc.perform(post("/api/dual-releases").contentType("application/json").content("""
                {"requestId":"r-rel-dup","operator":"op-1","model":"m1","targetVersion":"v3"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_RELEASE_EXISTS"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dual_release", Long.class)).isEqualTo(2);
        assertThat(releaseId).isPositive();
    }

    @Test
    void 拉取边界_无发布单_版本已达标_设备不存在() throws Exception {
        registerDevice("r-dev1", "d1", "m1", "v1");
        // 无发布单：不投放
        mockMvc.perform(post("/api/devices/d1/dual-pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p1\",\"operator\":\"op-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());

        createDualRelease("r-rel", "m1", "v2");
        registerDevice("r-dev2", "d2", "m1", "v2");
        // 设备当前版本已等于目标版本：不投放
        mockMvc.perform(post("/api/devices/d2/dual-pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p2\",\"operator\":\"op-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());
        // 设备不存在：404
        mockMvc.perform(post("/api/devices/ghost/dual-pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p3\",\"operator\":\"op-1\"}"))
                .andExpect(status().isNotFound());
        // 命中后重复拉取返回同一未终结任务
        long taskId = dualPull("r-p4", "d1");
        mockMvc.perform(post("/api/devices/d1/dual-pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p5\",\"operator\":\"op-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(taskId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dual_task", Long.class)).isEqualTo(1);
    }
}