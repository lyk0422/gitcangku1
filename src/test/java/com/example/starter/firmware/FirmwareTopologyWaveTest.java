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
 * 拓扑波次主流程测试（H2 内存库，MODE=MySQL）：合成链 A-B-C 的波次选择、健康流转、
 * 人工恢复、拉取隔离、422 与幂等重放。
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

    private long createChainRelease(String requestId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "releaseType":"TOPOLOGY","topologyVersion":1,"devices":["A","B","C"],
                "edges":[{"deviceA":"A","deviceB":"B"},{"deviceA":"B","deviceB":"C"}]}
                """.formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseType").value("TOPOLOGY"))
                .andExpect(jsonPath("$.topologyVersion").value(1))
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private MvcResult claim(long releaseId, String requestId, int expectedVersion) throws Exception {
        return mockMvc.perform(post("/api/releases/" + releaseId + "/waves/claim")
                        .contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"operator\":\"op\",\"expectedVersion\":%d}"
                                .formatted(requestId, expectedVersion)))
                .andReturn();
    }

    private void receipt(long taskId, String requestId, String result) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"result\":\"%s\"}".formatted(requestId, result)))
                .andExpect(status().isOk());
    }

    @Test
    void 合成链ABC_第一波选AC_A失败C成功后B不可领_人工恢复A后B入下一波() throws Exception {
        register("A");
        register("B");
        register("C");
        long releaseId = createChainRelease("rel-1");

        // 第一波：A、C 非相邻，被整体选中并标为停机
        MvcResult wave1 = claim(releaseId, "w1", 1);
        wave1.getResponse().setCharacterEncoding("UTF-8");
        assertThat(wave1.getResponse().getStatus()).isEqualTo(200);
        String body = wave1.getResponse().getContentAsString();
        assertThat(body).contains("\"waveNo\":1").contains("\"members\":[\"A\",\"C\"]");
        long waveId = idOf(wave1, "$.waveId");
        long taskA = taskIdOf(body, "A");
        long taskC = taskIdOf(body, "C");
        java.util.List<Number> taskWaveIds = com.jayway.jsonpath.JsonPath.read(body, "$.tasks[*].waveId");
        assertThat(taskWaveIds).hasSize(2).allMatch(id -> id.longValue() == waveId);
        mockMvc.perform(get("/api/devices/A")).andExpect(jsonPath("$.healthStatus").value("DOWN"));
        mockMvc.perform(get("/api/devices/C")).andExpect(jsonPath("$.healthStatus").value("DOWN"));

        // A 失败保持不健康，C 成功恢复健康并升级版本
        receipt(taskA, "rc-a", "FAILED");
        receipt(taskC, "rc-c", "SUCCESS");
        mockMvc.perform(get("/api/devices/A"))
                .andExpect(jsonPath("$.healthStatus").value("UNHEALTHY"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));
        mockMvc.perform(get("/api/devices/C"))
                .andExpect(jsonPath("$.healthStatus").value("HEALTHY"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // B 与不健康的 A 相邻，不能被新波次选入：422 且无新任务
        MvcResult blocked = claim(releaseId, "w2", 1);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(422);
        assertThat(blocked.getResponse().getContentAsString()).contains("NO_WAVE_CANDIDATES");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(2);

        // 人工恢复 A：expectedHealthVersion 校验并加一
        mockMvc.perform(post("/api/devices/A/recover").contentType("application/json")
                        .content("{\"requestId\":\"rec-a\",\"operator\":\"op\",\"expectedHealthVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthStatus").value("HEALTHY"))
                .andExpect(jsonPath("$.healthVersion").value(2));

        // 恢复后 B 进入下一波
        MvcResult wave2 = claim(releaseId, "w3", 1);
        assertThat(wave2.getResponse().getStatus()).isEqualTo(200);
        assertThat(wave2.getResponse().getContentAsString())
                .contains("\"waveNo\":2").contains("\"members\":[\"B\"]");
        mockMvc.perform(get("/api/devices/B")).andExpect(jsonPath("$.healthStatus").value("DOWN"));
    }

    @Test
    void 拓扑发布_普通拉取不能逐个绕过_已有任务仍可拉取查看() throws Exception {
        register("A");
        register("B");
        register("C");
        long releaseId = createChainRelease("rel-1");
        MvcResult wave1 = claim(releaseId, "w1", 1);
        long taskA = taskIdOf(wave1.getResponse().getContentAsString(), "A");

        // B 未入波次：普通拉取不创建任务
        mockMvc.perform(post("/api/devices/B/pull").contentType("application/json")
                        .content("{\"requestId\":\"pull-b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE device_id = 'B'", Long.class)).isZero();

        // A 已有波次任务：拉取返回同一任务
        mockMvc.perform(post("/api/devices/A/pull").contentType("application/json")
                        .content("{\"requestId\":\"pull-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(taskA))
                .andExpect(jsonPath("$.task.status").value("PENDING"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(2);
    }

    @Test
    void 波次幂等_同键重放冻结集合不按当前健康重选_异参409() throws Exception {
        register("A");
        register("B");
        register("C");
        long releaseId = createChainRelease("rel-1");
        MvcResult first = claim(releaseId, "w1", 1);
        String firstBody = first.getResponse().getContentAsString();
        long taskA = taskIdOf(firstBody, "A");

        // 健康状态变化后同键同参重放：仍返回首次冻结的集合与任务
        receipt(taskA, "rc-a", "FAILED");
        MvcResult replay = claim(releaseId, "w1", 1);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(firstBody);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_wave", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(2);

        // 同键异参：409
        MvcResult conflict = mockMvc.perform(post("/api/releases/" + releaseId + "/waves/claim")
                        .contentType("application/json")
                        .content("{\"requestId\":\"w1\",\"operator\":\"op\",\"expectedVersion\":2}"))
                .andReturn();
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(conflict.getResponse().getContentAsString()).contains("REQUEST_ID_CONFLICT");
    }

    @Test
    void 人工恢复_版本冲突409_健康设备409_同键重放只恢复一次() throws Exception {
        register("A");
        register("B");
        register("C");
        long releaseId = createChainRelease("rel-1");
        MvcResult wave1 = claim(releaseId, "w1", 1);
        long taskA = taskIdOf(wave1.getResponse().getContentAsString(), "A");
        receipt(taskA, "rc-a", "FAILED");

        // 健康版本不匹配：409
        mockMvc.perform(post("/api/devices/A/recover").contentType("application/json")
                        .content("{\"requestId\":\"rec-1\",\"operator\":\"op\",\"expectedHealthVersion\":9}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 健康设备不能人工恢复：409
        mockMvc.perform(post("/api/devices/B/recover").contentType("application/json")
                        .content("{\"requestId\":\"rec-2\",\"operator\":\"op\",\"expectedHealthVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_UNHEALTHY"));

        // 正确恢复后同键重放：健康版本只加一次
        String recovered = mockMvc.perform(post("/api/devices/A/recover").contentType("application/json")
                        .content("{\"requestId\":\"rec-3\",\"operator\":\"op\",\"expectedHealthVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthVersion").value(2))
                .andReturn().getResponse().getContentAsString();
        String replay = mockMvc.perform(post("/api/devices/A/recover").contentType("application/json")
                        .content("{\"requestId\":\"rec-3\",\"operator\":\"op\",\"expectedHealthVersion\":1}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(recovered);
        mockMvc.perform(get("/api/devices/A")).andExpect(jsonPath("$.healthVersion").value(2));
    }
}
