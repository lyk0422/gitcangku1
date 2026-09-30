package com.example.starter.firmware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设备隔离 API 测试（H2 内存库，MODE=MySQL）：
 * 连续两次失败隔离、隔离依据固化、隔离期拉取 409、失败回执幂等重放与换任务 409、
 * 异审核人解除（只开新一轮不清历史）、版本变化计数独立、SUCCESS 自然终结、可控时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareQuarantineTest {

    private static final String FIXED_NOW = "2026-09-30T07:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device_failure_round");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        Mockito.when(clock.instant()).thenReturn(Instant.parse(FIXED_NOW));
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void registerDevice(String requestId, String deviceId, String model, String version, int bucket)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d}
                """.formatted(requestId, deviceId, model, version, bucket)))
                .andExpect(status().isOk());
    }

    /**
     * floor=3：单个设备两次失败时样本不足，发布单保持 ACTIVE，便于专注隔离行为。
     * 调用方须确保同型号前一张发布单已取消（同型号至多一张未终结发布单）。
     */
    private long createRelease(String requestId, String model, String from, String to) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"%s","toVersion":"%s","ratio":100,
                "sampleFloor":3,"failureThresholdPercent":100}
                """.formatted(requestId, model, from, to)))
                .andExpect(status().isOk())
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

    private void failReceipt(long taskId, String requestId, String operator, String reason) throws Exception {
        String body = reason == null
                ? "{\"requestId\":\"%s\",\"result\":\"FAILED\",\"operator\":\"%s\"}".formatted(requestId, operator)
                : "{\"requestId\":\"%s\",\"result\":\"FAILED\",\"operator\":\"%s\",\"failureReason\":\"%s\"}"
                        .formatted(requestId, operator, reason);
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    private void successReceipt(long taskId, String requestId) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"result\":\"SUCCESS\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    /**
     * 准备同型号前后两张同目标版本发布单中的两个失败任务（跨发布单连续计数），
     * 返回（第一次任务ID，第二次任务ID，发布单2 ID）；发布单1已取消，发布单2 ACTIVE。
     */
    private long[] twoReleasesWithTasks(String deviceId) throws Exception {
        long release1 = createRelease("r-rel1", "m1", "1.0.0", "2.0.0");
        long task1 = pullTask("r-pull1", deviceId);
        failReceipt(task1, "r-fail1", "alice", "flash-write-error");
        // 第一次失败后仅 COUNTING：发布单失败统计照常累计
        mockMvc.perform(get("/api/releases/" + release1 + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(get("/api/devices/" + deviceId + "/quarantine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quarantined").value(false))
                .andExpect(jsonPath("$.version").value("2.0.0"))
                .andExpect(jsonPath("$.roundNo").value(1))
                .andExpect(jsonPath("$.consecutiveFailures").value(1))
                .andExpect(jsonPath("$.rounds.length()").value(1))
                .andExpect(jsonPath("$.rounds[0].status").value("COUNTING"))
                .andExpect(jsonPath("$.rounds[0].firstTaskId").value(task1))
                .andExpect(jsonPath("$.rounds[0].firstReason").value("flash-write-error"))
                .andExpect(jsonPath("$.releases.length()").value(0));

        // 终结发布单1才能建同型号发布单2；FAILED 任务保留
        mockMvc.perform(post("/api/releases/" + release1 + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-cancel1\"}"))
                .andExpect(status().isOk());
        long release2 = createRelease("r-rel2", "m1", "1.0.0", "2.0.0");
        // COUNTING 未隔离：可在发布单2领取新任务
        long task2 = pullTask("r-pull2", deviceId);
        assertThat(task2).isNotEqualTo(task1);
        assertThat(release2).isPositive();
        return new long[]{task1, task2};
    }

    @Test
    void 连续两次失败_跨发布单计数_隔离并固化两次任务版本原因与确认人() throws Exception {
        registerDevice("r-dev", "d1", "m1", "1.0.0", 1);
        long[] tasks = twoReleasesWithTasks("d1");
        long task1 = tasks[0];
        long task2 = tasks[1];

        // 第二次失败：同事务隔离，发布单统计继续累计为 2
        failReceipt(task2, "r-fail2", "bob", "checksum-mismatch");
        mockMvc.perform(get("/api/devices/d1/quarantine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quarantined").value(true))
                .andExpect(jsonPath("$.version").value("2.0.0"))
                .andExpect(jsonPath("$.roundNo").value(1))
                .andExpect(jsonPath("$.consecutiveFailures").value(2))
                .andExpect(jsonPath("$.basis.version").value("2.0.0"))
                .andExpect(jsonPath("$.basis.roundNo").value(1))
                .andExpect(jsonPath("$.basis.firstTaskId").value(task1))
                .andExpect(jsonPath("$.basis.firstReason").value("flash-write-error"))
                .andExpect(jsonPath("$.basis.secondTaskId").value(task2))
                .andExpect(jsonPath("$.basis.secondReason").value("checksum-mismatch"))
                .andExpect(jsonPath("$.basis.confirmedBy").value("bob"))
                .andExpect(jsonPath("$.basis.quarantinedAtUtc").value(FIXED_NOW));
        // 隔离轮次明细状态为 QUARANTINED，两次任务都在
        mockMvc.perform(get("/api/devices/d1/quarantine"))
                .andExpect(jsonPath("$.rounds.length()").value(1))
                .andExpect(jsonPath("$.rounds[0].status").value("QUARANTINED"))
                .andExpect(jsonPath("$.rounds[0].consecutiveFailures").value(2))
                .andExpect(jsonPath("$.rounds[0].secondTaskId").value(task2));
        Long rounds = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_failure_round WHERE device_id = 'd1'", Long.class);
        assertThat(rounds).isEqualTo(1);
    }

    @Test
    void 隔离期间拉取一律409_设备不存在404() throws Exception {
        registerDevice("r-dev", "d1", "m1", "1.0.0", 1);
        long[] tasks = twoReleasesWithTasks("d1");
        failReceipt(tasks[1], "r-fail2", "bob", "checksum-mismatch");

        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull-q\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_QUARANTINED"));
        // 拉取失败不占幂等键：解除后同 requestId 仍可成功（先验证查询接口 404）
        mockMvc.perform(get("/api/devices/ghost/quarantine"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));
        mockMvc.perform(post("/api/devices/ghost/quarantine/release").contentType("application/json")
                        .content("{\"requestId\":\"r-rel-x\",\"operator\":\"carol\",\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 首次失败回执同requestId重放_计数与状态不变_换任务409() throws Exception {
        registerDevice("r-dev", "d1", "m1", "1.0.0", 1);
        registerDevice("r-dev2", "d2", "m1", "1.0.0", 2);
        long release1 = createRelease("r-rel1", "m1", "1.0.0", "2.0.0");
        long task1 = pullTask("r-pull1", "d1");
        long taskOther = pullTask("r-pull-other", "d2");
        failReceipt(task1, "r-fail1", "alice", "err-1");

        // 同 requestId 同参重放：返回 FAILED，不重复计数、状态仍 COUNTING
        MvcResult replay = mockMvc.perform(post("/api/tasks/" + task1 + "/receipt")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-fail1\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andReturn();
        mockMvc.perform(get("/api/devices/d1/quarantine"))
                .andExpect(jsonPath("$.consecutiveFailures").value(1))
                .andExpect(jsonPath("$.quarantined").value(false))
                .andExpect(jsonPath("$.rounds[0].status").value("COUNTING"));
        mockMvc.perform(get("/api/releases/" + release1 + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(1));
        assertThat(replay.getResponse().getContentAsString()).contains("\"taskId\":" + task1);

        // 同 requestId 换到另一任务：指纹不同，409
        mockMvc.perform(post("/api/tasks/" + taskOther + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-fail1\",\"result\":\"FAILED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        // 被换任务未受影响、未计数
        mockMvc.perform(get("/api/devices/d2/quarantine"))
                .andExpect(jsonPath("$.quarantined").value(false))
                .andExpect(jsonPath("$.rounds.length()").value(0));
    }

    @Test
    void 第二次失败重放_不重复隔离_依据保持首次提交内容() throws Exception {
        registerDevice("r-dev", "d1", "m1", "1.0.0", 1);
        long[] tasks = twoReleasesWithTasks("d1");
        failReceipt(tasks[1], "r-fail2", "bob", "checksum-mismatch");

        mockMvc.perform(post("/api/tasks/" + tasks[1] + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-fail2\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        mockMvc.perform(get("/api/devices/d1/quarantine"))
                .andExpect(jsonPath("$.quarantined").value(true))
                .andExpect(jsonPath("$.consecutiveFailures").value(2))
                .andExpect(jsonPath("$.basis.secondTaskId").value(tasks[1]))
                .andExpect(jsonPath("$.basis.confirmedBy").value("bob"));
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_failure_round WHERE device_id = 'd1' AND status = 'QUARANTINED'",
                Long.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void 解除_确认人本人409_他人审核成功只开新一轮不清历史() throws Exception {
        registerDevice("r-dev", "d1", "m1", "1.0.0", 1);
        long[] tasks = twoReleasesWithTasks("d1");
        failReceipt(tasks[1], "r-fail2", "bob", "checksum-mismatch");

        // 隔离确认人本人不能解除
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json")
                        .content("{\"requestId\":\"r-rel-self\",\"operator\":\"bob\",\"reason\":\"我觉得没问题\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUARANTINE_RELEASE_SELF"));
        // 仍隔离
        mockMvc.perform(get("/api/devices/d1/quarantine"))
                .andExpect(jsonPath("$.quarantined").value(true));

        // 不同于确认人的操作人审核解除
        MvcResult released = mockMvc.perform(post("/api/devices/d1/quarantine/release")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-rel-ok\",\"operator\":\"carol\",\"reason\":\"现场已更换硬件\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quarantined").value(false))
                .andExpect(jsonPath("$.releases.length()").value(1))
                .andExpect(jsonPath("$.releases[0].version").value("2.0.0"))
                .andExpect(jsonPath("$.releases[0].roundNo").value(1))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"))
                .andExpect(jsonPath("$.releases[0].reason").value("现场已更换硬件"))
                .andExpect(jsonPath("$.releases[0].releasedAtUtc").value(FIXED_NOW))
                .andReturn();
        assertThat(released.getResponse().getContentAsString()).contains("\"consecutiveFailures\":2");

        // 旧两次失败仍可查：第1轮 RELEASED，次数保留为 2，两次任务与原因不清除
        mockMvc.perform(get("/api/devices/d1/quarantine"))
                .andExpect(jsonPath("$.rounds.length()").value(1))
                .andExpect(jsonPath("$.rounds[0].status").value("RELEASED"))
                .andExpect(jsonPath("$.rounds[0].consecutiveFailures").value(2))
                .andExpect(jsonPath("$.rounds[0].firstTaskId").value(tasks[0]))
                .andExpect(jsonPath("$.rounds[0].secondTaskId").value(tasks[1]))
                .andExpect(jsonPath("$.rounds[0].confirmedBy").value("bob"))
                .andExpect(jsonPath("$.rounds[0].release.releasedBy").value("carol"));

        // 非隔离状态再次解除：409
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json")
                        .content("{\"requestId\":\"r-rel-again\",\"operator\":\"dave\",\"reason\":\"再解一次\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_QUARANTINED"));

        // 解除后可领新一轮投放：终结发布单2、建发布单3，拉取成功，失败开第2轮（从1计数，不是在历史2上累加）
        Long release2Id = jdbc.queryForObject("SELECT MAX(id) FROM release_order", Long.class);
        mockMvc.perform(post("/api/releases/" + release2Id + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-cancel2\"}"))
                .andExpect(status().isOk());
        createRelease("r-rel3", "m1", "1.0.0", "2.0.0");
        long task3 = pullTask("r-pull3", "d1");
        assertThat(task3).isNotEqualTo(tasks[0]).isNotEqualTo(tasks[1]);
        failReceipt(task3, "r-fail3", "alice", "power-dip");
        mockMvc.perform(get("/api/devices/d1/quarantine"))
                .andExpect(jsonPath("$.quarantined").value(false))
                .andExpect(jsonPath("$.version").value("2.0.0"))
                .andExpect(jsonPath("$.roundNo").value(2))
                .andExpect(jsonPath("$.consecutiveFailures").value(1))
                .andExpect(jsonPath("$.rounds.length()").value(2))
                .andExpect(jsonPath("$.rounds[0].status").value("RELEASED"))
                .andExpect(jsonPath("$.rounds[1].status").value("COUNTING"))
                .andExpect(jsonPath("$.rounds[1].consecutiveFailures").value(1));
    }

    @Test
    void 解除幂等_同键同参重放只解除一次_异参409() throws Exception {
        registerDevice("r-dev", "d1", "m1", "1.0.0", 1);
        long[] tasks = twoReleasesWithTasks("d1");
        failReceipt(tasks[1], "r-fail2", "bob", "err");

        String body = "{\"requestId\":\"r-rel\",\"operator\":\"carol\",\"reason\":\"已复核\"}";
        MvcResult first = mockMvc.perform(post("/api/devices/d1/quarantine/release")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn();
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(first.getResponse().getContentAsString()));
        // 同键异参 409
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json")
                        .content("{\"requestId\":\"r-rel\",\"operator\":\"dave\",\"reason\":\"已复核\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        mockMvc.perform(get("/api/devices/d1/quarantine"))
                .andExpect(jsonPath("$.releases.length()").value(1));
    }

    @Test
    void 版本变化计数独立_成功自然终结本轮_历史不重算() throws Exception {
        registerDevice("r-dev", "d1", "m1", "1.0.0", 1);
        // 2.0.0 失败一次：COUNTING，1 次
        long release1 = createRelease("r-rel1", "m1", "1.0.0", "2.0.0");
        long task1 = pullTask("r-pull1", "d1");
        failReceipt(task1, "r-fail1", "alice", "err-2x");

        // 换发布单重试同版本成功：COUNTING 轮次自然终结为 RECOVERED，设备升级 2.0.0
        mockMvc.perform(post("/api/releases/" + release1 + "/cancel").contentType("application/json")
                .content("{\"requestId\":\"r-cancel1\"}")).andExpect(status().isOk());
        long release2 = createRelease("r-rel2", "m1", "1.0.0", "2.0.0");
        long task2 = pullTask("r-pull2", "d1");
        successReceipt(task2, "r-ok2");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        mockMvc.perform(get("/api/devices/d1/quarantine"))
                .andExpect(jsonPath("$.rounds.length()").value(1))
                .andExpect(jsonPath("$.rounds[0].status").value("RECOVERED"))
                .andExpect(jsonPath("$.rounds[0].consecutiveFailures").value(1));

        // 新版本 3.0.0 的失败独立计数：不带原因时原因为 null，一次失败不隔离
        mockMvc.perform(post("/api/releases/" + release2 + "/cancel").contentType("application/json")
                .content("{\"requestId\":\"r-cancel2\"}")).andExpect(status().isOk());
        createRelease("r-rel3", "m1", "2.0.0", "3.0.0");
        long task3 = pullTask("r-pull3", "d1");
        failReceipt(task3, "r-fail3", "bob", null);
        mockMvc.perform(get("/api/devices/d1/quarantine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quarantined").value(false))
                .andExpect(jsonPath("$.version").value("3.0.0"))
                .andExpect(jsonPath("$.roundNo").value(1))
                .andExpect(jsonPath("$.consecutiveFailures").value(1))
                .andExpect(jsonPath("$.rounds.length()").value(2))
                .andExpect(jsonPath("$.rounds[0].version").value("2.0.0"))
                .andExpect(jsonPath("$.rounds[0].status").value("RECOVERED"))
                .andExpect(jsonPath("$.rounds[0].firstReason").value("err-2x"))
                .andExpect(jsonPath("$.rounds[1].version").value("3.0.0"))
                .andExpect(jsonPath("$.rounds[1].status").value("COUNTING"))
                .andExpect(jsonPath("$.rounds[1].firstReason").doesNotExist());
    }

    @Test
    void 无失败历史设备_查询返回未隔离空结构() throws Exception {
        registerDevice("r-dev", "d1", "m1", "1.0.0", 1);
        mockMvc.perform(get("/api/devices/d1/quarantine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("d1"))
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.roundNo").value(0))
                .andExpect(jsonPath("$.consecutiveFailures").value(0))
                .andExpect(jsonPath("$.quarantined").value(false))
                .andExpect(jsonPath("$.basis").doesNotExist())
                .andExpect(jsonPath("$.releases.length()").value(0))
                .andExpect(jsonPath("$.rounds.length()").value(0));
    }

    @Test
    void 旧回执不带操作人_确认人记为device_本人解除409_他人可解除() throws Exception {
        registerDevice("r-dev", "d1", "m1", "1.0.0", 1);
        long release1 = createRelease("r-rel1", "m1", "1.0.0", "2.0.0");
        MvcResult pull1 = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull1\"}")).andReturn();
        long task1 = idOf(pull1, "$.task.taskId");
        // 旧客户端回执：只有 requestId 与 result
        mockMvc.perform(post("/api/tasks/" + task1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-fail1\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/releases/" + release1 + "/cancel").contentType("application/json")
                .content("{\"requestId\":\"r-cancel1\"}")).andExpect(status().isOk());
        long release2 = createRelease("r-rel2", "m1", "1.0.0", "2.0.0");
        long task2 = pullTask("r-pull2", "d1");
        mockMvc.perform(post("/api/tasks/" + task2 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-fail2\",\"result\":\"FAILED\",\"failureReason\":\"旧端原因\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/d1/quarantine"))
                .andExpect(jsonPath("$.quarantined").value(true))
                .andExpect(jsonPath("$.basis.confirmedBy").value("device"))
                .andExpect(jsonPath("$.basis.secondReason").value("旧端原因"));
        assertThat(release2).isPositive();

        // 设备自身（device）不能解除
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json")
                        .content("{\"requestId\":\"r-rel-self\",\"operator\":\"device\",\"reason\":\"自愈\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUARANTINE_RELEASE_SELF"));
        // 人工审核可解除
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json")
                        .content("{\"requestId\":\"r-rel-ok\",\"operator\":\"carol\",\"reason\":\"人工复核\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quarantined").value(false))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"));
    }

    @Test
    void 隔离只针对同一版本_其他版本仍可拉取_失败统计继续保留() throws Exception {
        registerDevice("r-dev", "d1", "m1", "1.0.0", 1);
        // 2.0.0 连续两次失败隔离
        long release1 = createRelease("r-rel1", "m1", "1.0.0", "2.0.0");
        long task1 = pullTask("r-pull1", "d1");
        failReceipt(task1, "r-fail1", "alice", "err-2x-1");
        mockMvc.perform(post("/api/releases/" + release1 + "/cancel").contentType("application/json")
                .content("{\"requestId\":\"r-cancel1\"}")).andExpect(status().isOk());
        long release2 = createRelease("r-rel2", "m1", "1.0.0", "2.0.0");
        long task2 = pullTask("r-pull2", "d1");
        failReceipt(task2, "r-fail2", "bob", "err-2x-2");
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull-2x-q\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_QUARANTINED"));

        // 取消 2.0.0 发布单，改投另一目标版本 1.5.0（d1 当前仍为 1.0.0，符合来源版本）：
        // 隔离只针对 2.0.0，d1 可正常领取 1.5.0 任务
        mockMvc.perform(post("/api/releases/" + release2 + "/cancel").contentType("application/json")
                .content("{\"requestId\":\"r-cancel2\"}")).andExpect(status().isOk());
        createRelease("r-rel3", "m1", "1.0.0", "1.5.0");
        MvcResult pull15 = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull-15\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.toVersion").value("1.5.0"))
                .andReturn();
        long task15 = idOf(pull15, "$.task.taskId");
        assertThat(task15).isNotEqualTo(task1).isNotEqualTo(task2);

        // d1 的 2.0.0 隔离依据与发布单失败统计继续保留，不因其他版本投放而变化
        mockMvc.perform(get("/api/devices/d1/quarantine"))
                .andExpect(jsonPath("$.quarantined").value(true))
                .andExpect(jsonPath("$.basis.version").value("2.0.0"))
                .andExpect(jsonPath("$.consecutiveFailures").value(2))
                .andExpect(jsonPath("$.rounds.length()").value(1));
        mockMvc.perform(get("/api/releases/" + release2 + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
