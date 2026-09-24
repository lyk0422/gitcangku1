package com.example.starter.firmware;

import com.example.starter.StarterApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设备本地维护窗口与投放顺延 API 测试（真实 H2 内存库，MODE=MySQL）：
 * 跨零点窗口判定、DEFERRED 携带 UTC 起点、顺延不计失败率样本、顺延累计与幂等、
 * 窗口修订乐观锁、暂停/恢复交互、开关关闭兼容、顺延查询稳定排序与参数校验。
 * 服务端时钟通过可替换 Clock 注入，不依赖真实等待。
 */
@SpringBootTest(classes = {StarterApplication.class, ControllableClockConfig.class})
@AutoConfigureMockMvc
class MaintenanceWindowApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM task_defer_state");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        ControllableClockConfig.setAt("2026-09-24T12:00:00Z");
    }

    private static void setClock(String utc) {
        ControllableClockConfig.setAt(utc);
    }

    private void registerDevice(String requestId, String deviceId, int offset, int windowStart, int windowEnd)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                "utcOffsetMinutes":%d,"windowStartMinute":%d,"windowEndMinute":%d}
                """.formatted(requestId, deviceId, offset, windowStart, windowEnd)))
                .andExpect(status().isOk());
    }

    private long createWindowedRelease(String requestId, boolean respectWindow, int ratio,
                                       int floor, int threshold) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":%d,
                "sampleFloor":%d,"failureThresholdPercent":%d,"respectMaintenanceWindow":%s}
                """.formatted(requestId, ratio, floor, threshold, respectWindow)))
                .andExpect(status().isOk())
                .andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.releaseId");
        return id.longValue();
    }

    private MvcResult pull(String requestId, String deviceId) throws Exception {
        return mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andReturn();
    }

    private static long taskIdOf(MvcResult result) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),
                "$.task.taskId");
        return value.longValue();
    }

    @Test
    void 跨零点窗口_窗口外顺延携带UTC起点_窗口内恢复下发_右开边界() throws Exception {
        // 设备本地窗口 23:00~次日01:00（1380 起，60 止），UTC 偏移 0
        registerDevice("r-d1", "d1", 0, 1380, 60);
        registerDevice("r-d2", "d2", 0, 1380, 60);
        long releaseId = createWindowedRelease("r-r", true, 100, 2, 50);

        // UTC 22:00 窗口外：DEFERRED，携带下一次窗口开始 UTC 23:00
        setClock("2026-09-24T22:00:00Z");
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DEFERRED"))
                .andExpect(jsonPath("$.task").doesNotExist())
                .andExpect(jsonPath("$.reason").value("OUTSIDE_MAINTENANCE_WINDOW"))
                .andExpect(jsonPath("$.nextWindowStartUtc").value("2026-09-24T23:00:00Z"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT defer_count FROM task_defer_state WHERE release_id = ? AND device_id = 'd1'",
                Long.class, releaseId)).isEqualTo(1);

        // UTC 23:30 窗口内：恢复原有下发
        setClock("2026-09-24T23:30:00Z");
        MvcResult dispatched = pull("r-p2", "d1");
        long taskId = taskIdOf(dispatched);
        assertThat((String) com.jayway.jsonpath.JsonPath.read(
                dispatched.getResponse().getContentAsString(), "$.result"))
                .isEqualTo("DISPATCHED");

        // UTC 次日00:30 仍在跨零点窗口内：设备已有任务，直接返回原任务而非顺延
        setClock("2026-09-25T00:30:00Z");
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DISPATCHED"))
                .andExpect(jsonPath("$.task.taskId").value(taskId));
        // 顺延计数未因窗口内重复拉取增加
        assertThat(jdbc.queryForObject("SELECT defer_count FROM task_defer_state WHERE release_id = ? AND device_id = 'd1'",
                Long.class, releaseId)).isEqualTo(1);

        // UTC 次日01:00 为右开边界：另一设备在窗口外，DEFERRED，起点为当天 23:00
        setClock("2026-09-25T01:00:00Z");
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DEFERRED"))
                .andExpect(jsonPath("$.nextWindowStartUtc").value("2026-09-25T23:00:00Z"));
    }

    @Test
    void 顺延不下发不改状态_不计入失败率样本() throws Exception {
        registerDevice("r-d1", "d1", 0, 1380, 60);
        registerDevice("r-d2", "d2", 0, 1380, 60);
        // 阈值100：1/2 失败不会触发暂停，便于核对顺延从未进入样本
        long releaseId = createWindowedRelease("r-r", true, 100, 2, 100);

        // 窗口外两次顺延：无任务、无样本
        setClock("2026-09-24T22:00:00Z");
        pull("r-p1", "d1");
        pull("r-p2", "d2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.monitorRound").value(1))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0));

        // 进入窗口：两台下发，一台 FAILED；失败样本只计 1（顺延从不计样本）
        setClock("2026-09-24T23:00:00Z");
        long t1 = taskIdOf(pull("r-p3", "d1"));
        long t2 = taskIdOf(pull("r-p4", "d2"));
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-c1\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-c2\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.roundSuccess").value(1));
    }

    @Test
    void 顺延累计次数与最近时刻_同键重放不重复累加_异参409() throws Exception {
        registerDevice("r-d1", "d1", 0, 1380, 60);
        registerDevice("r-d2", "d2", 0, 1380, 60);
        long releaseId = createWindowedRelease("r-r", true, 100, 2, 50);

        setClock("2026-09-24T22:00:00Z");
        String first = pull("r-p1", "d1").getResponse().getContentAsString();
        // 同 requestId 同参重放：响应一致，计数不重复累加
        String replay = pull("r-p1", "d1").getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT defer_count FROM task_defer_state WHERE release_id = ? AND device_id = 'd1'",
                Long.class, releaseId)).isEqualTo(1);

        // 新 requestId 再次顺延：计数 2，最近顺延时刻更新
        setClock("2026-09-24T22:30:00Z");
        pull("r-p2", "d1");
        assertThat(jdbc.queryForObject("SELECT defer_count FROM task_defer_state WHERE release_id = ? AND device_id = 'd1'",
                Long.class, releaseId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT last_deferred_at_utc FROM task_defer_state WHERE release_id = ? AND device_id = 'd1'",
                String.class, releaseId)).isEqualTo("2026-09-24T22:30:00Z");

        // 同 requestId 换设备（异参）：409，不产生顺延
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_defer_state", Long.class)).isEqualTo(1);
    }

    @Test
    void 窗口修订_乐观锁409_只影响后续判定_不改历史顺延_失败不占键() throws Exception {
        // d1 窗口 03:00~05:00
        registerDevice("r-d1", "d1", 0, 180, 300);
        long releaseId = createWindowedRelease("r-r", true, 100, 2, 50);

        // 02:00 在窗口外，先产生一次顺延
        setClock("2026-09-24T02:00:00Z");
        pull("r-p1", "d1");
        assertThat(jdbc.queryForObject("SELECT defer_count FROM task_defer_state WHERE release_id = ? AND device_id = 'd1'",
                Long.class, releaseId)).isEqualTo(1);

        // expectedVersion 不匹配：409，版本不变
        mockMvc.perform(put("/api/devices/d1/window").contentType("application/json").content("""
                {"requestId":"r-w-bad","expectedVersion":99,"utcOffsetMinutes":0,
                "windowStartMinute":0,"windowEndMinute":180}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_VERSION_CONFLICT"));

        // 失败不占键：同一 requestId 携带正确版本再次请求成功
        mockMvc.perform(put("/api/devices/d1/window").contentType("application/json").content("""
                {"requestId":"r-w-bad","expectedVersion":1,"utcOffsetMinutes":0,
                "windowStartMinute":0,"windowEndMinute":180}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceVersion").value(2))
                .andExpect(jsonPath("$.windowStartMinute").value(0))
                .andExpect(jsonPath("$.windowEndMinute").value(180));

        // 历史顺延记录不被窗口修订改写
        assertThat(jdbc.queryForObject("SELECT defer_count FROM task_defer_state WHERE release_id = ? AND device_id = 'd1'",
                Long.class, releaseId)).isEqualTo(1);

        // 同 requestId 同参重放：返回同一快照，版本不再增加
        mockMvc.perform(put("/api/devices/d1/window").contentType("application/json").content("""
                {"requestId":"r-w-bad","expectedVersion":1,"utcOffsetMinutes":0,
                "windowStartMinute":0,"windowEndMinute":180}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceVersion").value(2))
                .andExpect(jsonPath("$.windowEndMinute").value(180));

        // 窗口修订起止相同：400
        mockMvc.perform(put("/api/devices/d1/window").contentType("application/json").content("""
                {"requestId":"r-w-eq","expectedVersion":2,"utcOffsetMinutes":0,
                "windowStartMinute":60,"windowEndMinute":60}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WINDOW_START_END_SAME"));

        // 修订只影响后续拉取判定：02:00 在新窗口 [00:00,03:00) 内，恢复下发
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DISPATCHED"))
                .andExpect(jsonPath("$.task.status").value("PENDING"));

        // 旧 expectedVersion 再次冲突；版本 2 修改成功到 3
        mockMvc.perform(put("/api/devices/d1/window").contentType("application/json").content("""
                {"requestId":"r-w2","expectedVersion":1,"utcOffsetMinutes":0,
                "windowStartMinute":0,"windowEndMinute":120}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_VERSION_CONFLICT"));

        // 不存在设备修订：404
        mockMvc.perform(put("/api/devices/ghost/window").contentType("application/json").content("""
                {"requestId":"r-w3","expectedVersion":1,"utcOffsetMinutes":0,
                "windowStartMinute":0,"windowEndMinute":120}
                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void 暂停时窗口内返回RELEASE_PAUSED可区分_恢复新轮次统计清零且顺延不清() throws Exception {
        registerDevice("r-d1", "d1", 0, 0, 600);
        registerDevice("r-d2", "d2", 0, 0, 600);
        registerDevice("r-d3", "d3", 0, 0, 600);
        long releaseId = createWindowedRelease("r-r", true, 100, 2, 50);

        // 02:00 在窗口 [00:00,10:00) 内：两台下发并全部 FAILED，自动暂停
        setClock("2026-09-24T02:00:00Z");
        long t1 = taskIdOf(pull("r-p1", "d1"));
        long t2 = taskIdOf(pull("r-p2", "d2"));
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                .content("{\"requestId\":\"r-c1\",\"result\":\"FAILED\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                .content("{\"requestId\":\"r-c2\",\"result\":\"FAILED\"}")).andExpect(status().isOk());
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"));

        // PAUSED 且新设备在窗口内：不下发新任务，但理由必须与顺延可区分
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("RELEASE_PAUSED"))
                .andExpect(jsonPath("$.reason").value("RELEASE_PAUSED"))
                .andExpect(jsonPath("$.task").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(2);

        // PAUSED 但设备在窗口外：仍按顺延返回（顺延优先），并累加顺延
        setClock("2026-09-24T23:00:00Z");
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DEFERRED"))
                .andExpect(jsonPath("$.nextWindowStartUtc").value("2026-09-25T00:00:00Z"));

        // 人工恢复：版本加一、新轮次统计从零开始
        mockMvc.perform(post("/api/releases/" + releaseId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"r-res\",\"expectedVersion\":1,\"reason\":\"修复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.monitorRound").value(2));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(0))
                .andExpect(jsonPath("$.roundSuccess").value(0));

        // 顺延计数不清零
        assertThat(jdbc.queryForObject("SELECT defer_count FROM task_defer_state WHERE release_id = ? AND device_id = 'd3'",
                Long.class, releaseId)).isEqualTo(1);

        // 新轮次窗口内拉取：d3 正常下发
        setClock("2026-09-25T02:00:00Z");
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DISPATCHED"))
                .andExpect(jsonPath("$.task.status").value("PENDING"));
    }

    @Test
    void 关闭维护窗口开关_窗口外保持原有下发行为() throws Exception {
        registerDevice("r-d1", "d1", 0, 180, 300);
        long releaseId = createWindowedRelease("r-r", false, 100, 2, 50);

        // 02:00 在窗口外，但开关关闭：直接下发，不产生顺延记录
        setClock("2026-09-24T02:00:00Z");
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DISPATCHED"))
                .andExpect(jsonPath("$.task.status").value("PENDING"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_defer_state", Long.class)).isZero();

        // 发布单视图与创建缺省值：开关可查询，旧客户端缺省为 false
        mockMvc.perform(get("/api/releases/" + releaseId + "/defers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deferredDevices").value(0))
                .andExpect(jsonPath("$.totalDeferCount").value(0));
    }

    @Test
    void 顺延汇总与单任务查询_稳定排序_404() throws Exception {
        // 乱序创建 d3/d1/d2，顺延次数不同
        registerDevice("r-d3", "d3", 0, 1380, 60);
        registerDevice("r-d1", "d1", 0, 1380, 60);
        registerDevice("r-d2", "d2", 0, 1380, 60);
        long releaseId = createWindowedRelease("r-r", true, 100, 2, 50);
        setClock("2026-09-24T22:00:00Z");
        pull("r-p3", "d3");
        pull("r-p1a", "d1");
        pull("r-p1b", "d1");
        pull("r-p2", "d2");

        mockMvc.perform(get("/api/releases/" + releaseId + "/defers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deferredDevices").value(3))
                .andExpect(jsonPath("$.totalDeferCount").value(4))
                .andExpect(jsonPath("$.defers.length()").value(3))
                .andExpect(jsonPath("$.defers[0].deviceId").value("d1"))
                .andExpect(jsonPath("$.defers[0].deferCount").value(2))
                .andExpect(jsonPath("$.defers[1].deviceId").value("d2"))
                .andExpect(jsonPath("$.defers[2].deviceId").value("d3"));

        mockMvc.perform(get("/api/releases/" + releaseId + "/defers/d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deferCount").value(2))
                .andExpect(jsonPath("$.lastDeferredAtUtc").value("2026-09-24T22:00:00Z"));

        // 未发生过顺延的设备：404
        registerDevice("r-d4", "d4", 0, 1380, 60);
        mockMvc.perform(get("/api/releases/" + releaseId + "/defers/d4"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEFER_STATE_NOT_FOUND"));
        // 发布单不存在：404
        mockMvc.perform(get("/api/releases/9999/defers"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 登记参数校验_起止相同与越界400_仅传偏移允许() throws Exception {
        // 起止相同：400
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r-b1","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                "utcOffsetMinutes":0,"windowStartMinute":120,"windowEndMinute":120}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WINDOW_START_END_SAME"));

        // 偏移越界：400（Bean Validation）
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r-b2","deviceId":"d2","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                "utcOffsetMinutes":841,"windowStartMinute":0,"windowEndMinute":120}
                """))
                .andExpect(status().isBadRequest());

        // 只传窗口起点不传结束：400
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r-b3","deviceId":"d3","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                "windowStartMinute":0}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WINDOW_BOUNDS_INCOMPLETE"));

        // 仅传偏移（西五区 -300），窗口缺省为全天：允许登记
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r-ok","deviceId":"d4","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                "utcOffsetMinutes":-300}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.utcOffsetMinutes").value(-300))
                .andExpect(jsonPath("$.windowStartMinute").value(0))
                .andExpect(jsonPath("$.windowEndMinute").value(0))
                .andExpect(jsonPath("$.deviceVersion").value(1));
    }
}
