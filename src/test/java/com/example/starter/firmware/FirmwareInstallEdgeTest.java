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
 * 安装握手边界测试（H2 内存库，MODE=MySQL）：未开始直接取消、旧入口不绕过槽、
 * 幂等重放与异参 409、失败不占键、参数校验、取消不计失败率样本。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareInstallEdgeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
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

    private void registerDevice(String requestId, String deviceId, String model, int bucket) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"1.0.0","bucketNo":%d}
                """.formatted(requestId, deviceId, model, bucket)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, int ratio) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":%d}
                """.formatted(requestId, model, ratio)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private MvcResult createInstallTask(String requestId, long releaseId, String deviceId, int expectedVersion)
            throws Exception {
        return mockMvc.perform(post("/api/releases/" + releaseId + "/install-tasks")
                        .contentType("application/json").content("""
                        {"requestId":"%s","operator":"op","deviceId":"%s","expectedVersion":%d}
                        """.formatted(requestId, deviceId, expectedVersion)))
                .andReturn();
    }

    private MvcResult startTask(String requestId, long taskId, int expectedVersion) throws Exception {
        return mockMvc.perform(post("/api/tasks/" + taskId + "/start").contentType("application/json")
                        .content("""
                        {"requestId":"%s","operator":"op","expectedVersion":%d}
                        """.formatted(requestId, expectedVersion)))
                .andReturn();
    }

    private MvcResult cancelRequest(String requestId, long taskId, int expectedVersion) throws Exception {
        return mockMvc.perform(post("/api/tasks/" + taskId + "/cancel-request").contentType("application/json")
                        .content("""
                        {"requestId":"%s","operator":"op","expectedVersion":%d}
                        """.formatted(requestId, expectedVersion)))
                .andReturn();
    }

    private MvcResult stopConfirm(String requestId, long taskId, int expectedVersion, int generation)
            throws Exception {
        return mockMvc.perform(post("/api/tasks/" + taskId + "/stop-confirm").contentType("application/json")
                        .content("""
                        {"requestId":"%s","operator":"op","expectedVersion":%d,"generation":%d}
                        """.formatted(requestId, expectedVersion, generation)))
                .andReturn();
    }

    @Test
    void 未开始任务可直接取消_终结态不能取消() throws Exception {
        registerDevice("r1", "d1", "m1", 1);
        long releaseId = createRelease("r2", "m1", 100);
        long installTask = idOf(createInstallTask("r3", releaseId, "d1", 1), "$.taskId");

        // PENDING 安装任务直接取消，从不占槽
        MvcResult cancelled = cancelRequest("r4", installTask, 1);
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
        assertThat(cancelled.getResponse().getContentAsString()).contains("\"status\":\"CANCELLED\"");
        assertThat(jdbc.queryForObject("SELECT device_slot FROM rollout_task WHERE id = ?",
                String.class, installTask)).isNull();
        assertThat(startTask("r5", installTask, 1).getResponse().getStatus()).isEqualTo(409);
        assertThat(stopConfirm("r6", installTask, 1, 1).getResponse().getStatus()).isEqualTo(409);

        // PENDING 普通任务也可直接取消
        registerDevice("r7", "d2", "m1", 2);
        MvcResult pull = mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r8\"}"))
                .andExpect(status().isOk()).andReturn();
        long plainTask = idOf(pull, "$.task.taskId");
        assertThat(cancelRequest("r9", plainTask, 1).getResponse().getContentAsString())
                .contains("\"status\":\"CANCELLED\"");

        // 已终结任务不能取消
        registerDevice("r10", "d3", "m1", 3);
        MvcResult pull2 = mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"r11\"}"))
                .andReturn();
        long doneTask = idOf(pull2, "$.task.taskId");
        mockMvc.perform(post("/api/tasks/" + doneTask + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r12\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        MvcResult notCancellable = cancelRequest("r13", doneTask, 1);
        assertThat(notCancellable.getResponse().getStatus()).isEqualTo(409);
        assertThat(notCancellable.getResponse().getContentAsString()).contains("TASK_NOT_CANCELLABLE");
    }

    @Test
    void 槽占用_旧拉取入口不绕过槽_停止确认后恢复() throws Exception {
        registerDevice("r1", "d1", "m1", 1);
        long v1 = createRelease("r2", "m1", 100);
        long t1 = idOf(createInstallTask("r3", v1, "d1", 1), "$.taskId");
        assertThat(startTask("r4", t1, 1).getResponse().getStatus()).isEqualTo(200);
        mockMvc.perform(post("/api/releases/" + v1 + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r5\"}"))
                .andExpect(status().isOk());

        long v2 = createRelease("r6", "m1", 100);
        // 旧拉取入口不得绕过槽：槽被占用时 422
        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r7\"}"))
                .andReturn();
        assertThat(pull.getResponse().getStatus()).isEqualTo(422);
        assertThat(pull.getResponse().getContentAsString()).contains("SLOT_OCCUPIED");

        // 新类型任务可创建（PENDING 不占槽）但开始被旧槽阻塞
        long t2 = idOf(createInstallTask("r8", v2, "d1", 1), "$.taskId");
        assertThat(startTask("r9", t2, 1).getResponse().getStatus()).isEqualTo(422);

        // 停止确认释放槽后，拉取与开始恢复
        assertThat(stopConfirm("r10", t1, 1, 1).getResponse().getStatus()).isEqualTo(200);
        assertThat(startTask("r11", t2, 1).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 幂等_重放首次完整响应_异参409_失败不占键() throws Exception {
        registerDevice("r1", "d1", "m1", 1);
        long releaseId = createRelease("r2", "m1", 100);
        long taskId = idOf(createInstallTask("r3", releaseId, "d1", 1), "$.taskId");

        // 失败不占键：版本冲突 409 后同 requestId 可成功
        assertThat(startTask("r4", taskId, 99).getResponse().getStatus()).isEqualTo(409);
        MvcResult started = mockMvc.perform(post("/api/tasks/" + taskId + "/start")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"operator\":\"op\",\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放首次完整响应
        MvcResult replay = mockMvc.perform(post("/api/tasks/" + taskId + "/start")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"operator\":\"op\",\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(started.getResponse().getContentAsString());
        // 同键异参 409
        mockMvc.perform(post("/api/tasks/" + taskId + "/start").contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"operator\":\"other\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 停止确认重放：任务只终结一次
        assertThat(cancelRequest("r5", taskId, 1).getResponse().getStatus()).isEqualTo(200);
        MvcResult confirmed = stopConfirm("r6", taskId, 1, 1);
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        MvcResult confirmedReplay = stopConfirm("r6", taskId, 1, 1);
        assertThat(confirmedReplay.getResponse().getStatus()).isEqualTo(200);
        assertThat(confirmedReplay.getResponse().getContentAsString())
                .isEqualTo(confirmed.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE id = ? AND status = 'CANCELLED'",
                Long.class, taskId)).isEqualTo(1);
    }

    @Test
    void 参数校验_缺操作者或代次为400() throws Exception {
        registerDevice("r1", "d1", "m1", 1);
        long releaseId = createRelease("r2", "m1", 100);
        long taskId = idOf(createInstallTask("r3", releaseId, "d1", 1), "$.taskId");

        mockMvc.perform(post("/api/tasks/" + taskId + "/start").contentType("application/json")
                        .content("{\"requestId\":\"v1\",\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/tasks/" + taskId + "/stop-confirm").contentType("application/json")
                        .content("{\"requestId\":\"v2\",\"operator\":\"op\",\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/releases/" + releaseId + "/install-tasks")
                        .contentType("application/json")
                        .content("{\"requestId\":\"v3\",\"operator\":\"op\",\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 取消不计失败率样本_发布不因此暂停() throws Exception {
        registerDevice("r1", "d1", "m1", 1);
        registerDevice("r2", "d2", "m1", 2);
        MvcResult release = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r3","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "sampleFloor":2,"failureThresholdPercent":50}
                """)).andExpect(status().isOk()).andReturn();
        long releaseId = idOf(release, "$.releaseId");
        long t1 = idOf(createInstallTask("r4", releaseId, "d1", 1), "$.taskId");
        long t2 = idOf(createInstallTask("r5", releaseId, "d2", 1), "$.taskId");
        assertThat(startTask("r6", t1, 1).getResponse().getStatus()).isEqualTo(200);
        assertThat(startTask("r7", t2, 1).getResponse().getStatus()).isEqualTo(200);

        // 发布取消：两个安装中任务转待确认，确认停止后样本仍为零、不产生暂停记录
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r8\"}"))
                .andExpect(status().isOk());
        assertThat(stopConfirm("r9", t1, 1, 1).getResponse().getStatus()).isEqualTo(200);
        assertThat(stopConfirm("r10", t2, 1, 1).getResponse().getStatus()).isEqualTo(200);

        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0));
        mockMvc.perform(get("/api/releases/" + releaseId + "/history"))
                .andExpect(jsonPath("$.pauses.length()").value(0));
    }
}
