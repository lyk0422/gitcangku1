package com.example.starter.session;

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
 * 设备会话序列确认水位 API 的主流程、失败分支与幂等边界测试（真实 H2 内存库，MySQL 兼容模式）。
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
        jdbcTemplate.update("DELETE FROM session_message");
        jdbcTemplate.update("DELETE FROM device_session");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private Map<String, Object> createCommand(String requestId, String observationId,
                                              String location, String reading, String note) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("requestId", requestId);
        request.put("observationId", observationId);
        request.put("location", location);
        request.put("reading", reading);
        request.put("note", note);
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", "create");
        command.put("request", request);
        return command;
    }

    private Map<String, Object> mergeCommand(String observationId, String requestId, int baseVersion,
                                             String location, String reading, String note) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("requestId", requestId);
        request.put("baseVersion", baseVersion);
        request.put("location", location);
        request.put("reading", reading);
        request.put("note", note);
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", "merge");
        command.put("observationId", observationId);
        command.put("request", request);
        return command;
    }

    private Map<String, Object> message(int seq, Map<String, Object> command) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("seq", seq);
        message.put("command", command);
        return message;
    }

    private ResultActions uploadBatch(String requestId, String deviceId, String sessionId,
                                      List<Map<String, Object>> messages) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", "tester");
        body.put("deviceId", deviceId);
        body.put("sessionId", sessionId);
        body.put("messages", messages);
        return mockMvc.perform(post("/api/device-sessions/batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions closeSession(String requestId, String deviceId, String sessionId,
                                       int lastSeq) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("lastSeq", lastSeq);
        return mockMvc.perform(post("/api/device-sessions/{d}/{s}/close", deviceId, sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    @Test
    void syntheticChainWatermarkAdvancesAndOldKeyReplaysOriginalWatermark() throws Exception {
        // 合成链：先收到 1、3，水位 1 且待补 2
        List<Map<String, Object>> firstBatch = List.of(
                message(1, createCommand("req-o1", "obs-a", "站点A", "1.0", "首条")),
                message(3, createCommand("req-o3", "obs-c", "站点C", "3.0", "跳序")));
        uploadBatch("batch-1", "dev-1", "sess-1", firstBatch)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermark").value(1))
                .andExpect(jsonPath("$.receivedAboveWatermark[0]").value(3))
                .andExpect(jsonPath("$.receipts[0].seq").value(1))
                .andExpect(jsonPath("$.receipts[0].replayed").value(false))
                .andExpect(jsonPath("$.receipts[0].status").value(201))
                .andExpect(jsonPath("$.receipts[1].seq").value(3));

        mockMvc.perform(get("/api/device-sessions/{d}/{s}", "dev-1", "sess-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermark").value(1))
                .andExpect(jsonPath("$.closed").value(false))
                .andExpect(jsonPath("$.receivedAboveWatermark[0]").value(3));

        // 补 2 后水位 3
        uploadBatch("batch-2", "dev-1", "sess-1",
                List.of(message(2, createCommand("req-o2", "obs-b", "站点B", "2.0", "补洞"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermark").value(3))
                .andExpect(jsonPath("$.receivedAboveWatermark.length()").value(0));

        // 重放第一次批次（同 requestId 同参数）：仍返回首次水位 1 而非当前水位 3
        uploadBatch("batch-1", "dev-1", "sess-1", firstBatch)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermark").value(1))
                .andExpect(jsonPath("$.receivedAboveWatermark[0]").value(3));

        mockMvc.perform(get("/api/device-sessions/{d}/{s}", "dev-1", "sess-1"))
                .andExpect(jsonPath("$.watermark").value(3));
    }

    @Test
    void sameSeqSameContentReplaysReceiptWithoutTouchingObservation() throws Exception {
        uploadBatch("batch-10", "dev-1", "sess-2",
                List.of(message(1, createCommand("req-p1", "obs-p", "站点A", "1.0", "原始"))))
                .andExpect(status().isOk());

        // 新批次（新 requestId）携带同 seq 同内容：返回既有收件记录，不重复改变观测
        uploadBatch("batch-11", "dev-1", "sess-2",
                List.of(message(1, createCommand("req-p1", "obs-p", "站点A", "1.0", "原始"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermark").value(1))
                .andExpect(jsonPath("$.receipts[0].replayed").value(true))
                .andExpect(jsonPath("$.receipts[0].observation.version").value(1));

        Integer versions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-p'", Integer.class);
        assertThat(versions).isEqualTo(1);
        Integer messages = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_message WHERE device_id = 'dev-1' AND session_id = 'sess-2'",
                Integer.class);
        assertThat(messages).isEqualTo(1);
    }

    @Test
    void sameSeqDifferentContentRejected() throws Exception {
        uploadBatch("batch-20", "dev-1", "sess-3",
                List.of(message(1, createCommand("req-q1", "obs-q", "站点A", "1.0", "原始"))))
                .andExpect(status().isOk());

        uploadBatch("batch-21", "dev-1", "sess-3",
                List.of(message(1, createCommand("req-q2", "obs-q2", "站点B", "9.9", "异内容"))))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/observations/{id}", "obs-q2"))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateSeqInBatchRejected() throws Exception {
        uploadBatch("batch-30", "dev-1", "sess-4", List.of(
                message(1, createCommand("req-r1", "obs-r1", "站点A", "1.0", "一")),
                message(1, createCommand("req-r2", "obs-r2", "站点B", "2.0", "重复序号"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void batchShapeAndSeqRangeValidated() throws Exception {
        // 空批次 400
        uploadBatch("batch-40", "dev-1", "sess-5", List.of())
                .andExpect(status().isBadRequest());
        // 超过 20 条 400
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            tooMany.add(message(i, createCommand("req-t" + i, "obs-t" + i, "站点", "1.0", "超量")));
        }
        uploadBatch("batch-41", "dev-1", "sess-5", tooMany)
                .andExpect(status().isBadRequest());
        // seq 越界 400
        uploadBatch("batch-42", "dev-1", "sess-5",
                List.of(message(0, createCommand("req-t0", "obs-t0", "站点", "1.0", "零"))))
                .andExpect(status().isBadRequest());
        uploadBatch("batch-43", "dev-1", "sess-5",
                List.of(message(101, createCommand("req-t101", "obs-t101", "站点", "1.0", "越界"))))
                .andExpect(status().isBadRequest());
        // 未知命令类型 400
        Map<String, Object> badCommand = new LinkedHashMap<>();
        badCommand.put("type", "delete");
        badCommand.put("request", Map.of("requestId", "req-tx"));
        uploadBatch("batch-44", "dev-1", "sess-5", List.of(message(1, badCommand)))
                .andExpect(status().isBadRequest());
    }
    @Test
    void observationFailureRollsBackWholeBatchAndKeyIsReusable() throws Exception {
        uploadBatch("batch-50", "dev-1", "sess-6",
                List.of(message(1, createCommand("req-u0", "obs-u", "站点A", "1.0", "已存在"))))
                .andExpect(status().isOk());

        // 第二条消息观测业务冲突（记录已存在）：整批 409，第一条也不保存
        uploadBatch("batch-51", "dev-1", "sess-6", List.of(
                message(2, createCommand("req-u1", "obs-u1", "站点B", "2.0", "新观测")),
                message(3, createCommand("req-u2", "obs-u", "站点C", "3.0", "冲突"))))
                .andExpect(status().isConflict());

        // 失败不留收件记录、不推进水位、观测未创建、去重不占键
        mockMvc.perform(get("/api/observations/{id}", "obs-u1"))
                .andExpect(status().isNotFound());
        Integer messages = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_message WHERE device_id = 'dev-1' AND session_id = 'sess-6'",
                Integer.class);
        assertThat(messages).isEqualTo(1);
        mockMvc.perform(get("/api/device-sessions/{d}/{s}", "dev-1", "sess-6"))
                .andExpect(jsonPath("$.watermark").value(1));
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'batch-51'", Integer.class);
        assertThat(logs).isEqualTo(0);

        // 同键修正参数后可重新提交
        uploadBatch("batch-51", "dev-1", "sess-6",
                List.of(message(2, createCommand("req-u1", "obs-u1", "站点B", "2.0", "新观测"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermark").value(2));
    }

    @Test
    void mergeCommandInBatchFollowsOriginalVersionRules() throws Exception {
        uploadBatch("batch-60", "dev-1", "sess-7",
                List.of(message(1, createCommand("req-m0", "obs-m", "站点A", "1.0", "基线"))))
                .andExpect(status().isOk());

        // 合并命令正常推进水位
        uploadBatch("batch-61", "dev-1", "sess-7",
                List.of(message(2, mergeCommand("obs-m", "req-m1", 1, "站点B", "1.0", "基线"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermark").value(2))
                .andExpect(jsonPath("$.receipts[0].observation.version").value(2))
                .andExpect(jsonPath("$.receipts[0].observation.location").value("站点B"));

        // 基线版本不存在按原规则拒绝整批
        uploadBatch("batch-62", "dev-1", "sess-7",
                List.of(message(3, mergeCommand("obs-m", "req-m2", 99, "站点C", "1.0", "基线"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/device-sessions/{d}/{s}", "dev-1", "sess-7"))
                .andExpect(jsonPath("$.watermark").value(2));
    }

    @Test
    void closeSessionRequiresWatermarkMatchAndBlocksNewMessages() throws Exception {
        uploadBatch("batch-70", "dev-1", "sess-8", List.of(
                message(1, createCommand("req-c1", "obs-c1", "站点A", "1.0", "一")),
                message(2, createCommand("req-c2", "obs-c2", "站点B", "2.0", "二"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermark").value(2));

        // 水位不等于声明末序号：409
        closeSession("close-1", "dev-1", "sess-8", 3)
                .andExpect(status().isConflict());
        // 水位等于声明末序号：成功关闭
        closeSession("close-2", "dev-1", "sess-8", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(true))
                .andExpect(jsonPath("$.watermark").value(2));
        // 同声明末序号重复关闭：幂等返回
        closeSession("close-3", "dev-1", "sess-8", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(true));
        // 不同声明末序号：409
        closeSession("close-4", "dev-1", "sess-8", 5)
                .andExpect(status().isConflict());

        // 关闭后新消息 409
        uploadBatch("batch-71", "dev-1", "sess-8",
                List.of(message(3, createCommand("req-c3", "obs-c3", "站点C", "3.0", "关闭后"))))
                .andExpect(status().isConflict());

        // 已成功旧键仍重放最初水位而非当前状态
        uploadBatch("batch-70", "dev-1", "sess-8", List.of(
                message(1, createCommand("req-c1", "obs-c1", "站点A", "1.0", "一")),
                message(2, createCommand("req-c2", "obs-c2", "站点B", "2.0", "二"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermark").value(2))
                .andExpect(jsonPath("$.receipts[0].replayed").value(false));
    }

    @Test
    void differentSessionsWithSameSeqAreIndependent() throws Exception {
        uploadBatch("batch-80", "dev-1", "sess-9",
                List.of(message(1, createCommand("req-d1", "obs-d1", "站点A", "1.0", "会话九"))))
                .andExpect(status().isOk());
        uploadBatch("batch-81", "dev-1", "sess-10",
                List.of(message(1, createCommand("req-d2", "obs-d2", "站点B", "2.0", "会话十"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/device-sessions/{d}/{s}", "dev-1", "sess-9"))
                .andExpect(jsonPath("$.watermark").value(1));
        mockMvc.perform(get("/api/device-sessions/{d}/{s}", "dev-1", "sess-10"))
                .andExpect(jsonPath("$.watermark").value(1));
    }

    @Test
    void unknownSessionQueriesReturnNotFound() throws Exception {
        mockMvc.perform(get("/api/device-sessions/{d}/{s}", "dev-x", "sess-x"))
                .andExpect(status().isNotFound());
        closeSession("close-x", "dev-x", "sess-x", 0)
                .andExpect(status().isNotFound());
    }
}