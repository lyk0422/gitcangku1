package com.example.starter.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设备会话序列确认水位 API 测试：主流程、失败分支、幂等重放与关闭边界（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeviceSessionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM session_receipt");
        jdbcTemplate.update("DELETE FROM session_inbox");
        jdbcTemplate.update("DELETE FROM device_session");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private Map<String, Object> createCommand(String requestId, String observationId,
                                              String location, String reading, String note) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", "CREATE");
        command.put("requestId", requestId);
        command.put("observationId", observationId);
        command.put("location", location);
        command.put("reading", reading);
        command.put("note", note);
        return command;
    }

    private Map<String, Object> mergeCommand(String requestId, String observationId, int baseVersion,
                                             String location, String reading, String note) {
        Map<String, Object> command = createCommand(requestId, observationId, location, reading, note);
        command.put("type", "MERGE");
        command.put("baseVersion", baseVersion);
        return command;
    }

    private Map<String, Object> message(String deviceId, String sessionId, int seq,
                                        Map<String, Object> command) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("deviceId", deviceId);
        message.put("sessionId", sessionId);
        message.put("seq", seq);
        message.put("observation", command);
        return message;
    }

    private ResultActions uploadBatch(String requestId, List<Map<String, Object>> messages) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", "op-test");
        body.put("messages", messages);
        return mockMvc.perform(post("/api/device-sessions/batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions closeSession(String requestId, String deviceId, String sessionId,
                                       int declaredLastSeq) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("deviceId", deviceId);
        body.put("sessionId", sessionId);
        body.put("declaredLastSeq", declaredLastSeq);
        body.put("operator", "op-test");
        return mockMvc.perform(post("/api/device-sessions/close")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getSession(String deviceId, String sessionId) throws Exception {
        return mockMvc.perform(get("/api/device-sessions/{deviceId}/{sessionId}", deviceId, sessionId));
    }

    private void createObservationDirect(String requestId, String observationId,
                                         String location, String reading, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        mockMvc.perform(post("/api/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private void mergeDirect(String requestId, String observationId, int baseVersion,
                             String location, String reading, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        mockMvc.perform(post("/api/observations/{id}/merge", observationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private int count(String sql) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    // ---------- 主流程：跳序、补洞、重放 ----------

    @Test
    void gapFillChainAndBatchReplayKeepsOriginalWatermark() throws Exception {
        // 合成链：先收到 1、3，水位 1 且待补 2
        uploadBatch("req-b1", List.of(
                message("dev-1", "s-1", 1, createCommand("o-b1", "obs-1", "站点A", "1.0", "首条")),
                message("dev-1", "s-1", 3, createCommand("o-b3", "obs-3", "站点C", "3.0", "跳序"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].status").value(201))
                .andExpect(jsonPath("$.messages[0].replayed").value(false))
                .andExpect(jsonPath("$.sessions[0].watermark").value(1))
                .andExpect(jsonPath("$.sessions[0].pendingSeqs.length()").value(1))
                .andExpect(jsonPath("$.sessions[0].pendingSeqs[0]").value(3))
                .andExpect(jsonPath("$.sessions[0].closed").value(false));

        getSession("dev-1", "s-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermark").value(1))
                .andExpect(jsonPath("$.pendingSeqs[0]").value(3));

        // 补 2 后水位推进到 3
        uploadBatch("req-b2", List.of(
                message("dev-1", "s-1", 2, createCommand("o-b2", "obs-2", "站点B", "2.0", "补洞"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].watermark").value(3))
                .andExpect(jsonPath("$.sessions[0].pendingSeqs.length()").value(0));

        // 重放第一次批次：返回首次水位 1 而非当前水位 3，观测不被重复改变
        uploadBatch("req-b1", List.of(
                message("dev-1", "s-1", 1, createCommand("o-b1", "obs-1", "站点A", "1.0", "首条")),
                message("dev-1", "s-1", 3, createCommand("o-b3", "obs-3", "站点C", "3.0", "跳序"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].watermark").value(1))
                .andExpect(jsonPath("$.sessions[0].pendingSeqs[0]").value(3));

        assertThat(count("SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-1'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM session_inbox WHERE device_id = 'dev-1' AND session_id = 's-1'"))
                .isEqualTo(3);
        getSession("dev-1", "s-1").andExpect(jsonPath("$.watermark").value(3));
    }
    // ---------- 参数校验 ----------

    @Test
    void duplicateSeqWithinBatchReturns400() throws Exception {
        uploadBatch("req-d1", List.of(
                message("dev-2", "s-1", 1, createCommand("o-d1", "obs-d1", "站点A", "1.0", "一")),
                message("dev-2", "s-1", 1, createCommand("o-d2", "obs-d2", "站点B", "2.0", "二"))))
                .andExpect(status().isBadRequest());
        assertThat(count("SELECT COUNT(*) FROM session_inbox")).isZero();
        assertThat(count("SELECT COUNT(*) FROM device_session")).isZero();
    }

    @Test
    void emptyAndOversizedBatchReturns400() throws Exception {
        uploadBatch("req-e0", List.of()).andExpect(status().isBadRequest());

        List<Map<String, Object>> messages = new ArrayList<>();
        for (int seq = 1; seq <= 21; seq++) {
            messages.add(message("dev-2", "s-2", seq,
                    createCommand("o-e" + seq, "obs-e" + seq, "站点A", "1.0", "超量")));
        }
        uploadBatch("req-e21", messages).andExpect(status().isBadRequest());
        assertThat(count("SELECT COUNT(*) FROM device_session")).isZero();
    }

    @Test
    void nonPositiveSeqReturns400() throws Exception {
        uploadBatch("req-s0", List.of(
                message("dev-2", "s-3", 0, createCommand("o-s0", "obs-s0", "站点A", "1.0", "零序号"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mergeCommandWithoutBaseVersionReturns400() throws Exception {
        Map<String, Object> command = createCommand("o-m0", "obs-m0", "站点A", "1.0", "缺基线");
        command.put("type", "MERGE");
        uploadBatch("req-m0", List.of(message("dev-2", "s-4", 1, command)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 同序号重放与异内容 ----------

    @Test
    void sameSeqSameContentReplaysInboxRecordWithoutReapplying() throws Exception {
        uploadBatch("req-h1", List.of(
                message("dev-3", "s-1", 1, createCommand("o-h1", "obs-h1", "站点A", "1.0", "首条"))))
                .andExpect(status().isOk());

        // 新批次键、同序号同内容：返回既有收件记录，不重复改变观测
        uploadBatch("req-h2", List.of(
                message("dev-3", "s-1", 1, createCommand("o-h1", "obs-h1", "站点A", "1.0", "首条"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].replayed").value(true))
                .andExpect(jsonPath("$.messages[0].status").value(201))
                .andExpect(jsonPath("$.messages[0].observation.observationId").value("obs-h1"))
                .andExpect(jsonPath("$.sessions[0].watermark").value(1));

        assertThat(count("SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-h1'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM session_inbox WHERE device_id = 'dev-3' AND session_id = 's-1'"))
                .isEqualTo(1);
    }

    @Test
    void sameSeqDifferentContentReturns409() throws Exception {
        uploadBatch("req-g1", List.of(
                message("dev-4", "s-1", 1, createCommand("o-g1", "obs-g1", "站点A", "1.0", "首条"))))
                .andExpect(status().isOk());

        uploadBatch("req-g2", List.of(
                message("dev-4", "s-1", 1, createCommand("o-g2", "obs-g2", "站点B", "2.0", "异内容"))))
                .andExpect(status().isConflict());

        assertThat(count("SELECT COUNT(*) FROM session_inbox WHERE device_id = 'dev-4' AND session_id = 's-1'"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM observation_current WHERE observation_id = 'obs-g2'")).isZero();
        getSession("dev-4", "s-1").andExpect(jsonPath("$.watermark").value(1));
    }

    // ---------- 整批原子性与失败不占键 ----------

    @Test
    void observationFailureRollsBackWholeBatchAndReleasesKeys() throws Exception {
        // 批内两条消息创建同一观测：第二条必然失败，整批回滚
        uploadBatch("req-f1", List.of(
                message("dev-5", "s-1", 1, createCommand("o-f1", "obs-f1", "站点A", "1.0", "一")),
                message("dev-5", "s-1", 2, createCommand("o-f2", "obs-f1", "站点B", "2.0", "二"))))
                .andExpect(status().isConflict());

        assertThat(count("SELECT COUNT(*) FROM observation_current")).isZero();
        assertThat(count("SELECT COUNT(*) FROM session_inbox")).isZero();
        assertThat(count("SELECT COUNT(*) FROM device_session")).isZero();
        assertThat(count("SELECT COUNT(*) FROM session_receipt")).isZero();
        assertThat(count("SELECT COUNT(*) FROM request_log")).isZero();

        // 失败不占键：同一批次键修正内容后可重新成功
        uploadBatch("req-f1", List.of(
                message("dev-5", "s-1", 1, createCommand("o-f1", "obs-f1", "站点A", "1.0", "一"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].watermark").value(1));
    }

    @Test
    void mergeVersionConflictRejectsWholeBatch() throws Exception {
        createObservationDirect("req-p0", "obs-m", "站点A", "1.0", "初始");
        mergeDirect("req-p1", "obs-m", 1, "站点B", "1.0", "初始");

        // seq2 基于 v1 改地点为站点C，与当前 v2 冲突：整批拒绝，seq1 的创建一并回滚
        uploadBatch("req-p2", List.of(
                message("dev-6", "s-1", 1, createCommand("o-p1", "obs-p", "站点P", "9.9", "批内创建")),
                message("dev-6", "s-1", 2, mergeCommand("o-p2", "obs-m", 1, "站点C", "1.0", "初始"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.currentVersion").value(2));

        assertThat(count("SELECT COUNT(*) FROM observation_current WHERE observation_id = 'obs-p'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM session_inbox")).isZero();
        assertThat(count("SELECT COUNT(*) FROM device_session")).isZero();
        assertThat(count("SELECT COUNT(*) FROM session_receipt")).isZero();
        assertThat(count("SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-m'")).isEqualTo(2);
    }
    // ---------- 批次幂等 ----------

    @Test
    void batchRequestIdReusedWithDifferentParamsReturns409() throws Exception {
        uploadBatch("req-m1", List.of(
                message("dev-7", "s-1", 1, createCommand("o-m1", "obs-m1", "站点A", "1.0", "一"))))
                .andExpect(status().isOk());

        uploadBatch("req-m1", List.of(
                message("dev-7", "s-1", 1, createCommand("o-m2", "obs-m2", "站点B", "2.0", "二"))))
                .andExpect(status().isConflict());

        assertThat(count("SELECT COUNT(*) FROM session_receipt WHERE request_id = 'req-m1'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM observation_current WHERE observation_id = 'obs-m2'")).isZero();
    }

    // ---------- 会话隔离与上限 ----------

    @Test
    void differentSessionsWithSameSeqAreIndependent() throws Exception {
        uploadBatch("req-n1", List.of(
                message("dev-8", "s-1", 1, createCommand("o-n1", "obs-n1", "站点A", "1.0", "一"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].watermark").value(1));
        uploadBatch("req-n2", List.of(
                message("dev-8", "s-2", 1, createCommand("o-n2", "obs-n2", "站点B", "2.0", "二"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].watermark").value(1));

        // s-1 跳序收到 3，不影响 s-2
        uploadBatch("req-n3", List.of(
                message("dev-8", "s-1", 3, createCommand("o-n3", "obs-n3", "站点C", "3.0", "三"))))
                .andExpect(status().isOk());
        getSession("dev-8", "s-1")
                .andExpect(jsonPath("$.watermark").value(1))
                .andExpect(jsonPath("$.pendingSeqs[0]").value(3));
        getSession("dev-8", "s-2")
                .andExpect(jsonPath("$.watermark").value(1))
                .andExpect(jsonPath("$.pendingSeqs.length()").value(0));
    }

    @Test
    void sessionMessageLimitIs100() throws Exception {
        for (int batch = 0; batch < 5; batch++) {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                int seq = batch * 20 + i;
                messages.add(message("dev-9", "s-1", seq,
                        createCommand("o-l" + seq, "obs-l" + seq, "站点A", "1.0", "第" + seq + "条")));
            }
            uploadBatch("req-l" + batch, messages).andExpect(status().isOk());
        }
        getSession("dev-9", "s-1").andExpect(jsonPath("$.watermark").value(100));
        assertThat(count("SELECT COUNT(*) FROM session_inbox WHERE device_id = 'dev-9' AND session_id = 's-1'"))
                .isEqualTo(100);

        // 第 101 条新消息超出上限：409，且不留收件记录
        uploadBatch("req-l5", List.of(
                message("dev-9", "s-1", 101, createCommand("o-l101", "obs-l101", "站点A", "1.0", "超限"))))
                .andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM session_inbox WHERE device_id = 'dev-9' AND session_id = 's-1'"))
                .isEqualTo(100);
        getSession("dev-9", "s-1").andExpect(jsonPath("$.watermark").value(100));
    }

    // ---------- 关闭会话 ----------

    @Test
    void closeSessionFlow() throws Exception {
        uploadBatch("req-k1", List.of(
                message("dev-10", "s-1", 1, createCommand("o-k1", "obs-k1", "站点A", "1.0", "一")),
                message("dev-10", "s-1", 2, createCommand("o-k2", "obs-k2", "站点B", "2.0", "二"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].watermark").value(2));

        // 水位不等于声明末序号：409
        closeSession("req-kc0", "dev-10", "s-1", 3).andExpect(status().isConflict());
        // 水位匹配：关闭成功
        closeSession("req-kc1", "dev-10", "s-1", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermark").value(2))
                .andExpect(jsonPath("$.closed").value(true));

        // 关闭后新消息 409，不留收件记录
        uploadBatch("req-k2", List.of(
                message("dev-10", "s-1", 3, createCommand("o-k3", "obs-k3", "站点C", "3.0", "三"))))
                .andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM session_inbox WHERE device_id = 'dev-10' AND session_id = 's-1'"))
                .isEqualTo(2);

        // 已成功旧键仍重放首次响应（当时未关闭），而非当前状态
        uploadBatch("req-k1", List.of(
                message("dev-10", "s-1", 1, createCommand("o-k1", "obs-k1", "站点A", "1.0", "一")),
                message("dev-10", "s-1", 2, createCommand("o-k2", "obs-k2", "站点B", "2.0", "二"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].watermark").value(2))
                .andExpect(jsonPath("$.sessions[0].closed").value(false));

        // 关闭请求同键同参重放首次响应；同键异参 409；新键重复关闭 409
        closeSession("req-kc1", "dev-10", "s-1", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(true));
        closeSession("req-kc1", "dev-10", "s-1", 1).andExpect(status().isConflict());
        closeSession("req-kc2", "dev-10", "s-1", 2).andExpect(status().isConflict());

        assertThat(count("SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-k1'")).isEqualTo(1);
        getSession("dev-10", "s-1")
                .andExpect(jsonPath("$.watermark").value(2))
                .andExpect(jsonPath("$.closed").value(true));
    }

    @Test
    void unknownSessionReturns404() throws Exception {
        getSession("dev-x", "s-x").andExpect(status().isNotFound());
        closeSession("req-x1", "dev-x", "s-x", 0).andExpect(status().isNotFound());
        // 404 失败不占键：先建会话后同键关闭可成功
        uploadBatch("req-x2", List.of(
                message("dev-x", "s-x", 1, createCommand("o-x1", "obs-x1", "站点A", "1.0", "一"))))
                .andExpect(status().isOk());
        closeSession("req-x1", "dev-x", "s-x", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(true));
    }
}