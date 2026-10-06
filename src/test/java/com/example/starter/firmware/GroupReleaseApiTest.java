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
 * 合成场景、建组校验、PREPARE 回执、ABORTED、取消释放占用、激活与幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GroupReleaseApiTest {

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
        jdbc.update("DELETE FROM device_group_member");
        jdbc.update("DELETE FROM device_group_release");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void registerDevice(String requestId, String deviceId, String model, String version)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":1}
                """.formatted(requestId, deviceId, model, version)))
                .andExpect(status().isOk());
    }

    private long createGroup(String requestId, String operator, String model, String toVersion,
                             String... deviceIds) throws Exception {
        String devices = java.util.Arrays.stream(deviceIds)
                .map(d -> "\"" + d + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        MvcResult result = mockMvc.perform(post("/api/group-releases").contentType("application/json")
                        .content("""
                                {"requestId":"%s","operator":"%s","model":"%s","toVersion":"%s","deviceIds":[%s]}
                                """.formatted(requestId, operator, model, toVersion, devices)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.groupId");
    }

    private void prepare(long groupId, String deviceId, String requestId, String result, int expectStatus)
            throws Exception {
        mockMvc.perform(post("/api/group-releases/" + groupId + "/members/" + deviceId + "/prepare")
                        .contentType("application/json")
                        .content("""
                                {"requestId":"%s","operator":"op","expectedPrepareGeneration":1,"result":"%s"}
                                """.formatted(requestId, result)))
                .andExpect(status().is(expectStatus));
    }

    private String deviceVersion(String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/devices/" + deviceId))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),
                "$.currentVersion");
    }

    @Test
    void 合成场景_D1已准备D2未准备激活422不占键_D2准备后同键激活成功同代次v2() throws Exception {
        registerDevice("r-d1", "D1", "m1", "v1");
        registerDevice("r-d2", "D2", "m1", "v1");
        long groupId = createGroup("r-g1", "op", "m1", "v2", "D1", "D2");

        prepare(groupId, "D1", "r-p1", "PREPARED", 200);
        // PREPARE 只暂存不切版本
        assertThat(deviceVersion("D1")).isEqualTo("v1");
        mockMvc.perform(get("/api/group-releases/" + groupId))
                .andExpect(jsonPath("$.status").value("PREPARING"))
                .andExpect(jsonPath("$.members[0].status").value("PREPARED"))
                .andExpect(jsonPath("$.members[1].status").value("PENDING"));

        // 未齐备激活：422 且不占请求键
        mockMvc.perform(post("/api/group-releases/" + groupId + "/activate")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-act\",\"operator\":\"op\",\"expectedVersion\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_READY"));
        assertThat(deviceVersion("D1")).isEqualTo("v1");
        assertThat(deviceVersion("D2")).isEqualTo("v1");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'r-act'", Long.class))
                .isZero();

        // D2 准备后同键重试：激活成功，全组同代次 v2
        prepare(groupId, "D2", "r-p2", "PREPARED", 200);
        mockMvc.perform(post("/api/group-releases/" + groupId + "/activate")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-act\",\"operator\":\"op\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.activationGeneration").value(1))
                .andExpect(jsonPath("$.activatedAtUtc").isString())
                .andExpect(jsonPath("$.members[0].status").value("ACTIVATED"))
                .andExpect(jsonPath("$.members[0].activationGeneration").value(1))
                .andExpect(jsonPath("$.members[1].status").value("ACTIVATED"))
                .andExpect(jsonPath("$.members[1].activationGeneration").value(1));
        assertThat(deviceVersion("D1")).isEqualTo("v2");
        assertThat(deviceVersion("D2")).isEqualTo("v2");
        // 占用已释放，激活后不允许取消
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_group_member WHERE active_device IS NOT NULL", Long.class))
                .isZero();
        mockMvc.perform(post("/api/group-releases/" + groupId + "/cancel")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-c1\",\"operator\":\"op\",\"expectedVersion\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_ALREADY_ACTIVATED"));
    }

    @Test
    void 建组校验_台数边界_设备重复_型号不一致_设备不存在() throws Exception {
        registerDevice("r-d1", "D1", "m1", "v1");
        registerDevice("r-d2", "D2", "m1", "v1");
        registerDevice("r-d3", "D3", "m2", "v1");

        // 少于2台：400
        mockMvc.perform(post("/api/group-releases").contentType("application/json").content("""
                {"requestId":"r-b1","operator":"op","model":"m1","toVersion":"v2","deviceIds":["D1"]}
                """)).andExpect(status().isBadRequest());
        // 多于8台：400
        mockMvc.perform(post("/api/group-releases").contentType("application/json").content("""
                {"requestId":"r-b2","operator":"op","model":"m1","toVersion":"v2",
                "deviceIds":["D1","D2","D1","D2","D1","D2","D1","D2","D1"]}
                """)).andExpect(status().isBadRequest());
        // 组内设备重复：400
        mockMvc.perform(post("/api/group-releases").contentType("application/json").content("""
                {"requestId":"r-b3","operator":"op","model":"m1","toVersion":"v2","deviceIds":["D1","D1"]}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DEVICE"));
        // 设备不存在：404
        mockMvc.perform(post("/api/group-releases").contentType("application/json").content("""
                {"requestId":"r-b4","operator":"op","model":"m1","toVersion":"v2","deviceIds":["D1","ghost"]}
                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));
        // 型号不一致：400
        mockMvc.perform(post("/api/group-releases").contentType("application/json").content("""
                {"requestId":"r-b5","operator":"op","model":"m1","toVersion":"v2","deviceIds":["D1","D3"]}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MODEL_MISMATCH"));
        // 缺操作者：400
        mockMvc.perform(post("/api/group-releases").contentType("application/json").content("""
                {"requestId":"r-b6","model":"m1","toVersion":"v2","deviceIds":["D1","D2"]}
                """)).andExpect(status().isBadRequest());
        // 失败不占键：上述失败 requestId 均未入库（设备登记键不在此列）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record"
                + " WHERE request_id LIKE 'r-b%'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_group_release", Long.class)).isZero();
    }

    @Test
    void 设备不能同时属于两个活动组_终态后释放占用() throws Exception {
        registerDevice("r-d1", "D1", "m1", "v1");
        registerDevice("r-d2", "D2", "m1", "v1");
        registerDevice("r-d3", "D3", "m1", "v1");
        long g1 = createGroup("r-g1", "op", "m1", "v2", "D1", "D2");

        // D1 已属于活动组 g1：409
        mockMvc.perform(post("/api/group-releases").contentType("application/json").content("""
                {"requestId":"r-g2","operator":"op","model":"m1","toVersion":"v2","deviceIds":["D1","D3"]}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_IN_ACTIVE_GROUP"));

        // g1 取消后释放占用，D1 可加入新组
        mockMvc.perform(post("/api/group-releases/" + g1 + "/cancel")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-c1\",\"operator\":\"op\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.members[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.members[1].status").value("CANCELLED"));
        long g2 = createGroup("r-g3", "op", "m1", "v2", "D1", "D3");
        assertThat(g2).isNotEqualTo(g1);
    }

    @Test
    void 准备失败_整组ABORTED_已暂存成员不切版本_记录失败成员() throws Exception {
        registerDevice("r-d1", "D1", "m1", "v1");
        registerDevice("r-d2", "D2", "m1", "v1");
        registerDevice("r-d3", "D3", "m1", "v1");
        long groupId = createGroup("r-g1", "op", "m1", "v2", "D1", "D2", "D3");

        prepare(groupId, "D1", "r-p1", "PREPARED", 200);
        prepare(groupId, "D2", "r-p2", "FAILED", 200);

        // 整组 ABORTED，记录失败成员，已暂存的 D1 不切版本
        mockMvc.perform(get("/api/group-releases/" + groupId))
                .andExpect(jsonPath("$.status").value("ABORTED"))
                .andExpect(jsonPath("$.failedDeviceId").value("D2"))
                .andExpect(jsonPath("$.members[0].status").value("PREPARED"))
                .andExpect(jsonPath("$.members[1].status").value("FAILED"));
        assertThat(deviceVersion("D1")).isEqualTo("v1");
        assertThat(deviceVersion("D2")).isEqualTo("v1");
        assertThat(deviceVersion("D3")).isEqualTo("v1");

        // 终态后回执、激活、取消均被拒
        prepare(groupId, "D3", "r-p3", "PREPARED", 409);
        mockMvc.perform(post("/api/group-releases/" + groupId + "/activate")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-a1\",\"operator\":\"op\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_ABORTED"));
        mockMvc.perform(post("/api/group-releases/" + groupId + "/cancel")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-c1\",\"operator\":\"op\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_ABORTED"));

        // ABORTED 释放占用，同设备可建全新组并走通
        long g2 = createGroup("r-g2", "op", "m1", "v2", "D1", "D2");
        prepare(g2, "D1", "r-p4", "PREPARED", 200);
        prepare(g2, "D2", "r-p5", "PREPARED", 200);
        mockMvc.perform(post("/api/group-releases/" + g2 + "/activate")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-a2\",\"operator\":\"op\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"));
        assertThat(deviceVersion("D1")).isEqualTo("v2");
        assertThat(deviceVersion("D2")).isEqualTo("v2");
    }

    @Test
    void 旧组准备回执不影响新组代次() throws Exception {
        registerDevice("r-d1", "D1", "m1", "v1");
        registerDevice("r-d2", "D2", "m1", "v1");
        long g1 = createGroup("r-g1", "op", "m1", "v2", "D1", "D2");

        // 激活前取消并释放占用，同设备组建新组
        mockMvc.perform(post("/api/group-releases/" + g1 + "/cancel")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-c1\",\"operator\":\"op\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        long g2 = createGroup("r-g2", "op", "m1", "v2", "D1", "D2");

        // 旧组回执：组已终结 409，不影响新组
        prepare(g1, "D1", "r-p-old", "PREPARED", 409);
        mockMvc.perform(get("/api/group-releases/" + g2))
                .andExpect(jsonPath("$.status").value("PREPARING"))
                .andExpect(jsonPath("$.members[0].status").value("PENDING"))
                .andExpect(jsonPath("$.members[1].status").value("PENDING"));

        // 代次不匹配的回执：409，不影响新组
        mockMvc.perform(post("/api/group-releases/" + g2 + "/members/D1/prepare")
                        .contentType("application/json")
                        .content("""
                                {"requestId":"r-p-bad","operator":"op","expectedPrepareGeneration":99,"result":"PREPARED"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREPARE_GENERATION_MISMATCH"));
        mockMvc.perform(get("/api/group-releases/" + g2))
                .andExpect(jsonPath("$.members[0].status").value("PENDING"));

        // 新组正常走通
        prepare(g2, "D1", "r-p1", "PREPARED", 200);
        prepare(g2, "D2", "r-p2", "PREPARED", 200);
        mockMvc.perform(post("/api/group-releases/" + g2 + "/activate")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-a1\",\"operator\":\"op\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"));
    }

    @Test
    void 准备回执边界_重复同结果幂等_改结果409_成员不存在404_组不存在404() throws Exception {
        registerDevice("r-d1", "D1", "m1", "v1");
        registerDevice("r-d2", "D2", "m1", "v1");
        long groupId = createGroup("r-g1", "op", "m1", "v2", "D1", "D2");

        prepare(groupId, "D1", "r-p1", "PREPARED", 200);
        // 同结果重复回执（新 requestId）：幂等成功，不重复变更
        prepare(groupId, "D1", "r-p1-dup", "PREPARED", 200);
        // 改结果：409
        prepare(groupId, "D1", "r-p1-flip", "FAILED", 409);
        mockMvc.perform(get("/api/group-releases/" + groupId))
                .andExpect(jsonPath("$.status").value("PREPARING"))
                .andExpect(jsonPath("$.members[0].status").value("PREPARED"));

        // 设备不属于组：404
        prepare(groupId, "ghost", "r-p2", "PREPARED", 404);
        // 组不存在：404
        mockMvc.perform(post("/api/group-releases/9999/members/D1/prepare")
                        .contentType("application/json")
                        .content("""
                                {"requestId":"r-p3","operator":"op","expectedPrepareGeneration":1,"result":"PREPARED"}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/group-releases/9999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 组设备普通SUCCESS回执被拒_普通发布与FAILED回执不受影响() throws Exception {
        registerDevice("r-d1", "D1", "m1", "v1");
        registerDevice("r-d2", "D2", "m1", "v1");
        registerDevice("r-d3", "D3", "m1", "v1");
        // 普通灰度发布单覆盖全部三台设备
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-r1","model":"m1","fromVersion":"v1","toVersion":"v9","ratio":100}
                """)).andExpect(status().isOk());
        java.util.function.Function<String, Long> pullTask = deviceId -> {
            try {
                MvcResult r = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                                .contentType("application/json").content("{\"requestId\":\"r-pull-" + deviceId + "\"}"))
                        .andReturn();
                return idOf(r, "$.task.taskId");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        long t1 = pullTask.apply("D1");
        long t3 = pullTask.apply("D3");

        // D1、D2 进入活动组：D1 的普通 SUCCESS 回执被屏障拒绝
        long groupId = createGroup("r-g1", "op", "m1", "v2", "D1", "D2");
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_GROUP_BARRIER"));
        assertThat(deviceVersion("D1")).isEqualTo("v1");
        // FAILED 回执不受屏障限制
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc2\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        // 不在组内的 D3：普通发布不受影响
        mockMvc.perform(post("/api/tasks/" + t3 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc3\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        assertThat(deviceVersion("D3")).isEqualTo("v9");

        // 组取消后屏障解除，普通 SUCCESS 放行
        mockMvc.perform(post("/api/group-releases/" + groupId + "/cancel")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-c1\",\"operator\":\"op\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        long t2 = pullTask.apply("D2");
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc4\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        assertThat(deviceVersion("D2")).isEqualTo("v9");
    }

    @Test
    void 幂等_同键同参重放首次响应_异参409_激活只生效一次() throws Exception {
        registerDevice("r-d1", "D1", "m1", "v1");
        registerDevice("r-d2", "D2", "m1", "v1");

        MvcResult first = mockMvc.perform(post("/api/group-releases").contentType("application/json")
                        .content("""
                                {"requestId":"r-g1","operator":"op","model":"m1","toVersion":"v2","deviceIds":["D1","D2"]}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long groupId = idOf(first, "$.groupId");
        // 同键同参重放：响应一致，不重复建组
        mockMvc.perform(post("/api/group-releases").contentType("application/json")
                        .content("""
                                {"requestId":"r-g1","operator":"op","model":"m1","toVersion":"v2","deviceIds":["D1","D2"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(first.getResponse().getContentAsString()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_group_release", Long.class))
                .isEqualTo(1);
        // 同键异参（操作者不同）：409
        mockMvc.perform(post("/api/group-releases").contentType("application/json")
                        .content("""
                                {"requestId":"r-g1","operator":"other","model":"m1","toVersion":"v2","deviceIds":["D1","D2"]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        prepare(groupId, "D1", "r-p1", "PREPARED", 200);
        prepare(groupId, "D2", "r-p2", "PREPARED", 200);
        // 激活期望版本不匹配：409 且不占键
        mockMvc.perform(post("/api/group-releases/" + groupId + "/activate")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-a0\",\"operator\":\"op\",\"expectedVersion\":99}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_VERSION_CONFLICT"));
        MvcResult activated = mockMvc.perform(post("/api/group-releases/" + groupId + "/activate")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-a1\",\"operator\":\"op\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andReturn();
        // 同键重放激活：版本不再加，响应一致
        mockMvc.perform(post("/api/group-releases/" + groupId + "/activate")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-a1\",\"operator\":\"op\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(activated.getResponse().getContentAsString()));
        mockMvc.perform(get("/api/group-releases/" + groupId))
                .andExpect(jsonPath("$.version").value(2));
        // 新 requestId 再激活：409
        mockMvc.perform(post("/api/group-releases/" + groupId + "/activate")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-a2\",\"operator\":\"op\",\"expectedVersion\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_ALREADY_ACTIVATED"));
        // 组不存在：404
        mockMvc.perform(post("/api/group-releases/9999/activate")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-a3\",\"operator\":\"op\",\"expectedVersion\":1}"))
                .andExpect(status().isNotFound());
    }
}
