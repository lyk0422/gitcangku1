package com.example.starter.firmware;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 取消握手幂等与状态迁移边界测试：新写端点同键同参重放首次响应、异参 409、失败不占键；
 * 取消不计失败率样本；未开始任务直接取消；终态拒绝迁移。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareCancelHandshakeIdempotencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM device_install_slot");
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

    private long setupInstallingTask(String suffix) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r-d%s","deviceId":"d%s","model":"m%s","currentVersion":"1.0.0","bucketNo":1}
                """.formatted(suffix, suffix, suffix))).andExpect(status().isOk());
        MvcResult release = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-r%s","model":"m%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """.formatted(suffix, suffix))).andExpect(status().isOk()).andReturn();
        MvcResult pull = mockMvc.perform(post("/api/devices/d" + suffix + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"r-p%s\"}".formatted(suffix)))
                .andExpect(status().isOk()).andReturn();
        long taskId = idOf(pull, "$.task.taskId");
        mockMvc.perform(post("/api/tasks/" + taskId + "/start").contentType("application/json").content("""
                {"requestId":"r-s%s","operator":"op1","expectedVersion":1}
                """.formatted(suffix))).andExpect(status().isOk());
        return taskId;
    }

    private long slotCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM device_install_slot", Long.class);
        return count == null ? 0 : count;
    }

    @Test
    void 幂等_同键同参重放首次响应_异参409_失败不占键() throws Exception {
        long taskId = setupInstallingTask("1");

        // start 失败不占键：先 409（版本不符），再同键正确参数成功
        mockMvc.perform(post("/api/tasks/" + taskId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-c1\",\"operator\":\"op1\",\"expectedVersion\":9}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        MvcResult cancel = mockMvc.perform(post("/api/tasks/" + taskId + "/cancel")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-c1\",\"operator\":\"op1\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCEL_REQUESTED"))
                .andExpect(jsonPath("$.cancelGeneration").value(1))
                .andReturn();

        // 同键同参重放：响应与首次完全一致，代次不重复增加
        mockMvc.perform(post("/api/tasks/" + taskId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-c1\",\"operator\":\"op1\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(cancel.getResponse().getContentAsString()));
        assertThat(jdbc.queryForObject("SELECT cancel_generation FROM rollout_task WHERE id = ?",
                Integer.class, taskId)).isEqualTo(1);

        // 同键异参（改操作者/改期望版本）：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-c1\",\"operator\":\"op2\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        mockMvc.perform(post("/api/tasks/" + taskId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-c1\",\"operator\":\"op1\",\"expectedVersion\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 停止确认重放：首次响应一致，槽只释放一次
        MvcResult confirm = mockMvc.perform(post("/api/tasks/" + taskId + "/stop-confirmation")
                        .contentType("application/json")
                        .content("""
                        {"requestId":"r-k1","operator":"op1","expectedVersion":1,"generation":1}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andReturn();
        assertThat(slotCount()).isZero();
        mockMvc.perform(post("/api/tasks/" + taskId + "/stop-confirmation")
                        .contentType("application/json")
                        .content("""
                        {"requestId":"r-k1","operator":"op1","expectedVersion":1,"generation":1}
                        """))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(confirm.getResponse().getContentAsString()));
        // 同键异参（改代次）：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/stop-confirmation")
                        .contentType("application/json")
                        .content("""
                        {"requestId":"r-k1","operator":"op1","expectedVersion":1,"generation":2}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void 取消不计失败率样本_状态迁移边界() throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r-d1","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":1}
                """)).andExpect(status().isOk());
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r-d2","deviceId":"d2","model":"m1","currentVersion":"1.0.0","bucketNo":2}
                """)).andExpect(status().isOk());
        MvcResult release = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-r1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "sampleFloor":2,"failureThresholdPercent":100}
                """)).andExpect(status().isOk()).andReturn();
        long releaseId = idOf(release, "$.releaseId");
        long t1 = idOf(mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                .content("{\"requestId\":\"r-p1\"}")).andReturn(), "$.task.taskId");
        long t2 = idOf(mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                .content("{\"requestId\":\"r-p2\"}")).andReturn(), "$.task.taskId");

        // t1 开始并失败：计 1 个失败样本
        mockMvc.perform(post("/api/tasks/" + t1 + "/start").contentType("application/json")
                .content("{\"requestId\":\"r-s1\",\"operator\":\"op1\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                .content("{\"requestId\":\"r-rc1\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk());
        assertThat(slotCount()).isZero();

        // t2 开始后取消请求：不计样本，发布单不暂停
        mockMvc.perform(post("/api/tasks/" + t2 + "/start").contentType("application/json")
                .content("{\"requestId\":\"r-s2\",\"operator\":\"op1\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/" + t2 + "/cancel").contentType("application/json")
                .content("{\"requestId\":\"r-c2\",\"operator\":\"op1\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCEL_REQUESTED"))
                .andExpect(jsonPath("$.cancelGeneration").value(1));
        assertThat(slotCount()).isEqualTo(1);
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.roundSuccess").value(0));

        // 重复取消请求 409；停止确认后再次确认 409；已终结任务取消 409
        mockMvc.perform(post("/api/tasks/" + t2 + "/cancel").contentType("application/json")
                .content("{\"requestId\":\"r-c2b\",\"operator\":\"op1\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCEL_ALREADY_REQUESTED"));
        mockMvc.perform(post("/api/tasks/" + t2 + "/stop-confirmation").contentType("application/json")
                .content("""
                {"requestId":"r-k2","operator":"op1","expectedVersion":1,"generation":1}
                """)).andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/" + t2 + "/stop-confirmation").contentType("application/json")
                .content("""
                {"requestId":"r-k2b","operator":"op1","expectedVersion":1,"generation":1}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_CANCEL_REQUESTED"));
        mockMvc.perform(post("/api/tasks/" + t1 + "/cancel").contentType("application/json")
                .content("{\"requestId\":\"r-c1\",\"operator\":\"op1\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_ALREADY_FINISHED"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.roundSuccess").value(0));
    }

    @Test
    void 未开始任务直接取消_不占槽_回执拒绝() throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r-d1","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":1}
                """)).andExpect(status().isOk());
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-r1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """)).andExpect(status().isOk());
        long taskId = idOf(mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                .content("{\"requestId\":\"r-p1\"}")).andReturn(), "$.task.taskId");

        mockMvc.perform(post("/api/tasks/" + taskId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-c1\",\"operator\":\"op1\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(slotCount()).isZero();
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));
    }
}
