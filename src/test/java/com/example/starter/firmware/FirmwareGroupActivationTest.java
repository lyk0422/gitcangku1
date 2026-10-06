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
 * 固定设备组预备屏障 API 测试（H2 内存库，MODE=MySQL）：
 * 主流程、准备失败整组中止、取消释放占用、代次隔离、组占用冲突、参数校验、幂等与屏障。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareGroupActivationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM group_member");
        jdbc.update("DELETE FROM group_release");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private void register(String requestId, String deviceId, String model, String version) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":1}
                """.formatted(requestId, deviceId, model, version))).andExpect(status().isOk());
    }

    private MvcResult createGroup(String requestId, String model, String toVersion, String... deviceIds)
            throws Exception {
        String devices = java.util.Arrays.stream(deviceIds)
                .map(d -> "\"" + d + "\"")
                .reduce((a, b) -> a + "," + b).orElse("");
        return mockMvc.perform(post("/api/group-releases").contentType("application/json").content("""
                {"requestId":"%s","operator":"op","model":"%s","toVersion":"%s","deviceIds":[%s]}
                """.formatted(requestId, model, toVersion, devices))).andReturn();
    }

    private long groupIdOf(MvcResult result) throws Exception {
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.groupId");
        return id.longValue();
    }

    private MvcResult prepare(long groupId, String deviceId, String requestId, int generation, String result)
            throws Exception {
        return mockMvc.perform(post("/api/group-releases/" + groupId + "/members/" + deviceId + "/prepare")
                .contentType("application/json").content("""
                {"requestId":"%s","operator":"op","expectedGeneration":%d,"result":"%s"}
                """.formatted(requestId, generation, result))).andReturn();
    }

    private MvcResult activate(long groupId, String requestId, int generation) throws Exception {
        return mockMvc.perform(post("/api/group-releases/" + groupId + "/activate")
                .contentType("application/json").content("""
                {"requestId":"%s","operator":"op","expectedGeneration":%d}
                """.formatted(requestId, generation))).andReturn();
    }

    private MvcResult cancel(long groupId, String requestId) throws Exception {
        return mockMvc.perform(post("/api/group-releases/" + groupId + "/cancel")
                .contentType("application/json")
                .content("{\"requestId\":\"" + requestId + "\",\"operator\":\"op\"}")).andReturn();
    }

    @Test
    void 主流程_未齐备激活422不占键_齐备后同键激活成功_全组同代次切版本() throws Exception {
        register("rd1", "D1", "m1", "v1");
        register("rd2", "D2", "m1", "v1");
        long gid = groupIdOf(createGroup("g1", "m1", "v2", "D1", "D2"));

        // D1 已准备：仅暂存，不切版本
        prepare(gid, "D1", "p1", 1, "PREPARED");
        mockMvc.perform(get("/api/group-releases/" + gid))
                .andExpect(jsonPath("$.status").value("PREPARING"))
                .andExpect(jsonPath("$.members[0].status").value("PREPARED"));
        mockMvc.perform(get("/api/devices/D1")).andExpect(jsonPath("$.currentVersion").value("v1"));

        // D2 未准备：激活 422，不占请求键，两者仍 v1
        mockMvc.perform(post("/api/group-releases/" + gid + "/activate").contentType("application/json")
                        .content("{\"requestId\":\"act-1\",\"operator\":\"op\",\"expectedGeneration\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_READY"));
        mockMvc.perform(get("/api/devices/D1")).andExpect(jsonPath("$.currentVersion").value("v1"));
        mockMvc.perform(get("/api/devices/D2")).andExpect(jsonPath("$.currentVersion").value("v1"));

        // D2 准备后同键激活成功：全组同代次 v2
        prepare(gid, "D2", "p2", 1, "PREPARED");
        MvcResult activated = mockMvc.perform(post("/api/group-releases/" + gid + "/activate")
                        .contentType("application/json")
                        .content("{\"requestId\":\"act-1\",\"operator\":\"op\",\"expectedGeneration\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"))
                .andExpect(jsonPath("$.activationGeneration").value(1))
                .andExpect(jsonPath("$.successCount").value(2))
                .andExpect(jsonPath("$.failedCount").value(0))
                .andExpect(jsonPath("$.members[0].generation").value(1))
                .andExpect(jsonPath("$.members[1].generation").value(1))
                .andReturn();
        mockMvc.perform(get("/api/devices/D1")).andExpect(jsonPath("$.currentVersion").value("v2"));
        mockMvc.perform(get("/api/devices/D2")).andExpect(jsonPath("$.currentVersion").value("v2"));

        // 同键同参重放首次完整响应
        MvcResult replay = activate(gid, "act-1", 1);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(activated.getResponse().getContentAsString());

        // 激活后不允许取消
        mockMvc.perform(post("/api/group-releases/" + gid + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"cx1\",\"operator\":\"op\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_ALREADY_ACTIVATED"));
    }

    @Test
    void 准备失败_整组ABORTED_已暂存成员不切版本_记录失败成员() throws Exception {
        register("rd1", "D1", "m1", "v1");
        register("rd2", "D2", "m1", "v1");
        register("rd3", "D3", "m1", "v1");
        long gid = groupIdOf(createGroup("g1", "m1", "v2", "D1", "D2", "D3"));

        prepare(gid, "D1", "p1", 1, "PREPARED");
        mockMvc.perform(post("/api/group-releases/" + gid + "/members/D2/prepare")
                        .contentType("application/json")
                        .content("{\"requestId\":\"p2\",\"operator\":\"op\",\"expectedGeneration\":1,\"result\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ABORTED"))
                .andExpect(jsonPath("$.failedDevice").value("D2"))
                .andExpect(jsonPath("$.failedCount").value(1));

        // 已暂存的 D1 不切版本，全组仍 v1
        mockMvc.perform(get("/api/devices/D1")).andExpect(jsonPath("$.currentVersion").value("v1"));
        mockMvc.perform(get("/api/devices/D2")).andExpect(jsonPath("$.currentVersion").value("v1"));
        mockMvc.perform(get("/api/devices/D3")).andExpect(jsonPath("$.currentVersion").value("v1"));

        // 终态组不再受理回执与激活
        mockMvc.perform(post("/api/group-releases/" + gid + "/members/D3/prepare")
                        .contentType("application/json")
                        .content("{\"requestId\":\"p3\",\"operator\":\"op\",\"expectedGeneration\":1,\"result\":\"PREPARED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_PREPARING"));
        mockMvc.perform(post("/api/group-releases/" + gid + "/activate").contentType("application/json")
                        .content("{\"requestId\":\"a1\",\"operator\":\"op\",\"expectedGeneration\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_PREPARING"));

        // 取消已中止组：幂等返回当前状态
        mockMvc.perform(post("/api/group-releases/" + gid + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"c1\",\"operator\":\"op\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ABORTED"));
    }

    @Test
    void 取消_激活前释放组占用_重复取消幂等() throws Exception {
        register("rd1", "D1", "m1", "v1");
        register("rd2", "D2", "m1", "v1");
        long gid = groupIdOf(createGroup("g1", "m1", "v2", "D1", "D2"));

        mockMvc.perform(post("/api/group-releases/" + gid + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"c1\",\"operator\":\"op\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.failedCount").value(0));

        // 重复取消（新 requestId）幂等返回当前状态
        mockMvc.perform(post("/api/group-releases/" + gid + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"c2\",\"operator\":\"op\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 占用已释放：同设备可加入新组并成功激活
        long gid2 = groupIdOf(createGroup("g2", "m1", "v2", "D1", "D2"));
        prepare(gid2, "D1", "p1", 1, "PREPARED");
        prepare(gid2, "D2", "p2", 1, "PREPARED");
        mockMvc.perform(post("/api/group-releases/" + gid2 + "/activate").contentType("application/json")
                        .content("{\"requestId\":\"a2\",\"operator\":\"op\",\"expectedGeneration\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"));
        mockMvc.perform(get("/api/devices/D1")).andExpect(jsonPath("$.currentVersion").value("v2"));
    }

    @Test
    void 代次隔离_旧组回执与错误代次不影响新组() throws Exception {
        register("rd1", "D1", "m1", "v1");
        register("rd2", "D2", "m1", "v1");
        long g1 = groupIdOf(createGroup("g1", "m1", "v2", "D1", "D2"));
        prepare(g1, "D1", "p1", 1, "PREPARED");
        prepare(g1, "D2", "p2", 1, "FAILED"); // 整组 ABORTED，占用释放

        long g2 = groupIdOf(createGroup("g2", "m1", "v2", "D1", "D2"));

        // 旧组回执：409，不影响新组
        mockMvc.perform(post("/api/group-releases/" + g1 + "/members/D1/prepare")
                        .contentType("application/json")
                        .content("{\"requestId\":\"p3\",\"operator\":\"op\",\"expectedGeneration\":1,\"result\":\"PREPARED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_PREPARING"));

        // 错误代次回执：409 STALE_GENERATION
        mockMvc.perform(post("/api/group-releases/" + g2 + "/members/D1/prepare")
                        .contentType("application/json")
                        .content("{\"requestId\":\"p4\",\"operator\":\"op\",\"expectedGeneration\":99,\"result\":\"PREPARED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_GENERATION"));

        // 新组成员未被污染，仍为 PENDING，可正常走完激活
        mockMvc.perform(get("/api/group-releases/" + g2))
                .andExpect(jsonPath("$.members[0].status").value("PENDING"))
                .andExpect(jsonPath("$.members[1].status").value("PENDING"));
        prepare(g2, "D1", "p5", 1, "PREPARED");
        prepare(g2, "D2", "p6", 1, "PREPARED");
        mockMvc.perform(post("/api/group-releases/" + g2 + "/activate").contentType("application/json")
                        .content("{\"requestId\":\"a2\",\"operator\":\"op\",\"expectedGeneration\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"));
        mockMvc.perform(get("/api/devices/D1")).andExpect(jsonPath("$.currentVersion").value("v2"));
        mockMvc.perform(get("/api/devices/D2")).andExpect(jsonPath("$.currentVersion").value("v2"));
    }

    @Test
    void 组占用_同设备不能同时属于两个活动组() throws Exception {
        register("rd1", "D1", "m1", "v1");
        register("rd2", "D2", "m1", "v1");
        register("rd3", "D3", "m1", "v1");
        register("rd4", "D4", "m1", "v1");
        createGroup("g1", "m1", "v2", "D1", "D2");

        mockMvc.perform(post("/api/group-releases").contentType("application/json").content("""
                {"requestId":"g2","operator":"op","model":"m1","toVersion":"v2","deviceIds":["D1","D3"]}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_IN_ACTIVE_GROUP"));

        // 不相交设备可建组
        mockMvc.perform(post("/api/group-releases").contentType("application/json").content("""
                {"requestId":"g3","operator":"op","model":"m1","toVersion":"v2","deviceIds":["D3","D4"]}
                """))
                .andExpect(status().isOk());
    }

    @Test
    void 校验_成员数量_重复成员_型号不一致_设备不存在() throws Exception {
        register("rd1", "D1", "m1", "v1");
        register("rd2", "D2", "m2", "v1");

        // 少于 2 台：400
        mockMvc.perform(post("/api/group-releases").contentType("application/json").content("""
                {"requestId":"g1","operator":"op","model":"m1","toVersion":"v2","deviceIds":["D1"]}
                """)).andExpect(status().isBadRequest());

        // 多于 8 台：400
        mockMvc.perform(post("/api/group-releases").contentType("application/json").content("""
                {"requestId":"g2","operator":"op","model":"m1","toVersion":"v2",
                 "deviceIds":["A","B","C","D","E","F","G","H","I"]}
                """)).andExpect(status().isBadRequest());

        // 重复成员：400
        mockMvc.perform(post("/api/group-releases").contentType("application/json").content("""
                {"requestId":"g3","operator":"op","model":"m1","toVersion":"v2","deviceIds":["D1","D1"]}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DEVICE"));

        // 型号不一致：400
        mockMvc.perform(post("/api/group-releases").contentType("application/json").content("""
                {"requestId":"g4","operator":"op","model":"m1","toVersion":"v2","deviceIds":["D1","D2"]}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MODEL_MISMATCH"));

        // 设备不存在：404
        mockMvc.perform(post("/api/group-releases").contentType("application/json").content("""
                {"requestId":"g5","operator":"op","model":"m1","toVersion":"v2","deviceIds":["D1","ghost"]}
                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));
    }

    @Test
    void 幂等_同键同参重放_异参409_失败不占键() throws Exception {
        register("rd1", "D1", "m1", "v1");
        register("rd2", "D2", "m1", "v1");
        register("rd3", "D3", "m1", "v1");
        register("rd4", "D4", "m1", "v1");

        String first = createGroup("k1", "m1", "v2", "D1", "D2").getResponse().getContentAsString();
        String replay = createGroup("k1", "m1", "v2", "D1", "D2").getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM group_release", Long.class)).isEqualTo(1);

        // 同键异参：409
        mockMvc.perform(post("/api/group-releases").contentType("application/json").content("""
                {"requestId":"k1","operator":"op","model":"m1","toVersion":"v3","deviceIds":["D1","D2"]}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：先 404，再同键成功
        mockMvc.perform(post("/api/group-releases").contentType("application/json").content("""
                {"requestId":"k2","operator":"op","model":"m1","toVersion":"v2","deviceIds":["D3","ghost"]}
                """)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/group-releases").contentType("application/json").content("""
                {"requestId":"k2","operator":"op","model":"m1","toVersion":"v2","deviceIds":["D3","D4"]}
                """)).andExpect(status().isOk());

        // 准备回执幂等：同键同参重放，同键异参 409（使用未被占用的设备）
        register("rd5", "D5", "m1", "v1");
        register("rd6", "D6", "m1", "v1");
        long gid = groupIdOf(createGroup("g9", "m1", "v2", "D5", "D6"));
        String p1 = prepare(gid, "D5", "pk1", 1, "PREPARED").getResponse().getContentAsString();
        String p1Replay = prepare(gid, "D5", "pk1", 1, "PREPARED").getResponse().getContentAsString();
        assertThat(p1Replay).isEqualTo(p1);
        mockMvc.perform(post("/api/group-releases/" + gid + "/members/D5/prepare")
                        .contentType("application/json")
                        .content("{\"requestId\":\"pk1\",\"operator\":\"op\",\"expectedGeneration\":1,\"result\":\"FAILED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void 回执_重复暂存幂等_暂存后改失败409_普通SUCCESS结果400() throws Exception {
        register("rd1", "D1", "m1", "v1");
        register("rd2", "D2", "m1", "v1");
        register("rd3", "D3", "m1", "v1");
        long gid = groupIdOf(createGroup("g1", "m1", "v2", "D1", "D2"));

        prepare(gid, "D1", "p1", 1, "PREPARED");
        // 同结果重复（新 requestId）：幂等返回当前状态
        mockMvc.perform(post("/api/group-releases/" + gid + "/members/D1/prepare")
                        .contentType("application/json")
                        .content("{\"requestId\":\"p2\",\"operator\":\"op\",\"expectedGeneration\":1,\"result\":\"PREPARED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].status").value("PREPARED"));
        // 暂存后改失败：409
        mockMvc.perform(post("/api/group-releases/" + gid + "/members/D1/prepare")
                        .contentType("application/json")
                        .content("{\"requestId\":\"p3\",\"operator\":\"op\",\"expectedGeneration\":1,\"result\":\"FAILED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREPARE_RESULT_CONFLICT"));
        // 组回执不接受普通 SUCCESS：400
        mockMvc.perform(post("/api/group-releases/" + gid + "/members/D2/prepare")
                        .contentType("application/json")
                        .content("{\"requestId\":\"p4\",\"operator\":\"op\",\"expectedGeneration\":1,\"result\":\"SUCCESS\"}"))
                .andExpect(status().isBadRequest());
        // 非成员与不存在组：404
        mockMvc.perform(post("/api/group-releases/" + gid + "/members/D3/prepare")
                        .contentType("application/json")
                        .content("{\"requestId\":\"p5\",\"operator\":\"op\",\"expectedGeneration\":1,\"result\":\"PREPARED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
        mockMvc.perform(post("/api/group-releases/9999/members/D1/prepare")
                        .contentType("application/json")
                        .content("{\"requestId\":\"p6\",\"operator\":\"op\",\"expectedGeneration\":1,\"result\":\"PREPARED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
    }

    @Test
    void 屏障_组设备普通SUCCESS回执被拒_普通发布不受影响() throws Exception {
        register("rd1", "D1", "m1", "1.0.0");
        register("rd2", "D2", "m1", "1.0.0");
        register("rd3", "D3", "m1", "1.0.0");
        // 普通发布
        MvcResult release = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r1","model":"m1","fromVersion":"1.0.0","toVersion":"9.9.9","ratio":100}
                """)).andExpect(status().isOk()).andReturn();
        long releaseId = com.jayway.jsonpath.JsonPath
                .<Number>read(release.getResponse().getContentAsString(), "$.releaseId").longValue();
        // 组发布占用 D1、D2
        long gid = groupIdOf(createGroup("g1", "m1", "v2", "D1", "D2"));

        // 普通设备 D3：SUCCESS 回执正常
        MvcResult pull3 = mockMvc.perform(post("/api/devices/D3/pull").contentType("application/json")
                .content("{\"requestId\":\"pl3\"}")).andReturn();
        long t3 = com.jayway.jsonpath.JsonPath
                .<Number>read(pull3.getResponse().getContentAsString(), "$.task.taskId").longValue();
        mockMvc.perform(post("/api/tasks/" + t3 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc3\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/D3")).andExpect(jsonPath("$.currentVersion").value("9.9.9"));

        // 组设备 D1：普通 SUCCESS 回执被屏障拒绝，任务与版本不变
        MvcResult pull1 = mockMvc.perform(post("/api/devices/D1/pull").contentType("application/json")
                .content("{\"requestId\":\"pl1\"}")).andReturn();
        long t1 = com.jayway.jsonpath.JsonPath
                .<Number>read(pull1.getResponse().getContentAsString(), "$.task.taskId").longValue();
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_GROUP_BARRIER"));
        mockMvc.perform(get("/api/devices/D1")).andExpect(jsonPath("$.currentVersion").value("1.0.0"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks").param("status", "PENDING"))
                .andExpect(jsonPath("$.tasks.length()").value(1));

        // FAILED 回执不受屏障限制
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc2\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        // 组取消后屏障解除：D2 普通 SUCCESS 回执恢复受理
        cancel(gid, "cx1");
        MvcResult pull2 = mockMvc.perform(post("/api/devices/D2/pull").contentType("application/json")
                .content("{\"requestId\":\"pl2\"}")).andReturn();
        long t2 = com.jayway.jsonpath.JsonPath
                .<Number>read(pull2.getResponse().getContentAsString(), "$.task.taskId").longValue();
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc4\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/D2")).andExpect(jsonPath("$.currentVersion").value("9.9.9"));
    }
}
