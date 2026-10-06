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
 * 安装握手任务 API 测试（H2 内存库，MODE=MySQL）：
 * 显式开始占槽、仅 INSTALLING 接受回执、发布取消转待确认、同代次停止确认释放槽。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareInstallHandshakeTest {

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

    private void registerDevice(String requestId, String deviceId, String model, String version, int bucket)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d}
                """.formatted(requestId, deviceId, model, version, bucket)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, String from, String to, int ratio)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"%s","toVersion":"%s","ratio":%d}
                """.formatted(requestId, model, from, to, ratio)))
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

    private MvcResult receipt(String requestId, long taskId, String result) throws Exception {
        return mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"" + requestId + "\",\"result\":\"" + result + "\"}"))
                .andReturn();
    }

    private String slotOf(long taskId) {
        return jdbc.queryForObject("SELECT device_slot FROM rollout_task WHERE id = ?", String.class, taskId);
    }

    @Test
    void 安装任务主流程_显式开始占槽_成功回执释放槽并更新版本() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        long releaseId = createRelease("r2", "m1", "1.0.0", "2.0.0", 100);

        MvcResult created = createInstallTask("r3", releaseId, "d1", 1);
        long taskId = idOf(created, "$.taskId");
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        assertThat(created.getResponse().getContentAsString())
                .contains("\"status\":\"PENDING\"").contains("\"taskType\":\"INSTALL\"");

        // 未开始不受理普通回执
        MvcResult early = receipt("r4", taskId, "SUCCESS");
        assertThat(early.getResponse().getStatus()).isEqualTo(409);
        assertThat(early.getResponse().getContentAsString()).contains("TASK_NOT_STARTED");

        // 显式开始：INSTALLING 并占槽；重复开始幂等返回当前状态
        MvcResult started = startTask("r5", taskId, 1);
        assertThat(started.getResponse().getStatus()).isEqualTo(200);
        assertThat(started.getResponse().getContentAsString()).contains("\"status\":\"INSTALLING\"");
        assertThat(slotOf(taskId)).isEqualTo("d1");
        MvcResult startedAgain = startTask("r6", taskId, 1);
        assertThat(startedAgain.getResponse().getStatus()).isEqualTo(200);
        assertThat(startedAgain.getResponse().getContentAsString()).contains("\"status\":\"INSTALLING\"");

        // INSTALLING 接受成功回执：释放槽并更新设备版本
        MvcResult done = receipt("r7", taskId, "SUCCESS");
        assertThat(done.getResponse().getStatus()).isEqualTo(200);
        assertThat(done.getResponse().getContentAsString()).contains("\"status\":\"SUCCESS\"");
        assertThat(slotOf(taskId)).isNull();
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // 同结果重复回执成功，改结果 409
        assertThat(receipt("r8", taskId, "SUCCESS").getResponse().getStatus()).isEqualTo(200);
        MvcResult changed = receipt("r9", taskId, "FAILED");
        assertThat(changed.getResponse().getStatus()).isEqualTo(409);
        assertThat(changed.getResponse().getContentAsString()).contains("RECEIPT_RESULT_CONFLICT");

        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(1))
                .andExpect(jsonPath("$.roundFailed").value(0));
    }

    @Test
    void 合成场景_安装中取消发布_同代次停止确认后才能开始新任务() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        long v1 = createRelease("r2", "m1", "1.0.0", "2.0.0", 100);
        long t1 = idOf(createInstallTask("r3", v1, "d1", 1), "$.taskId");
        assertThat(startTask("r4", t1, 1).getResponse().getStatus()).isEqualTo(200);

        // 安装中取消发布：任务转待确认、仍占槽、设备版本不变
        mockMvc.perform(post("/api/releases/" + v1 + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/releases/" + v1 + "/tasks"))
                .andExpect(jsonPath("$.tasks[0].status").value("CANCEL_REQUESTED"))
                .andExpect(jsonPath("$.tasks[0].cancelGeneration").value(1));
        assertThat(slotOf(t1)).isEqualTo("d1");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // 待确认任务拒绝安装回执
        MvcResult rejected = receipt("r6", t1, "SUCCESS");
        assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
        assertThat(rejected.getResponse().getContentAsString()).contains("TASK_CANCEL_REQUESTED");

        // 新发布可建任务，但旧槽未释放不能开始
        long v2 = createRelease("r7", "m1", "1.0.0", "3.0.0", 100);
        long t2 = idOf(createInstallTask("r8", v2, "d1", 1), "$.taskId");
        MvcResult blocked = startTask("r9", t2, 1);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(422);
        assertThat(blocked.getResponse().getContentAsString()).contains("SLOT_OCCUPIED");

        // 错误代次停止确认 409；正确代次确认后释放槽
        assertThat(stopConfirm("r10", t1, 1, 9).getResponse().getStatus()).isEqualTo(409);
        MvcResult confirmed = stopConfirm("r11", t1, 1, 1);
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        assertThat(confirmed.getResponse().getContentAsString()).contains("\"status\":\"CANCELLED\"");
        assertThat(slotOf(t1)).isNull();

        // 旧 SUCCESS 回执仍拒绝，设备不切换版本
        MvcResult late = receipt("r12", t1, "SUCCESS");
        assertThat(late.getResponse().getStatus()).isEqualTo(409);
        assertThat(late.getResponse().getContentAsString()).contains("TASK_CANCELLED");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // 槽释放后新任务可开始并完成
        assertThat(startTask("r13", t2, 1).getResponse().getStatus()).isEqualTo(200);
        assertThat(receipt("r14", t2, "SUCCESS").getResponse().getStatus()).isEqualTo(200);
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("3.0.0"));
    }

    @Test
    void 取消请求_生成取消代次_旧代次停止确认失效() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        long releaseId = createRelease("r2", "m1", "1.0.0", "2.0.0", 100);
        long taskId = idOf(createInstallTask("r3", releaseId, "d1", 1), "$.taskId");
        assertThat(startTask("r4", taskId, 1).getResponse().getStatus()).isEqualTo(200);

        // 任务级取消请求：INSTALLING 转 CANCEL_REQUESTED，代次 1，保留槽
        MvcResult cancel1 = cancelRequest("r5", taskId, 1);
        assertThat(cancel1.getResponse().getStatus()).isEqualTo(200);
        assertThat(cancel1.getResponse().getContentAsString())
                .contains("\"status\":\"CANCEL_REQUESTED\"").contains("\"cancelGeneration\":1");
        assertThat(slotOf(taskId)).isEqualTo("d1");

        // 待确认期间拒绝普通回执
        assertThat(receipt("r6", taskId, "FAILED").getResponse().getStatus()).isEqualTo(409);

        // 再次取消请求：代次递增，旧代次确认失效
        MvcResult cancel2 = cancelRequest("r7", taskId, 1);
        assertThat(cancel2.getResponse().getContentAsString()).contains("\"cancelGeneration\":2");
        MvcResult stale = stopConfirm("r8", taskId, 1, 1);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("CANCEL_GENERATION_MISMATCH");

        // 同代次确认：CANCELLED 并释放槽，取消不计失败率样本
        assertThat(stopConfirm("r9", taskId, 1, 2).getResponse().getStatus()).isEqualTo(200);
        assertThat(slotOf(taskId)).isNull();
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0));
    }
}
