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
 * 配置迁移主流程与预演失败测试（H2 内存库，MODE=MySQL）：
 * 领取冻结快照、SUCCESS 应用完整配置并推进配置版本、预演失败 422 不建任务。
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

    private void registerDevice(String requestId, String deviceId, String model, int bucket) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"1.0.0","bucketNo":%d}
                """.formatted(requestId, deviceId, model, bucket)))
                .andExpect(status().isOk());
    }

    private void modifyConfig(String requestId, String deviceId, int expectedVersion, String entriesJson)
            throws Exception {
        mockMvc.perform(put("/api/devices/" + deviceId + "/config").contentType("application/json").content("""
                {"requestId":"%s","operator":"tester","expectedConfigVersion":%d,"entries":%s}
                """.formatted(requestId, expectedVersion, entriesJson)))
                .andExpect(status().isOk());
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

    private static long taskIdOf(MvcResult result) throws Exception {
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.task.taskId");
        return id.longValue();
    }

    @Test
    void 合成主流程_a3改名b再乘2_成功回执得b6且无a() throws Exception {
        registerDevice("cm-d1", "d1", "m1", 1);
        modifyConfig("cm-c1", "d1", 1, "{\"a\":3}");
        long releaseId = createRelease("cm-r1", "m1",
                "[{\"type\":\"RENAME\",\"key\":\"a\",\"targetKey\":\"b\"},"
                        + "{\"type\":\"MULTIPLY\",\"key\":\"b\",\"multiplier\":2}]");

        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"cm-p1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.configVersion").value(2))
                .andExpect(jsonPath("$.task.configBefore.a").value(3))
                .andExpect(jsonPath("$.task.configAfter.b").value(6))
                .andExpect(jsonPath("$.task.configAfter.a").doesNotExist())
                .andReturn();
        long taskId = taskIdOf(pull);

        // 领取只冻结快照，不改动当前配置
        mockMvc.perform(get("/api/devices/d1/config"))
                .andExpect(jsonPath("$.configVersion").value(2))
                .andExpect(jsonPath("$.entries.a").value(3));

        MvcResult first = mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"cm-rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn();

        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"))
                .andExpect(jsonPath("$.configVersion").value(3));
        mockMvc.perform(get("/api/devices/d1/config"))
                .andExpect(jsonPath("$.configVersion").value(3))
                .andExpect(jsonPath("$.entries.b").value(6))
                .andExpect(jsonPath("$.entries.a").doesNotExist());

        // 旧成功回放不重复迁移：同 requestId 重放响应一致，新 requestId 同结果亦不重复推进版本
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"cm-rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getContentAsString())
                        .isEqualTo(first.getResponse().getContentAsString()));
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"cm-rc2\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/d1/config"))
                .andExpect(jsonPath("$.configVersion").value(3))
                .andExpect(jsonPath("$.entries.b").value(6));
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task WHERE release_id = " + releaseId,
                        Long.class)).isEqualTo(1);
    }

    @Test
    void 预演失败_目标键已存在或乘积越界_422且不建任务不改配置() throws Exception {
        registerDevice("cm-d1", "d1", "m1", 1);
        modifyConfig("cm-c1", "d1", 1, "{\"a\":3,\"b\":9}");
        createRelease("cm-r1", "m1",
                "[{\"type\":\"RENAME\",\"key\":\"a\",\"targetKey\":\"b\"},"
                        + "{\"type\":\"MULTIPLY\",\"key\":\"b\",\"multiplier\":2}]");

        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"cm-p1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONFIG_RENAME_TARGET_EXISTS"));
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();
        mockMvc.perform(get("/api/devices/d1/config"))
                .andExpect(jsonPath("$.configVersion").value(2))
                .andExpect(jsonPath("$.entries.a").value(3))
                .andExpect(jsonPath("$.entries.b").value(9));

        // 乘法越界：500000×3 > 1000000，拒绝不截断
        registerDevice("cm-d2", "d2", "m2", 1);
        modifyConfig("cm-c2", "d2", 1, "{\"a\":500000}");
        createRelease("cm-r2", "m2", "[{\"type\":\"MULTIPLY\",\"key\":\"a\",\"multiplier\":3}]");
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"cm-p2\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONFIG_VALUE_OVERFLOW"));
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();
        mockMvc.perform(get("/api/devices/d2/config"))
                .andExpect(jsonPath("$.configVersion").value(2))
                .andExpect(jsonPath("$.entries.a").value(500000));
    }
}
