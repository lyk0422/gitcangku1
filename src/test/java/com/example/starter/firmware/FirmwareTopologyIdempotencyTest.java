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
 * 拓扑波次幂等与人工恢复测试（H2 内存库，MODE=MySQL）：
 * 同键同参重放冻结快照、异参409、失败不占键、人工恢复版本核验与重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareTopologyIdempotencyTest {

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

    private void registerDevice(String requestId, String deviceId, int bucket) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"tm","currentVersion":"1.0.0","bucketNo":%d}
                """.formatted(requestId, deviceId, bucket)))
                .andExpect(status().isOk());
    }

    private long createChainRelease(String requestId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"tm","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "topology":{"topologyVersion":1,"deviceIds":["A","B","C"],
                "edges":[{"a":"A","b":"B"},{"a":"B","b":"C"}]}}
                """.formatted(requestId)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private long prepareChainWithWave() throws Exception {
        registerDevice("r-a", "A", 1);
        registerDevice("r-b", "B", 2);
        registerDevice("r-c", "C", 3);
        return createChainRelease("r-rel");
    }

    @Test
    void 领波次_同键重放冻结首次集合_异参409_失败不占键() throws Exception {
        long releaseId = prepareChainWithWave();

        MvcResult first = mockMvc.perform(post("/api/releases/" + releaseId + "/waves")
                        .contentType("application/json")
                        .content("{\"requestId\":\"w1\",\"operator\":\"op-1\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String firstBody = first.getResponse().getContentAsString();
        long taskA = idOf(first, "$.members[0].taskId");
        long taskC = idOf(first, "$.members[1].taskId");

        // 改变健康状态：A 失败、C 成功
        mockMvc.perform(post("/api/tasks/" + taskA + "/receipt").contentType("application/json")
                .content("{\"requestId\":\"r-fa\",\"result\":\"FAILED\"}"));
        mockMvc.perform(post("/api/tasks/" + taskC + "/receipt").contentType("application/json")
                .content("{\"requestId\":\"r-sc\",\"result\":\"SUCCESS\"}"));

        // 旧重放不按当前健康重选：返回首次完整响应
        MvcResult replay = mockMvc.perform(post("/api/releases/" + releaseId + "/waves")
                        .contentType("application/json")
                        .content("{\"requestId\":\"w1\",\"operator\":\"op-1\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(firstBody);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_wave", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(2);

        // 同键异参（不同操作者）：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/waves").contentType("application/json")
                        .content("{\"requestId\":\"w1\",\"operator\":\"op-2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：B 被不健康的 A 阻断，422 后同键可复用
        mockMvc.perform(post("/api/releases/" + releaseId + "/waves").contentType("application/json")
                        .content("{\"requestId\":\"w2\",\"operator\":\"op-1\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/devices/A/recover").contentType("application/json")
                        .content("{\"requestId\":\"r-rec\",\"operator\":\"op-9\",\"expectedHealthVersion\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/releases/" + releaseId + "/waves").contentType("application/json")
                        .content("{\"requestId\":\"w2\",\"operator\":\"op-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waveSeq").value(2))
                .andExpect(jsonPath("$.members[0].deviceId").value("B"));
    }

    @Test
    void 人工恢复_版本校验_健康设备409_重放只加一次版本() throws Exception {
        long releaseId = prepareChainWithWave();
        MvcResult wave = mockMvc.perform(post("/api/releases/" + releaseId + "/waves")
                        .contentType("application/json")
                        .content("{\"requestId\":\"w1\",\"operator\":\"op-1\"}"))
                .andExpect(status().isOk())
                .andReturn();
        long taskA = idOf(wave, "$.members[0].taskId");
        mockMvc.perform(post("/api/tasks/" + taskA + "/receipt").contentType("application/json")
                .content("{\"requestId\":\"r-fa\",\"result\":\"FAILED\"}"));

        // 期望健康版本不匹配：409
        mockMvc.perform(post("/api/devices/A/recover").contentType("application/json")
                        .content("{\"requestId\":\"rec-1\",\"operator\":\"op\",\"expectedHealthVersion\":9}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        // 健康设备无需恢复：409（B 从未停机）
        mockMvc.perform(post("/api/devices/B/recover").contentType("application/json")
                        .content("{\"requestId\":\"rec-2\",\"operator\":\"op\",\"expectedHealthVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_DOWN"));
        // 设备不存在：404
        mockMvc.perform(post("/api/devices/ghost/recover").contentType("application/json")
                        .content("{\"requestId\":\"rec-3\",\"operator\":\"op\",\"expectedHealthVersion\":1}"))
                .andExpect(status().isNotFound());

        // 成功恢复：健康版本加一
        MvcResult recovered = mockMvc.perform(post("/api/devices/A/recover")
                        .contentType("application/json")
                        .content("{\"requestId\":\"rec-4\",\"operator\":\"op\",\"expectedHealthVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthVersion").value(2))
                .andExpect(jsonPath("$.down").value(false))
                .andReturn();
        // 同键同参重放：版本不再增加，返回首次响应
        MvcResult replay = mockMvc.perform(post("/api/devices/A/recover")
                        .contentType("application/json")
                        .content("{\"requestId\":\"rec-4\",\"operator\":\"op\",\"expectedHealthVersion\":1}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(recovered.getResponse().getContentAsString());
        mockMvc.perform(get("/api/devices/A")).andExpect(jsonPath("$.healthVersion").value(2));
        // 同键异参：409
        mockMvc.perform(post("/api/devices/A/recover").contentType("application/json")
                        .content("{\"requestId\":\"rec-4\",\"operator\":\"op\",\"expectedHealthVersion\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }
}
