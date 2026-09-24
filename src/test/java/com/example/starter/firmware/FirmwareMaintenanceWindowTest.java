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
 * 设备本地维护窗口与投放顺延 API 测试（H2 内存库，MODE=MySQL，可控时钟）：
 * 跨零点窗口判定、DEFERRED 不计样本、顺延统计、窗口修订、暂停恢复交互与幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareMaintenanceWindowTest {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-09-24T10:00:00Z"));
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
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM task_deferral");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        clock.set(Instant.parse("2026-09-24T10:00:00Z"));
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void registerDeviceWithWindow(String requestId, String deviceId, int offset,
                                          int start, int end) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                "utcOffsetMinutes":%d,"windowStartMinute":%d,"windowEndMinute":%d}
                """.formatted(requestId, deviceId, offset, start, end)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, boolean respectWindow) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "respectMaintenanceWindow":%s}
                """.formatted(requestId, respectWindow)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.respectMaintenanceWindow").value(respectWindow))
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private MvcResult pull(String requestId, String deviceId) throws Exception {
        return mockMvc.perform(post("/api/devices/" + deviceId + "/pull").contentType("application/json")
                        .content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    void 跨零点窗口_窗口外顺延并携带下次开始时刻_窗口内恢复下发与回执() throws Exception {
        // 东八区，本地窗口 22:00~06:00（跨零点）；时钟 UTC 10:00 → 本地 18:00，窗口外
        registerDeviceWithWindow("r-dev", "d1", 480, 1320, 360);
        long releaseId = createRelease("r-rel", true);

        MvcResult deferred = pull("r-pull1", "d1");
        String body = deferred.getResponse().getContentAsString();
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist())
                .andExpect(jsonPath("$.result").value("DEFERRED"))
                .andExpect(jsonPath("$.nextWindowStartUtc").value("2026-09-24T14:00:00Z"));
        assertThat(body).contains("\"result\":\"DEFERRED\"")
                .contains("\"nextWindowStartUtc\":\"2026-09-24T14:00:00Z\"");

        // 顺延不下发任务、不计入失败率样本
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks.length()").value(0));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0));

        // 顺延统计：两次不同 requestId 的顺延各计一次
        mockMvc.perform(get("/api/releases/" + releaseId + "/deferrals/d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deferCount").value(2))
                .andExpect(jsonPath("$.lastDeferredAtUtc").value("2026-09-24T10:00:00Z"));

        // 时钟推进到 UTC 14:30 → 本地 22:30，窗口内：恢复原有下发与回执流程
        clock.set(Instant.parse("2026-09-24T14:30:00Z"));
        MvcResult pulled = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("TASK"))
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        long taskId = idOf(pulled, "$.task.taskId");
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // 历史顺延记录保留，不清零
        mockMvc.perform(get("/api/releases/" + releaseId + "/deferrals/d1"))
                .andExpect(jsonPath("$.deferCount").value(2));
    }

    @Test
    void 非跨零点窗口_边界左闭右开() throws Exception {
        // UTC 窗口 09:00~17:00；时钟 08:59 窗口外
        registerDeviceWithWindow("r-dev", "d1", 0, 540, 1020);
        createRelease("r-rel", true);
        clock.set(Instant.parse("2026-09-24T08:59:30Z"));
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p1\"}"))
                .andExpect(jsonPath("$.result").value("DEFERRED"))
                .andExpect(jsonPath("$.nextWindowStartUtc").value("2026-09-24T09:00:00Z"));

        // 09:00 左闭：窗口内
        clock.set(Instant.parse("2026-09-24T09:00:00Z"));
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p2\"}"))
                .andExpect(jsonPath("$.result").value("TASK"));

        // 17:00 右开：窗口外，下一次为次日 09:00
        registerDeviceWithWindow("r-dev2", "d2", 0, 540, 1020);
        clock.set(Instant.parse("2026-09-24T17:00:00Z"));
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p3\"}"))
                .andExpect(jsonPath("$.result").value("DEFERRED"))
                .andExpect(jsonPath("$.nextWindowStartUtc").value("2026-09-25T09:00:00Z"));
    }

    @Test
    void 顺延幂等_同键重放不重复累加_汇总稳定排序() throws Exception {
        registerDeviceWithWindow("r-dev1", "d2", 0, 540, 1020);
        registerDeviceWithWindow("r-dev2", "d1", 0, 540, 1020);
        long releaseId = createRelease("r-rel", true);
        // 时钟 10:00 UTC 在 09:00~17:00 窗口内；改到窗口外
        clock.set(Instant.parse("2026-09-24T20:00:00Z"));

        pull("r-p1", "d1");
        // 同 requestId 同参重放：响应一致，计数不重复累加
        MvcResult replay = pull("r-p1", "d1");
        mockMvc.perform(get("/api/releases/" + releaseId + "/deferrals/d1"))
                .andExpect(jsonPath("$.deferCount").value(1));
        assertThat(replay.getResponse().getContentAsString()).contains("\"result\":\"DEFERRED\"");

        pull("r-p2", "d1");
        pull("r-p3", "d2");
        // 汇总：按设备ID稳定排序，合计正确
        mockMvc.perform(get("/api/releases/" + releaseId + "/deferrals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deferredDeviceCount").value(2))
                .andExpect(jsonPath("$.totalDeferCount").value(3))
                .andExpect(jsonPath("$.records.length()").value(2))
                .andExpect(jsonPath("$.records[0].deviceId").value("d1"))
                .andExpect(jsonPath("$.records[0].deferCount").value(2))
                .andExpect(jsonPath("$.records[0].lastDeferredAtUtc").value("2026-09-24T20:00:00Z"))
                .andExpect(jsonPath("$.records[1].deviceId").value("d2"))
                .andExpect(jsonPath("$.records[1].deferCount").value(1));

        // 无顺延记录的设备：零值统计
        mockMvc.perform(get("/api/releases/" + releaseId + "/deferrals/ghost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deferCount").value(0));
        // 发布单不存在：404
        mockMvc.perform(get("/api/releases/9999/deferrals"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 开关关闭保持原行为_未配置窗口设备不受限() throws Exception {
        // 开关关闭：窗口外仍按原流程下发
        registerDeviceWithWindow("r-dev1", "d1", 0, 540, 1020);
        long releaseId = createRelease("r-rel", false);
        clock.set(Instant.parse("2026-09-24T20:00:00Z"));
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p1\"}"))
                .andExpect(jsonPath("$.result").value("TASK"))
                .andExpect(jsonPath("$.task.status").value("PENDING"));

        // 开关开启但设备未配置窗口：不受窗口限制
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-c1\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r-dev2","deviceId":"d2","model":"m1","currentVersion":"1.0.0","bucketNo":2}
                """)).andExpect(status().isOk());
        createRelease("r-rel2", true);
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p2\"}"))
                .andExpect(jsonPath("$.result").value("TASK"));
    }

    @Test
    void 窗口修订_版本冲突409_只影响后续拉取_幂等重放() throws Exception {
        // 窗口 09:00~17:00，时钟 10:00 窗口内：先下发任务
        registerDeviceWithWindow("r-dev", "d1", 0, 540, 1020);
        long releaseId = createRelease("r-rel", true);
        MvcResult pulled = pull("r-p1", "d1");
        long taskId = idOf(pulled, "$.task.taskId");

        // expectedVersion 不匹配：409
        mockMvc.perform(post("/api/devices/d1/window").contentType("application/json").content("""
                {"requestId":"r-w0","expectedVersion":9,"utcOffsetMinutes":0,
                "windowStartMinute":1200,"windowEndMinute":1260}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 正确修订为 20:00~21:00（当前 10:00 在窗口外），版本 1→2
        mockMvc.perform(post("/api/devices/d1/window").contentType("application/json").content("""
                {"requestId":"r-w1","expectedVersion":1,"utcOffsetMinutes":0,
                "windowStartMinute":1200,"windowEndMinute":1260}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.windowStartMinute").value(1200));
        // 同 requestId 同参重放：版本不再增加
        mockMvc.perform(post("/api/devices/d1/window").contentType("application/json").content("""
                {"requestId":"r-w1","expectedVersion":1,"utcOffsetMinutes":0,
                "windowStartMinute":1200,"windowEndMinute":1260}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 同 requestId 异参：409
        mockMvc.perform(post("/api/devices/d1/window").contentType("application/json").content("""
                {"requestId":"r-w1","expectedVersion":1,"utcOffsetMinutes":0,
                "windowStartMinute":1200,"windowEndMinute":1230}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 修改不改写已下发任务：重复拉取仍返回原任务而非顺延
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p2\"}"))
                .andExpect(jsonPath("$.result").value("TASK"))
                .andExpect(jsonPath("$.task.taskId").value(taskId));
        mockMvc.perform(get("/api/releases/" + releaseId + "/deferrals"))
                .andExpect(jsonPath("$.totalDeferCount").value(0));

        // 新设备按新窗口判定：窗口外顺延
        registerDeviceWithWindow("r-dev2", "d2", 0, 540, 1020);
        mockMvc.perform(post("/api/devices/d2/window").contentType("application/json").content("""
                {"requestId":"r-w2","expectedVersion":1,"utcOffsetMinutes":0,
                "windowStartMinute":1200,"windowEndMinute":1260}
                """)).andExpect(status().isOk());
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p3\"}"))
                .andExpect(jsonPath("$.result").value("DEFERRED"))
                .andExpect(jsonPath("$.nextWindowStartUtc").value("2026-09-24T20:00:00Z"));

        // 窗口查询（只读）
        mockMvc.perform(get("/api/devices/d1/window"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.utcOffsetMinutes").value(0))
                .andExpect(jsonPath("$.windowStartMinute").value(1200))
                .andExpect(jsonPath("$.windowEndMinute").value(1260));
    }

    @Test
    void 暂停恢复交互_窗口内暂停理由可区分_恢复后顺延计数不清零() throws Exception {
        // 东八区窗口 22:00~06:00；先在窗口外顺延一次（计数1）
        registerDeviceWithWindow("r-dev1", "d1", 480, 1320, 360);
        registerDeviceWithWindow("r-dev2", "d2", 480, 1320, 360);
        registerDeviceWithWindow("r-dev3", "d3", 480, 1320, 360);
        MvcResult created = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-rel","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "sampleFloor":2,"failureThresholdPercent":50,"respectMaintenanceWindow":true}
                """)).andExpect(status().isOk()).andReturn();
        long releaseId = idOf(created, "$.releaseId");

        // 时钟 UTC 10:00 → 本地 18:00 窗口外：d3 顺延一次
        pull("r-p3a", "d3");
        mockMvc.perform(get("/api/releases/" + releaseId + "/deferrals/d3"))
                .andExpect(jsonPath("$.deferCount").value(1));

        // 时钟进入窗口（UTC 14:30 → 本地 22:30）：d1/d2 下发并失败，触发暂停
        clock.set(Instant.parse("2026-09-24T14:30:00Z"));
        long t1 = idOf(pull("r-p1", "d1"), "$.task.taskId");
        long t2 = idOf(pull("r-p2", "d2"), "$.task.taskId");
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                .content("{\"requestId\":\"r-rc1\",\"result\":\"FAILED\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                .content("{\"requestId\":\"r-rc2\",\"result\":\"FAILED\"}")).andExpect(status().isOk());
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"));

        // PAUSED + 窗口内：不下发新任务，理由与顺延可区分，且不记顺延
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p3b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist())
                .andExpect(jsonPath("$.result").value("RELEASE_PAUSED"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/deferrals/d3"))
                .andExpect(jsonPath("$.deferCount").value(1));

        // 人工恢复：新监控轮次统计从零开始
        mockMvc.perform(post("/api/releases/" + releaseId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"r-res\",\"expectedVersion\":1,\"reason\":\"已修复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.monitorRound").value(2));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0));

        // 恢复后窗口外再次顺延：计数在历史上的基础上累加，不清零
        clock.set(Instant.parse("2026-09-24T10:00:00Z"));
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p3c\"}"))
                .andExpect(jsonPath("$.result").value("DEFERRED"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/deferrals/d3"))
                .andExpect(jsonPath("$.deferCount").value(2))
                .andExpect(jsonPath("$.lastDeferredAtUtc").value("2026-09-24T10:00:00Z"));
    }

    @Test
    void 窗口参数校验_登记与修订() throws Exception {
        // 三项缺一项：400
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r-b1","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                "utcOffsetMinutes":0}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WINDOW_INCOMPLETE"));
        // 起止相同：400
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r-b2","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                "utcOffsetMinutes":0,"windowStartMinute":600,"windowEndMinute":600}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WINDOW_INVALID"));
        // 偏移越界：400
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r-b3","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                "utcOffsetMinutes":841,"windowStartMinute":0,"windowEndMinute":600}
                """))
                .andExpect(status().isBadRequest());
        // 分钟越界：400
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r-b4","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                "utcOffsetMinutes":0,"windowStartMinute":0,"windowEndMinute":1440}
                """))
                .andExpect(status().isBadRequest());

        // 合法登记：窗口字段入库可查
        registerDeviceWithWindow("r-ok", "d1", 480, 1320, 360);
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.utcOffsetMinutes").value(480))
                .andExpect(jsonPath("$.windowStartMinute").value(1320))
                .andExpect(jsonPath("$.windowEndMinute").value(360));

        // 修订起止相同：400；设备不存在：404
        mockMvc.perform(post("/api/devices/d1/window").contentType("application/json").content("""
                {"requestId":"r-b5","expectedVersion":1,"utcOffsetMinutes":0,
                "windowStartMinute":100,"windowEndMinute":100}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WINDOW_INVALID"));
        mockMvc.perform(post("/api/devices/ghost/window").contentType("application/json").content("""
                {"requestId":"r-b6","expectedVersion":1,"utcOffsetMinutes":0,
                "windowStartMinute":100,"windowEndMinute":200}
                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/devices/ghost/window"))
                .andExpect(status().isNotFound());
    }
}
