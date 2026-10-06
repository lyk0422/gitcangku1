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
 * 拓扑波次 API 测试（H2 内存库，MODE=MySQL）：链式拓扑主流程、图校验原子回滚、
 * 拓扑隔离拉取、波次上限、自动暂停与取消释放停机。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareTopologyWaveTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM release_wave_member");
        jdbc.update("DELETE FROM release_wave");
        jdbc.update("DELETE FROM release_topology_edge");
        jdbc.update("DELETE FROM release_topology_node");
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthVersion").value(1))
                .andExpect(jsonPath("$.down").value(false));
    }

    private long createTopologyRelease(String requestId, String model, String topologyJson) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "topology":%s}
                """.formatted(requestId, model, topologyJson)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseType").value("TOPOLOGY"))
                .andExpect(jsonPath("$.topologyVersion").value(1))
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private MvcResult claimWave(long releaseId, String requestId, String operator) throws Exception {
        return mockMvc.perform(post("/api/releases/" + releaseId + "/waves").contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"operator\":\"%s\"}".formatted(requestId, operator)))
                .andReturn();
    }

    private void receipt(long taskId, String requestId, String result) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"result\":\"%s\"}".formatted(requestId, result)))
                .andExpect(status().isOk());
    }

    @Test
    void 主流程_链ABC第一波选AC_A失败C成功后B不可领_人工恢复A后B入下一波() throws Exception {
        registerDevice("r-a", "A", "tm", 1);
        registerDevice("r-b", "B", "tm", 2);
        registerDevice("r-c", "C", "tm", 3);
        long releaseId = createTopologyRelease("r-rel", "tm", """
                {"topologyVersion":1,"deviceIds":["A","B","C"],
                "edges":[{"a":"A","b":"B"},{"a":"B","b":"C"}]}
                """);

        // 第一波：最大非相邻集合 {A, C}，整体建任务并标停机
        MvcResult wave1 = claimWave(releaseId, "r-w1", "op-1");
        String body1 = wave1.getResponse().getContentAsString();
        assertThat(body1).contains("\"waveSeq\":1");
        assertThat(com.jayway.jsonpath.JsonPath.<java.util.List<String>>read(body1, "$.members[*].deviceId"))
                .containsExactly("A", "C");
        long taskA = idOf(wave1, "$.members[0].taskId");
        long taskC = idOf(wave1, "$.members[1].taskId");
        mockMvc.perform(get("/api/devices/A")).andExpect(jsonPath("$.down").value(true));
        mockMvc.perform(get("/api/devices/C")).andExpect(jsonPath("$.down").value(true));
        mockMvc.perform(get("/api/devices/B")).andExpect(jsonPath("$.down").value(false));

        // 拓扑隔离：B 普通拉取不建任务；A 拉取返回已有任务
        mockMvc.perform(post("/api/devices/B/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pb\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());
        mockMvc.perform(post("/api/devices/A/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pa\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(taskA))
                .andExpect(jsonPath("$.task.status").value("PENDING"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(2);

        // A 失败保持不健康；C 成功恢复健康并升级版本
        receipt(taskA, "r-fa", "FAILED");
        mockMvc.perform(get("/api/devices/A"))
                .andExpect(jsonPath("$.down").value(true))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));
        receipt(taskC, "r-sc", "SUCCESS");
        mockMvc.perform(get("/api/devices/C"))
                .andExpect(jsonPath("$.down").value(false))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // B 因邻居 A 不健康被阻断：无可选者 422 且无新任务
        mockMvc.perform(post("/api/releases/" + releaseId + "/waves").contentType("application/json")
                        .content("{\"requestId\":\"r-w2\",\"operator\":\"op-1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_WAVE_CANDIDATE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(2);

        // 人工恢复 A：健康版本加一后 B 可入下一波
        mockMvc.perform(post("/api/devices/A/recover").contentType("application/json")
                        .content("{\"requestId\":\"r-rec\",\"operator\":\"op-9\",\"expectedHealthVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthVersion").value(2))
                .andExpect(jsonPath("$.down").value(false));
        MvcResult wave2 = mockMvc.perform(post("/api/releases/" + releaseId + "/waves")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-w3\",\"operator\":\"op-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waveSeq").value(2))
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.members[0].deviceId").value("B"))
                .andReturn();
        long taskB = idOf(wave2, "$.members[0].taskId");
        mockMvc.perform(get("/api/devices/B")).andExpect(jsonPath("$.down").value(true));
        receipt(taskB, "r-sb", "SUCCESS");
        mockMvc.perform(get("/api/devices/B"))
                .andExpect(jsonPath("$.down").value(false))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
    }

    @Test
    void 图校验_非法拓扑400且发布单不落库() throws Exception {
        registerDevice("r-a", "A", "tm", 1);
        registerDevice("r-b", "B", "tm", 2);
        registerDevice("r-c", "C", "other-model", 3);

        // 自环
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"t1","model":"tm","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "topology":{"topologyVersion":1,"deviceIds":["A","B"],"edges":[{"a":"A","b":"A"}]}}
                """)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOPOLOGY_SELF_LOOP"));
        // 重复边（无向，A-B 与 B-A 等价）
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"t2","model":"tm","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "topology":{"topologyVersion":1,"deviceIds":["A","B"],
                "edges":[{"a":"A","b":"B"},{"a":"B","b":"A"}]}}
                """)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOPOLOGY_DUPLICATE_EDGE"));
        // 边端点不在节点集合内
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"t3","model":"tm","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "topology":{"topologyVersion":1,"deviceIds":["A"],"edges":[{"a":"A","b":"B"}]}}
                """)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOPOLOGY_EDGE_UNKNOWN_DEVICE"));
        // 节点设备未登记
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"t4","model":"tm","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "topology":{"topologyVersion":1,"deviceIds":["A","ghost"],"edges":[]}}
                """)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOPOLOGY_DEVICE_UNKNOWN"));
        // 节点设备型号与发布单不一致
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"t5","model":"tm","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "topology":{"topologyVersion":1,"deviceIds":["A","C"],"edges":[]}}
                """)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOPOLOGY_MODEL_MISMATCH"));
        // 节点重复
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"t6","model":"tm","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "topology":{"topologyVersion":1,"deviceIds":["A","A"],"edges":[]}}
                """)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOPOLOGY_DUPLICATE_DEVICE"));
        // 超过10台设备
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"t7","model":"tm","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "topology":{"topologyVersion":1,
                "deviceIds":["d1","d2","d3","d4","d5","d6","d7","d8","d9","d10","d11"],"edges":[]}}
                """)).andExpect(status().isBadRequest());

        // 校验失败与发布单插入原子回滚：没有任何发布单或拓扑数据落库
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_topology_node", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_topology_edge", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record"
                + " WHERE api = 'release.create'", Long.class)).isZero();
    }

    @Test
    void 波次上限_无边十台设备分两波各5台_之后422() throws Exception {
        for (int i = 0; i < 10; i++) {
            registerDevice("r-d" + i, "d0" + i, "tm", i);
        }
        long releaseId = createTopologyRelease("r-rel", "tm", """
                {"topologyVersion":1,
                "deviceIds":["d00","d01","d02","d03","d04","d05","d06","d07","d08","d09"],"edges":[]}
                """);

        MvcResult wave1 = mockMvc.perform(post("/api/releases/" + releaseId + "/waves")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-w1\",\"operator\":\"op\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waveSeq").value(1))
                .andExpect(jsonPath("$.members.length()").value(5))
                .andReturn();
        assertThat(com.jayway.jsonpath.JsonPath.<java.util.List<String>>read(
                        wave1.getResponse().getContentAsString(), "$.members[*].deviceId"))
                .containsExactly("d00", "d01", "d02", "d03", "d04");

        mockMvc.perform(post("/api/releases/" + releaseId + "/waves").contentType("application/json")
                        .content("{\"requestId\":\"r-w2\",\"operator\":\"op\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waveSeq").value(2))
                .andExpect(jsonPath("$.members.length()").value(5))
                .andExpect(jsonPath("$.members[0].deviceId").value("d05"))
                .andExpect(jsonPath("$.members[4].deviceId").value("d09"));

        mockMvc.perform(post("/api/releases/" + releaseId + "/waves").contentType("application/json")
                        .content("{\"requestId\":\"r-w3\",\"operator\":\"op\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_WAVE_CANDIDATE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT device_id) FROM rollout_task", Long.class)).isEqualTo(10);
    }

    @Test
    void 自动暂停_拓扑发布失败率达阈值暂停后领波次409() throws Exception {
        registerDevice("r-a", "A", "tm", 1);
        registerDevice("r-b", "B", "tm", 2);
        registerDevice("r-c", "C", "tm", 3);
        MvcResult created = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-rel","model":"tm","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "sampleFloor":2,"failureThresholdPercent":50,
                "topology":{"topologyVersion":1,"deviceIds":["A","B","C"],
                "edges":[{"a":"A","b":"B"},{"a":"B","b":"C"}]}}
                """)).andExpect(status().isOk()).andReturn();
        long releaseId = idOf(created, "$.releaseId");

        MvcResult wave1 = claimWave(releaseId, "r-w1", "op");
        long taskA = idOf(wave1, "$.members[0].taskId");
        long taskC = idOf(wave1, "$.members[1].taskId");
        receipt(taskA, "r-f1", "FAILED");
        receipt(taskC, "r-f2", "FAILED");

        // 新键仍执行原自动暂停规则：失败率越限即 PAUSED
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.roundFailed").value(2));
        mockMvc.perform(post("/api/releases/" + releaseId + "/waves").contentType("application/json")
                        .content("{\"requestId\":\"r-w2\",\"operator\":\"op\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_ACTIVE"));
        // 失败设备保持不健康，不自动恢复
        mockMvc.perform(get("/api/devices/A")).andExpect(jsonPath("$.down").value(true));
        mockMvc.perform(get("/api/devices/C")).andExpect(jsonPath("$.down").value(true));
    }

    @Test
    void 取消_待执行任务释放停机_失败设备不自动恢复() throws Exception {
        registerDevice("r-a", "A", "tm", 1);
        registerDevice("r-b", "B", "tm", 2);
        registerDevice("r-c", "C", "tm", 3);
        long releaseId = createTopologyRelease("r-rel", "tm", """
                {"topologyVersion":1,"deviceIds":["A","B","C"],
                "edges":[{"a":"A","b":"B"},{"a":"B","b":"C"}]}
                """);
        MvcResult wave1 = claimWave(releaseId, "r-w1", "op");
        long taskA = idOf(wave1, "$.members[0].taskId");
        long taskC = idOf(wave1, "$.members[1].taskId");
        receipt(taskA, "r-f1", "FAILED");

        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 待执行的 C 取消并释放停机；已失败的 A 保持不健康
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks").param("status", "CANCELLED"))
                .andExpect(jsonPath("$.tasks.length()").value(1));
        mockMvc.perform(get("/api/devices/C")).andExpect(jsonPath("$.down").value(false));
        mockMvc.perform(get("/api/devices/A")).andExpect(jsonPath("$.down").value(true));
        // 取消后领波次 409
        mockMvc.perform(post("/api/releases/" + releaseId + "/waves").contentType("application/json")
                        .content("{\"requestId\":\"r-w2\",\"operator\":\"op\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_ACTIVE"));
        // 取消任务的后到回执 409
        mockMvc.perform(post("/api/tasks/" + taskC + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-late\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
    }

    @Test
    void 非拓扑发布_领波次400() throws Exception {
        registerDevice("r-a", "A", "tm", 1);
        MvcResult created = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-rel","model":"tm","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseType").value("STANDARD"))
                .andReturn();
        long releaseId = idOf(created, "$.releaseId");
        mockMvc.perform(post("/api/releases/" + releaseId + "/waves").contentType("application/json")
                        .content("{\"requestId\":\"r-w1\",\"operator\":\"op\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOT_TOPOLOGY_RELEASE"));
    }
}
