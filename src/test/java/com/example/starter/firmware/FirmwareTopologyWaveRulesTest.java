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
 * 拓扑发布校验、波次选择规则（上限5台、字典序）、取消释放停机与失败率自动暂停沿用测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareTopologyWaveRulesTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM release_wave_member");
        jdbc.update("DELETE FROM release_wave");
        jdbc.update("DELETE FROM release_topology_edge");
        jdbc.update("DELETE FROM release_topology_device");
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

    private static long taskIdOf(String waveBody, String deviceId) {
        java.util.List<Number> ids = com.jayway.jsonpath.JsonPath.read(waveBody,
                "$.tasks[?(@.deviceId=='" + deviceId + "')].taskId");
        return ids.get(0).longValue();
    }

    private void register(String deviceId) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"reg-%s","deviceId":"%s","model":"m1","currentVersion":"1.0.0","bucketNo":1}
                """.formatted(deviceId, deviceId))).andExpect(status().isOk());
    }

    private MvcResult createTopology(String requestId, String devices, String edges) throws Exception {
        return mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "releaseType":"TOPOLOGY","topologyVersion":1,"devices":%s,"edges":%s}
                """.formatted(requestId, devices, edges))).andReturn();
    }

    private MvcResult claim(long releaseId, String requestId) throws Exception {
        return mockMvc.perform(post("/api/releases/" + releaseId + "/waves/claim")
                        .contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"operator\":\"op\",\"expectedVersion\":1}"
                                .formatted(requestId)))
                .andReturn();
    }

    @Test
    void 拓扑校验_自环重复边越界端点缺版本均为400且不建单() throws Exception {
        register("A");
        register("B");

        MvcResult selfLoop = createTopology("t1", "[\"A\",\"B\"]",
                "[{\"deviceA\":\"A\",\"deviceB\":\"A\"}]");
        assertThat(selfLoop.getResponse().getStatus()).isEqualTo(400);
        assertThat(selfLoop.getResponse().getContentAsString()).contains("TOPOLOGY_EDGE_SELF_LOOP");

        MvcResult dupEdge = createTopology("t2", "[\"A\",\"B\"]",
                "[{\"deviceA\":\"A\",\"deviceB\":\"B\"},{\"deviceA\":\"B\",\"deviceB\":\"A\"}]");
        assertThat(dupEdge.getResponse().getStatus()).isEqualTo(400);
        assertThat(dupEdge.getResponse().getContentAsString()).contains("TOPOLOGY_EDGE_DUPLICATED");

        MvcResult unknownEndpoint = createTopology("t3", "[\"A\",\"B\"]",
                "[{\"deviceA\":\"A\",\"deviceB\":\"Z\"}]");
        assertThat(unknownEndpoint.getResponse().getStatus()).isEqualTo(400);
        assertThat(unknownEndpoint.getResponse().getContentAsString())
                .contains("TOPOLOGY_EDGE_UNKNOWN_DEVICE");

        MvcResult unknownDevice = createTopology("t4", "[\"A\",\"Z\"]", "[]");
        assertThat(unknownDevice.getResponse().getStatus()).isEqualTo(400);
        assertThat(unknownDevice.getResponse().getContentAsString()).contains("TOPOLOGY_DEVICE_UNKNOWN");

        MvcResult dupDevice = createTopology("t5", "[\"A\",\"A\"]", "[]");
        assertThat(dupDevice.getResponse().getStatus()).isEqualTo(400);
        assertThat(dupDevice.getResponse().getContentAsString()).contains("TOPOLOGY_DEVICE_DUPLICATED");

        // 缺拓扑版本
        MvcResult noVersion = mockMvc.perform(post("/api/releases").contentType("application/json")
                        .content("""
                                {"requestId":"t6","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                                "ratio":100,"releaseType":"TOPOLOGY","devices":["A"],"edges":[]}
                                """))
                .andReturn();
        assertThat(noVersion.getResponse().getStatus()).isEqualTo(400);
        assertThat(noVersion.getResponse().getContentAsString()).contains("TOPOLOGY_VERSION_REQUIRED");

        // 超过10台设备：参数校验 400
        register("D1");
        register("D2");
        register("D3");
        register("D4");
        register("D5");
        register("D6");
        register("D7");
        register("D8");
        register("D9");
        MvcResult tooMany = createTopology("t7",
                "[\"A\",\"B\",\"D1\",\"D2\",\"D3\",\"D4\",\"D5\",\"D6\",\"D7\",\"D8\",\"D9\"]", "[]");
        assertThat(tooMany.getResponse().getStatus()).isEqualTo(400);

        // GRAY 发布单携带拓扑字段：400
        MvcResult grayWithTopology = mockMvc.perform(post("/api/releases")
                        .contentType("application/json")
                        .content("""
                                {"requestId":"t8","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                                "ratio":100,"topologyVersion":1}
                                """))
                .andReturn();
        assertThat(grayWithTopology.getResponse().getStatus()).isEqualTo(400);
        assertThat(grayWithTopology.getResponse().getContentAsString()).contains("GRAY_WITH_TOPOLOGY");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order", Long.class)).isZero();
    }

    @Test
    void 波次选择_同大小按设备编号字典序_单波次最多5台() throws Exception {
        // 仅一条边 A-B：{A,C} 与 {B,C} 同为最大非相邻集合，按字典序选 {A,C}
        register("A");
        register("B");
        register("C");
        long releaseId = idOf(createTopology("r1", "[\"A\",\"B\",\"C\"]",
                "[{\"deviceA\":\"A\",\"deviceB\":\"B\"}]"), "$.releaseId");
        MvcResult wave = claim(releaseId, "w1");
        assertThat(wave.getResponse().getContentAsString()).contains("\"members\":[\"A\",\"C\"]");

        // 同型号仅允许一张未终结发布单：先取消上一张再建第二张
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"cancel-1\"}"))
                .andExpect(status().isOk());

        // 7 台孤立设备：第一波取字典序最小5台，第二波取剩余2台
        for (int i = 1; i <= 7; i++) {
            register("d" + i);
        }
        long release2 = idOf(createTopology("r2",
                "[\"d1\",\"d2\",\"d3\",\"d4\",\"d5\",\"d6\",\"d7\"]", "[]"), "$.releaseId");
        MvcResult wave1 = claim(release2, "w2");
        assertThat(wave1.getResponse().getContentAsString())
                .contains("\"members\":[\"d1\",\"d2\",\"d3\",\"d4\",\"d5\"]");
        MvcResult wave2 = claim(release2, "w3");
        assertThat(wave2.getResponse().getContentAsString()).contains("\"members\":[\"d6\",\"d7\"]");
        MvcResult wave3 = claim(release2, "w4");
        assertThat(wave3.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void 取消_待执行任务释放停机_失败设备不自动恢复() throws Exception {
        register("A");
        register("B");
        register("C");
        long releaseId = idOf(createTopology("r1", "[\"A\",\"B\",\"C\"]",
                "[{\"deviceA\":\"A\",\"deviceB\":\"B\"},{\"deviceA\":\"B\",\"deviceB\":\"C\"}]"),
                "$.releaseId");
        MvcResult wave = claim(releaseId, "w1");
        long taskA = taskIdOf(wave.getResponse().getContentAsString(), "A");

        // A 失败后取消：A 保持 UNHEALTHY，C 的停机被释放
        mockMvc.perform(post("/api/tasks/" + taskA + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc-a\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"cancel-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/devices/A")).andExpect(jsonPath("$.healthStatus").value("UNHEALTHY"));
        mockMvc.perform(get("/api/devices/C")).andExpect(jsonPath("$.healthStatus").value("HEALTHY"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks").param("status", "CANCELLED"))
                .andExpect(jsonPath("$.tasks.length()").value(1));
    }

    @Test
    void 拓扑发布沿用失败率自动暂停_暂停后领取409_新键仍触发暂停() throws Exception {
        register("A");
        register("B");
        register("C");
        MvcResult created = mockMvc.perform(post("/api/releases").contentType("application/json")
                        .content("""
                                {"requestId":"r1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                                "ratio":100,"sampleFloor":2,"failureThresholdPercent":50,
                                "releaseType":"TOPOLOGY","topologyVersion":1,
                                "devices":["A","B","C"],"edges":[]}
                                """))
                .andExpect(status().isOk()).andReturn();
        long releaseId = idOf(created, "$.releaseId");

        MvcResult wave = claim(releaseId, "w1");
        java.util.List<Number> taskIds = com.jayway.jsonpath.JsonPath.read(
                wave.getResponse().getContentAsString(), "$.tasks[*].taskId");
        assertThat(taskIds).hasSize(3);
        mockMvc.perform(post("/api/tasks/" + taskIds.get(0).longValue() + "/receipt")
                        .contentType("application/json")
                        .content("{\"requestId\":\"rc-1\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk());
        // 第二台失败触发阈值：发布单原子暂停
        mockMvc.perform(post("/api/tasks/" + taskIds.get(1).longValue() + "/receipt")
                        .contentType("application/json")
                        .content("{\"requestId\":\"rc-2\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"));

        // PAUSED 不能领取新波次
        MvcResult paused = claim(releaseId, "w2");
        assertThat(paused.getResponse().getStatus()).isEqualTo(409);
        assertThat(paused.getResponse().getContentAsString()).contains("RELEASE_NOT_ACTIVE");
    }

    @Test
    void 领取校验_非拓扑发布单400_版本冲突409_发布单不存在404() throws Exception {
        MvcResult gray = mockMvc.perform(post("/api/releases").contentType("application/json")
                        .content("""
                                {"requestId":"g1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                                "ratio":100}
                                """))
                .andExpect(status().isOk()).andReturn();
        long grayId = idOf(gray, "$.releaseId");

        MvcResult notTopology = claim(grayId, "w1");
        assertThat(notTopology.getResponse().getStatus()).isEqualTo(400);
        assertThat(notTopology.getResponse().getContentAsString()).contains("RELEASE_NOT_TOPOLOGY");

        // 释放型号占用后再建拓扑发布单
        mockMvc.perform(post("/api/releases/" + grayId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"cancel-g\"}"))
                .andExpect(status().isOk());

        register("A");
        long releaseId = idOf(createTopology("r1", "[\"A\"]", "[]"), "$.releaseId");
        MvcResult versionConflict = mockMvc.perform(post("/api/releases/" + releaseId + "/waves/claim")
                        .contentType("application/json")
                        .content("{\"requestId\":\"w2\",\"operator\":\"op\",\"expectedVersion\":9}"))
                .andReturn();
        assertThat(versionConflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(versionConflict.getResponse().getContentAsString()).contains("VERSION_CONFLICT");

        MvcResult notFound = claim(9999, "w3");
        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);
    }
}
