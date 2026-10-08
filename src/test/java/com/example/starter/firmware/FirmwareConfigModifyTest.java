package com.example.starter.firmware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 本地配置修改、规则顺序身份与 FAILED/取消不改配置测试（H2 内存库，MODE=MySQL）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareConfigModifyTest {

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

    private void registerDevice(String requestId, String deviceId, String model) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"1.0.0","bucketNo":1}
                """.formatted(requestId, deviceId, model)))
                .andExpect(status().isOk());
    }

    private MvcResult modifyConfig(String requestId, String deviceId, int expectedVersion, String entriesJson)
            throws Exception {
        return mockMvc.perform(put("/api/devices/" + deviceId + "/config").contentType("application/json")
                        .content("""
                                {"requestId":"%s","operator":"tester","expectedConfigVersion":%d,"entries":%s}
                                """.formatted(requestId, expectedVersion, entriesJson)))
                .andReturn();
    }

    private long createRelease(String requestId, String model, String rulesJson) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,"rules":%s}
                """.formatted(requestId, model, rulesJson)))
                .andExpect(status().isOk())
                .andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.releaseId");
        return id.longValue();
    }

    private long pullTask(String requestId, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.task.taskId");
        return id.longValue();
    }

    @Test
    void 本地修改_版本校验_进行中任务409_参数越界400_幂等重放() throws Exception {
        registerDevice("cm-d1", "d1", "m1");

        // 版本不匹配：409
        mockMvc.perform(put("/api/devices/d1/config").contentType("application/json").content("""
                {"requestId":"cm-bad","operator":"tester","expectedConfigVersion":9,"entries":{"a":1}}
                """)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIG_VERSION_CONFLICT"));

        // 成功修改：版本 1 -> 2
        MvcResult first = modifyConfig("cm-m1", "d1", 1, "{\"a\":3}");
        org.assertj.core.api.Assertions.assertThat(first.getResponse().getContentAsString())
                .contains("\"configVersion\":2").contains("\"a\":3");

        // 同 requestId 同参重放：响应一致，版本只推进一次
        MvcResult replay = modifyConfig("cm-m1", "d1", 1, "{\"a\":3}");
        org.assertj.core.api.Assertions.assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        mockMvc.perform(get("/api/devices/d1/config"))
                .andExpect(jsonPath("$.configVersion").value(2));

        // 同 requestId 异参：409
        mockMvc.perform(put("/api/devices/d1/config").contentType("application/json").content("""
                {"requestId":"cm-m1","operator":"tester","expectedConfigVersion":1,"entries":{"a":4}}
                """)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 参数越界：11个键 / 值超界 / 空操作者
        mockMvc.perform(put("/api/devices/d1/config").contentType("application/json").content("""
                {"requestId":"cm-v1","operator":"t","expectedConfigVersion":2,
                "entries":{"k1":1,"k2":2,"k3":3,"k4":4,"k5":5,"k6":6,"k7":7,"k8":8,"k9":9,"k10":10,"k11":11}}
                """)).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/devices/d1/config").contentType("application/json").content("""
                {"requestId":"cm-v2","operator":"t","expectedConfigVersion":2,"entries":{"a":1000001}}
                """)).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/devices/d1/config").contentType("application/json").content("""
                {"requestId":"cm-v3","operator":"t","expectedConfigVersion":2,"entries":{"a":-1}}
                """)).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/devices/d1/config").contentType("application/json").content("""
                {"requestId":"cm-v4","operator":"","expectedConfigVersion":2,"entries":{"a":1}}
                """)).andExpect(status().isBadRequest());

        // 设备不存在：404
        mockMvc.perform(put("/api/devices/ghost/config").contentType("application/json").content("""
                {"requestId":"cm-v5","operator":"t","expectedConfigVersion":1,"entries":{"a":1}}
                """)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/devices/ghost/config")).andExpect(status().isNotFound());

        // 进行中任务禁止修改：409
        createRelease("cm-r1", "m1",
                "[{\"type\":\"RENAME\",\"key\":\"a\",\"targetKey\":\"b\"}]");
        pullTask("cm-p1", "d1");
        mockMvc.perform(put("/api/devices/d1/config").contentType("application/json").content("""
                {"requestId":"cm-m2","operator":"tester","expectedConfigVersion":2,"entries":{"a":7}}
                """)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_TASK_IN_PROGRESS"));
    }

    @Test
    void FAILED与取消_固件与配置均不变_取消后可再修改() throws Exception {
        registerDevice("cm-d1", "d1", "m1");
        modifyConfig("cm-c1", "d1", 1, "{\"a\":3}");
        long releaseId = createRelease("cm-r1", "m1",
                "[{\"type\":\"RENAME\",\"key\":\"a\",\"targetKey\":\"b\"}]");
        long taskId = pullTask("cm-p1", "d1");

        // FAILED：固件与配置均不变
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"cm-rc1\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"))
                .andExpect(jsonPath("$.configVersion").value(2));
        mockMvc.perform(get("/api/devices/d1/config"))
                .andExpect(jsonPath("$.entries.a").value(3))
                .andExpect(jsonPath("$.entries.b").doesNotExist());

        // 取消：PENDING 任务转 CANCELLED，后到回执 409，配置不变
        registerDevice("cm-d2", "d2", "m2");
        modifyConfig("cm-c2", "d2", 1, "{\"a\":3}");
        long release2 = createRelease("cm-r2", "m2",
                "[{\"type\":\"RENAME\",\"key\":\"a\",\"targetKey\":\"b\"}]");
        long task2 = pullTask("cm-p2", "d2");
        mockMvc.perform(post("/api/releases/" + release2 + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"cm-x2\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/" + task2 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"cm-rc2\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
        mockMvc.perform(get("/api/devices/d2/config"))
                .andExpect(jsonPath("$.configVersion").value(2))
                .andExpect(jsonPath("$.entries.a").value(3));

        // 取消后无进行中任务，可再次本地修改
        modifyConfig("cm-c3", "d2", 2, "{\"a\":8}");
        mockMvc.perform(get("/api/devices/d2/config"))
                .andExpect(jsonPath("$.configVersion").value(3))
                .andExpect(jsonPath("$.entries.a").value(8));
        org.assertj.core.api.Assertions.assertThat(releaseId).isPositive();
    }

    @Test
    void 规则顺序是身份与语义的一部分() throws Exception {
        // 同 requestId 仅规则顺序不同：409
        createRelease("cm-r1", "m1",
                "[{\"type\":\"RENAME\",\"key\":\"a\",\"targetKey\":\"b\"},"
                        + "{\"type\":\"MULTIPLY\",\"key\":\"b\",\"multiplier\":2}]");
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"cm-r1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "rules":[{"type":"MULTIPLY","key":"b","multiplier":2},{"type":"RENAME","key":"a","targetKey":"b"}]}
                """)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 顺序影响语义：先乘b（b不存在，空操作）再改名a→b，结果为 b=3 而非 b=6
        registerDevice("cm-d1", "d1", "m2");
        modifyConfig("cm-c1", "d1", 1, "{\"a\":3}");
        createRelease("cm-r2", "m2",
                "[{\"type\":\"MULTIPLY\",\"key\":\"b\",\"multiplier\":2},"
                        + "{\"type\":\"RENAME\",\"key\":\"a\",\"targetKey\":\"b\"}]");
        long taskId = pullTask("cm-p1", "d1");
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"cm-rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/d1/config"))
                .andExpect(jsonPath("$.entries.b").value(3))
                .andExpect(jsonPath("$.entries.a").doesNotExist());

        // 规则结构非法：RENAME 缺目标键 / MULTIPLY 缺乘数 / 超过5条
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"cm-b1","model":"m3","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                "rules":[{"type":"RENAME","key":"a"}]}
                """)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RULE"));
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"cm-b2","model":"m3","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                "rules":[{"type":"MULTIPLY","key":"a"}]}
                """)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RULE"));
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"cm-b3","model":"m3","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                "rules":[{"type":"MULTIPLY","key":"a","multiplier":2},{"type":"MULTIPLY","key":"a","multiplier":2},
                {"type":"MULTIPLY","key":"a","multiplier":2},{"type":"MULTIPLY","key":"a","multiplier":2},
                {"type":"MULTIPLY","key":"a","multiplier":2},{"type":"MULTIPLY","key":"a","multiplier":2}]}
                """)).andExpect(status().isBadRequest());
    }
}
