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
 * 配置迁移 API 测试（H2 内存库，MODE=MySQL）：领取冻结快照与预演、SUCCESS 同时切换固件与配置、
 * FAILED/取消不改变二者、本地配置修改、幂等重放与规则校验。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareConfigMigrationTest {

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
        jdbc.update("DELETE FROM device_config");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void registerDevice(String requestId, String deviceId, String configJson) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"%s","deviceId":"%s","model":"m1","currentVersion":"1.0.0","bucketNo":1,"config":%s}
                        """.formatted(requestId, deviceId, configJson)))
                .andExpect(status().isOk());
    }

    private long createReleaseWithRules(String requestId, String rulesJson) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"%s","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,"migrationRules":%s}
                        """.formatted(requestId, rulesJson)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private long pullTask(String deviceId, String requestId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json")
                        .content("{\"requestId\":\"" + requestId + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.task.taskId");
    }

    @Test
    void 主流程_领取冻结快照_成功回执同时切换固件与配置() throws Exception {
        registerDevice("r1", "d1", "{\"a\":3}");
        long releaseId = createReleaseWithRules("r2",
                "[{\"type\":\"RENAME\",\"key\":\"a\",\"targetKey\":\"b\"},{\"type\":\"MULTIPLY\",\"key\":\"b\",\"factor\":2}]");

        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.configVersionBefore").value(1))
                .andExpect(jsonPath("$.task.configBefore.a").value(3))
                .andExpect(jsonPath("$.task.configAfter.b").value(6))
                .andExpect(jsonPath("$.task.configAfter.a").doesNotExist())
                .andReturn();
        long taskId = idOf(pull, "$.task.taskId");

        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // 固件与配置同时切换：b=6 且无 a，配置版本推进到 2
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"))
                .andExpect(jsonPath("$.configVersion").value(2))
                .andExpect(jsonPath("$.config.b").value(6))
                .andExpect(jsonPath("$.config.a").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_config WHERE device_id = 'd1'",
                Long.class)).isEqualTo(1);

        // 旧成功回放不重复迁移：同结果重复回执成功，配置版本不再推进
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.configVersion").value(2))
                .andExpect(jsonPath("$.config.b").value(6));

        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(1));
    }

    @Test
    void 预演失败_目标键已存在_422不创建任务且配置不变() throws Exception {
        registerDevice("r1", "d1", "{\"a\":3,\"b\":1}");
        long releaseId = createReleaseWithRules("r2", "[{\"type\":\"RENAME\",\"key\":\"a\",\"targetKey\":\"b\"}]");

        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r3\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONFIG_TARGET_EXISTS"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"))
                .andExpect(jsonPath("$.configVersion").value(1))
                .andExpect(jsonPath("$.config.a").value(3))
                .andExpect(jsonPath("$.config.b").value(1));

        // 失败不占键：取消旧发布单、改为无规则发布单后，同一 requestId 拉取成功
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r9\"}"))
                .andExpect(status().isOk());
        createReleaseWithRules("r10", "[]");
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.configBefore.a").value(3))
                .andExpect(jsonPath("$.task.configAfter.a").value(3));
    }

    @Test
    void 预演失败_乘法越界_422不创建任务() throws Exception {
        registerDevice("r1", "d1", "{\"a\":500000}");
        createReleaseWithRules("r2", "[{\"type\":\"MULTIPLY\",\"key\":\"a\",\"factor\":3}]");

        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r3\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONFIG_VALUE_OUT_OF_RANGE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.config.a").value(500000))
                .andExpect(jsonPath("$.configVersion").value(1));
    }

    @Test
    void 失败回执_固件与配置均不变_失败原因首次冻结() throws Exception {
        registerDevice("r1", "d1", "{\"a\":3}");
        long releaseId = createReleaseWithRules("r2",
                "[{\"type\":\"RENAME\",\"key\":\"a\",\"targetKey\":\"b\"},{\"type\":\"MULTIPLY\",\"key\":\"b\",\"factor\":2}]");
        long taskId = pullTask("d1", "r3");

        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"result\":\"FAILED\",\"reason\":\"烧录超时\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("烧录超时"));

        // 固件与配置均不变
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"))
                .andExpect(jsonPath("$.configVersion").value(1))
                .andExpect(jsonPath("$.config.a").value(3))
                .andExpect(jsonPath("$.config.b").doesNotExist());

        // 同结果重复回执不覆盖首次冻结的失败原因
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"result\":\"FAILED\",\"reason\":\"别的原因\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureReason").value("烧录超时"));

        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.roundSuccess").value(0));
    }

    @Test
    void 取消_配置与固件不变_后到回执409() throws Exception {
        registerDevice("r1", "d1", "{\"a\":3}");
        long releaseId = createReleaseWithRules("r2", "[{\"type\":\"RENAME\",\"key\":\"a\",\"targetKey\":\"b\"}]");
        long taskId = pullTask("d1", "r3");

        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));

        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"))
                .andExpect(jsonPath("$.configVersion").value(1))
                .andExpect(jsonPath("$.config.a").value(3));
    }

    @Test
    void 本地配置修改_版本校验_进行中任务409_键数上限() throws Exception {
        registerDevice("r1", "d1", "{\"a\":3}");

        // 期望版本不符：409
        mockMvc.perform(post("/api/devices/d1/config").contentType("application/json").content("""
                        {"requestId":"c1","operator":"op-1","expectedConfigVersion":9,"entries":{"x":1}}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIG_VERSION_CONFLICT"));

        // 无进行中任务：成功并推进配置版本
        mockMvc.perform(post("/api/devices/d1/config").contentType("application/json").content("""
                        {"requestId":"c2","operator":"op-1","expectedConfigVersion":1,"entries":{"x":7,"a":4}}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configVersion").value(2))
                .andExpect(jsonPath("$.config.a").value(4))
                .andExpect(jsonPath("$.config.x").value(7));

        // 有进行中任务：409，配置不变
        createReleaseWithRules("r2", "[{\"type\":\"RENAME\",\"key\":\"a\",\"targetKey\":\"b\"}]");
        pullTask("d1", "r3");
        mockMvc.perform(post("/api/devices/d1/config").contentType("application/json").content("""
                        {"requestId":"c3","operator":"op-1","expectedConfigVersion":2,"entries":{"y":1}}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_IN_PROGRESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.configVersion").value(2))
                .andExpect(jsonPath("$.config.y").doesNotExist());

        // 合并后键数超过 10：422
        registerDevice("r4", "d2", "{\"k0\":0,\"k1\":1,\"k2\":2,\"k3\":3,\"k4\":4,\"k5\":5,\"k6\":6,\"k7\":7,\"k8\":8}");
        mockMvc.perform(post("/api/devices/d2/config").contentType("application/json").content("""
                        {"requestId":"c4","operator":"op-1","expectedConfigVersion":1,"entries":{"k9":9,"k10":10}}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONFIG_KEY_LIMIT"));

        // 参数校验：operator 空白 400；值越界 400
        mockMvc.perform(post("/api/devices/d2/config").contentType("application/json").content("""
                        {"requestId":"c5","operator":"","expectedConfigVersion":1,"entries":{"k9":9}}
                        """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/devices/d2/config").contentType("application/json").content("""
                        {"requestId":"c6","operator":"op-1","expectedConfigVersion":1,"entries":{"k9":1000001}}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 本地配置修改_幂等重放_异参409_失败不占键() throws Exception {
        registerDevice("r1", "d1", "{\"a\":3}");

        String body = """
                {"requestId":"c1","operator":"op-1","expectedConfigVersion":1,"entries":{"x":5}}
                """;
        MvcResult first = mockMvc.perform(post("/api/devices/d1/config").contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        // 同键同参重放：完整响应一致，配置版本只推进一次
        MvcResult replay = mockMvc.perform(post("/api/devices/d1/config").contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.configVersion").value(2));

        // 同键异参：409
        mockMvc.perform(post("/api/devices/d1/config").contentType("application/json").content("""
                        {"requestId":"c1","operator":"op-1","expectedConfigVersion":1,"entries":{"x":6}}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：先触发 409（版本不符），再用同 requestId 正确参数成功
        mockMvc.perform(post("/api/devices/d1/config").contentType("application/json").content("""
                        {"requestId":"c2","operator":"op-1","expectedConfigVersion":99,"entries":{"y":1}}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIG_VERSION_CONFLICT"));
        mockMvc.perform(post("/api/devices/d1/config").contentType("application/json").content("""
                        {"requestId":"c2","operator":"op-1","expectedConfigVersion":2,"entries":{"y":1}}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configVersion").value(3))
                .andExpect(jsonPath("$.config.y").value(1));
    }

    @Test
    void 迁移规则_校验与顺序语义() throws Exception {
        // 超过 5 条：400
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"v1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                         "migrationRules":[{"type":"MULTIPLY","key":"a","factor":1},{"type":"MULTIPLY","key":"a","factor":1},
                         {"type":"MULTIPLY","key":"a","factor":1},{"type":"MULTIPLY","key":"a","factor":1},
                         {"type":"MULTIPLY","key":"a","factor":1},{"type":"MULTIPLY","key":"a","factor":1}]}
                        """))
                .andExpect(status().isBadRequest());
        // RENAME 缺 targetKey：400
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"v2","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                         "migrationRules":[{"type":"RENAME","key":"a"}]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RULE_TARGET_REQUIRED"));
        // RENAME 目标与源相同：400
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"v3","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                         "migrationRules":[{"type":"RENAME","key":"a","targetKey":"a"}]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RULE_TARGET_SAME"));
        // factor 越界：400
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"v4","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                         "migrationRules":[{"type":"MULTIPLY","key":"a","factor":11}]}
                        """))
                .andExpect(status().isBadRequest());

        // 顺序是身份一部分：同 requestId 同规则不同顺序视为异参 409
        registerDevice("r1", "d1", "{\"a\":3}");
        long releaseId = createReleaseWithRules("r2",
                "[{\"type\":\"RENAME\",\"key\":\"a\",\"targetKey\":\"b\"},{\"type\":\"MULTIPLY\",\"key\":\"b\",\"factor\":2}]");
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r2","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                         "migrationRules":[{"type":"MULTIPLY","key":"b","factor":2},{"type":"RENAME","key":"a","targetKey":"b"}]}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 顺序影响预演结果：先改名再乘 a（a 已不存在，乘法跳过）得 b=3，而非 b=6
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r8\"}"))
                .andExpect(status().isOk());
        createReleaseWithRules("r9",
                "[{\"type\":\"RENAME\",\"key\":\"a\",\"targetKey\":\"b\"},{\"type\":\"MULTIPLY\",\"key\":\"a\",\"factor\":2}]");
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r11\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.configAfter.b").value(3));
    }
}
