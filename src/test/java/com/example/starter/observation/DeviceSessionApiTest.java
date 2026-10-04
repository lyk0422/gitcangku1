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
 * 设备会话序列确认水位 API 的主流程、异常、重复与幂等边界测试（真实 H2 内存库，MySQL 兼容模式）。
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
        jdbcTemplate.update("DELETE FROM session_request_log");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private Map<String, Object> createMessage(String deviceId, String sessionId, int seq,
                                              String observationId, String location,
                                              String reading, String note) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("deviceId", deviceId);
        message.put("sessionId", sessionId);
        message.put("seq", seq);
        message.put("operation", "CREATE");
        message.put("observationId", observationId);
        message.put("location", location);
        message.put("reading", reading);
        message.put("note", note);
        return message;
    }

    private Map<String, Object> mergeMessage(String deviceId, String sessionId, int seq,
                                             String observationId, int baseVersion, String location,
                                             String reading, String note) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("deviceId", deviceId);
        message.put("sessionId", sessionId);
        message.put("seq", seq);
        message.put("operation", "MERGE");
        message.put("observationId", observationId);
        message.put("baseVersion", baseVersion);
        message.put("location", location);
        message.put("reading", reading);
        message.put("note", note);
        return message;
    }

    private ResultActions uploadBatch(String requestId, String operator,
                                      List<Map<String, Object>> messages) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("messages", messages);
        return mockMvc.perform(post("/api/sessions/batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getSession(String deviceId, String sessionId) throws Exception {
        return mockMvc.perform(get("/api/sessions/{deviceId}/{sessionId}", deviceId, sessionId));
    }

    private ResultActions closeSession(String deviceId, String sessionId, String requestId,
                                       String operator, int finalSeq) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("finalSeq", finalSeq);
        return mockMvc.perform(post("/api/sessions/{deviceId}/{sessionId}/close", deviceId, sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int countRows(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    // ---------- 合成业务链：先收 1、3，补 2，重放首批 ----------

    @Test
    void syntheticChainWatermarkAdvanceAndFirstBatchReplay() throws Exception {
        // 第一批：seq 1、3 乱序抵达，水位只能推进到 1，待补 2
        uploadBatch("batch-1", "op-1", List.of(
                        createMessage("dev-1", "s-1", 1, "obs-a", "站点A", "1.0", "首条"),
                        createMessage("dev-1", "s-1", 3, "obs-c", "站点C", "3.0", "跳序")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("batch-1"))
                .andExpect(jsonPath("$.messages[0].seq").value(1))
                .andExpect(jsonPath("$.messages[0].replayed").value(false))
                .andExpect(jsonPath("$.messages[0].observation.observationId").value("obs-a"))
                .andExpect(jsonPath("$.messages[0].observation.version").value(1))
                .andExpect(jsonPath("$.messages[1].seq").value(3))
                .andExpect(jsonPath("$.sessions[0].watermark").value(1))
                .andExpect(jsonPath("$.sessions[0].pending[0]").value(3))
                .andExpect(jsonPath("$.sessions[0].messageCount").value(2))
                .andExpect(jsonPath("$.sessions[0].closed").value(false));

        getSession("dev-1", "s-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermark").value(1))
                .andExpect(jsonPath("$.pending[0]").value(3))
                .andExpect(jsonPath("$.pending.length()").value(1));

        // 第二批：补 seq 2，水位推进到 3，无待补
        uploadBatch("batch-2", "op-1", List.of(
                        createMessage("dev-1", "s-1", 2, "obs-b", "站点B", "2.0", "补洞")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].watermark").value(3))
                .andExpect(jsonPath("$.sessions[0].pending.length()").value(0))
                .andExpect(jsonPath("$.sessions[0].messageCount").value(3));

        getSession("dev-1", "s-1")
                .andExpect(jsonPath("$.watermark").value(3))
                .andExpect(jsonPath("$.pending.length()").value(0));

        // 重放第一批：同键同参返回首次完整响应，水位仍为首次的 1，而非当前水位 3
        uploadBatch("batch-1", "op-1", List.of(
                        createMessage("dev-1", "s-1", 1, "obs-a", "站点A", "1.0", "首条"),
                        createMessage("dev-1", "s-1", 3, "obs-c", "站点C", "3.0", "跳序")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].watermark").value(1))
                .andExpect(jsonPath("$.sessions[0].pending[0]").value(3));

        // 重放未重复改变观测：三条观测各只有一个版本
        assertThat(countRows("SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-a'")).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-b'")).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-c'")).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM session_message WHERE device_id = 'dev-1' AND session_id = 's-1'"))
                .isEqualTo(3);
    }
    // ---------- 参数校验与批内重复 ----------

    @Test
    void batchWithDuplicateSeqReturns400() throws Exception {
        uploadBatch("batch-dup", "op-1", List.of(
                        createMessage("dev-1", "s-1", 1, "obs-a", "站点A", "1.0", "首条"),
                        createMessage("dev-1", "s-1", 1, "obs-b", "站点B", "2.0", "重复序号")))
                .andExpect(status().isBadRequest());
        assertThat(countRows("SELECT COUNT(*) FROM session_message")).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM device_session")).isZero();
    }

    @Test
    void batchSizeOutOfRangeReturns400() throws Exception {
        uploadBatch("batch-empty", "op-1", List.of()).andExpect(status().isBadRequest());

        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            tooMany.add(createMessage("dev-1", "s-1", i, "obs-" + i, "站点A", "1.0", "超量"));
        }
        uploadBatch("batch-21", "op-1", tooMany).andExpect(status().isBadRequest());
        assertThat(countRows("SELECT COUNT(*) FROM session_message")).isZero();
    }

    @Test
    void invalidSeqAndMissingBaseVersionReturn400() throws Exception {
        Map<String, Object> zeroSeq = createMessage("dev-1", "s-1", 0, "obs-a", "站点A", "1.0", "零序号");
        uploadBatch("batch-seq0", "op-1", List.of(zeroSeq)).andExpect(status().isBadRequest());

        Map<String, Object> mergeNoBase = createMessage("dev-1", "s-1", 1, "obs-a", "站点A", "1.0", "缺基线");
        mergeNoBase.put("operation", "MERGE");
        uploadBatch("batch-nobase", "op-1", List.of(mergeNoBase)).andExpect(status().isBadRequest());

        Map<String, Object> createWithBase = createMessage("dev-1", "s-1", 1, "obs-a", "站点A", "1.0", "多基线");
        createWithBase.put("baseVersion", 1);
        uploadBatch("batch-extrabase", "op-1", List.of(createWithBase)).andExpect(status().isBadRequest());

        assertThat(countRows("SELECT COUNT(*) FROM session_message")).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM session_request_log")).isZero();
    }

    // ---------- 同序号重放与异内容 ----------

    @Test
    void sameSeqSameContentReplaysReceiptWithoutTouchingObservation() throws Exception {
        uploadBatch("batch-r1", "op-1", List.of(
                        createMessage("dev-1", "s-1", 1, "obs-a", "站点A", "1.0", "首条")))
                .andExpect(status().isOk());

        // 新批次（新 requestId）携带同序号同内容：返回既有收件记录，不重复改变观测
        uploadBatch("batch-r2", "op-1", List.of(
                        createMessage("dev-1", "s-1", 1, "obs-a", "站点A", "1.0", "首条")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].replayed").value(true))
                .andExpect(jsonPath("$.messages[0].observation.version").value(1))
                .andExpect(jsonPath("$.sessions[0].watermark").value(1))
                .andExpect(jsonPath("$.sessions[0].messageCount").value(1));

        assertThat(countRows("SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-a'")).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM session_message WHERE device_id = 'dev-1' AND session_id = 's-1'"))
                .isEqualTo(1);
    }

    @Test
    void sameSeqDifferentContentReturns409() throws Exception {
        uploadBatch("batch-c1", "op-1", List.of(
                        createMessage("dev-1", "s-1", 1, "obs-a", "站点A", "1.0", "首条")))
                .andExpect(status().isOk());

        uploadBatch("batch-c2", "op-1", List.of(
                        createMessage("dev-1", "s-1", 1, "obs-a", "站点A", "9.9", "异内容")))
                .andExpect(status().isConflict());

        // 失败批次不留收件记录、不推进水位
        getSession("dev-1", "s-1")
                .andExpect(jsonPath("$.watermark").value(1))
                .andExpect(jsonPath("$.messageCount").value(1));
        assertThat(countRows("SELECT COUNT(*) FROM session_message WHERE device_id = 'dev-1' AND session_id = 's-1'"))
                .isEqualTo(1);
    }

    @Test
    void differentSessionsSameSeqAreIndependent() throws Exception {
        uploadBatch("batch-s1", "op-1", List.of(
                        createMessage("dev-1", "s-1", 1, "obs-a", "站点A", "1.0", "会话一")))
                .andExpect(status().isOk());
        uploadBatch("batch-s2", "op-1", List.of(
                        createMessage("dev-1", "s-2", 1, "obs-b", "站点B", "2.0", "会话二")))
                .andExpect(status().isOk());
        uploadBatch("batch-s3", "op-1", List.of(
                        createMessage("dev-2", "s-1", 1, "obs-c", "站点C", "3.0", "另一设备")))
                .andExpect(status().isOk());

        getSession("dev-1", "s-1").andExpect(jsonPath("$.watermark").value(1));
        getSession("dev-1", "s-2").andExpect(jsonPath("$.watermark").value(1));
        getSession("dev-2", "s-1").andExpect(jsonPath("$.watermark").value(1));
        assertThat(countRows("SELECT COUNT(*) FROM session_message")).isEqualTo(3);
    }
    // ---------- 观测业务失败整批回滚 ----------

    @Test
    void observationConflictRejectsWholeBatchAtomically() throws Exception {
        uploadBatch("batch-x1", "op-1", List.of(
                        createMessage("dev-1", "s-1", 1, "obs-a", "站点A", "1.0", "首条")))
                .andExpect(status().isOk());

        // 第二批：seq 2 新建 obs-b 成功、seq 3 重复创建已存在的 obs-a 冲突，整批回滚
        uploadBatch("batch-x2", "op-1", List.of(
                        createMessage("dev-1", "s-1", 2, "obs-b", "站点B", "2.0", "本应成功"),
                        createMessage("dev-1", "s-1", 3, "obs-a", "站点A", "1.0", "冲突")))
                .andExpect(status().isConflict());

        getSession("dev-1", "s-1")
                .andExpect(jsonPath("$.watermark").value(1))
                .andExpect(jsonPath("$.messageCount").value(1));
        assertThat(countRows("SELECT COUNT(*) FROM observation_current WHERE observation_id = 'obs-b'")).isZero();
        // 失败不占键：修正参数后同键可重新成功
        uploadBatch("batch-x2", "op-1", List.of(
                        createMessage("dev-1", "s-1", 2, "obs-b", "站点B", "2.0", "修正后重发")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].watermark").value(2));
    }

    @Test
    void mergeVersionConflictRejectsWholeBatch() throws Exception {
        uploadBatch("batch-v1", "op-1", List.of(
                        createMessage("dev-1", "s-1", 1, "obs-a", "站点A", "1.0", "旧备注")))
                .andExpect(status().isOk());
        // 独立合并把 obs-a 推到版本 2
        uploadBatch("batch-v2", "op-1", List.of(
                        mergeMessage("dev-1", "s-1", 2, "obs-a", 1, "站点B", "1.0", "旧备注")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].observation.version").value(2));

        // 基于过期基线且双方改同一字段：三方合并冲突，整批 409 回滚
        uploadBatch("batch-v3", "op-1", List.of(
                        createMessage("dev-1", "s-1", 3, "obs-c", "站点C", "3.0", "本应成功"),
                        mergeMessage("dev-1", "s-1", 4, "obs-a", 1, "站点D", "1.0", "旧备注")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.currentVersion").value(2));

        getSession("dev-1", "s-1")
                .andExpect(jsonPath("$.watermark").value(2))
                .andExpect(jsonPath("$.messageCount").value(2));
        assertThat(countRows("SELECT COUNT(*) FROM observation_current WHERE observation_id = 'obs-c'")).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM session_request_log WHERE request_id = 'batch-v3'")).isZero();
    }

    @Test
    void requestIdReuseWithDifferentParamsReturns409() throws Exception {
        uploadBatch("batch-k1", "op-1", List.of(
                        createMessage("dev-1", "s-1", 1, "obs-a", "站点A", "1.0", "首条")))
                .andExpect(status().isOk());
        uploadBatch("batch-k1", "op-1", List.of(
                        createMessage("dev-1", "s-1", 1, "obs-a", "站点A", "2.0", "异参")))
                .andExpect(status().isConflict());
        // 操作者属于身份的一部分：仅换操作者也算异参
        uploadBatch("batch-k1", "op-2", List.of(
                        createMessage("dev-1", "s-1", 1, "obs-a", "站点A", "1.0", "首条")))
                .andExpect(status().isConflict());
    }

    // ---------- 会话条数上限 ----------

    @Test
    void sessionMessageLimitIs100() throws Exception {
        for (int batch = 0; batch < 5; batch++) {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                int seq = batch * 20 + i;
                messages.add(createMessage("dev-1", "s-1", seq, "obs-" + seq, "站点A", "1.0", "第" + seq + "条"));
            }
            uploadBatch("batch-fill-" + batch, "op-1", messages).andExpect(status().isOk());
        }
        getSession("dev-1", "s-1")
                .andExpect(jsonPath("$.watermark").value(100))
                .andExpect(jsonPath("$.messageCount").value(100));

        uploadBatch("batch-overflow", "op-1", List.of(
                        createMessage("dev-1", "s-1", 101, "obs-101", "站点A", "1.0", "超限")))
                .andExpect(status().isBadRequest());
        getSession("dev-1", "s-1")
                .andExpect(jsonPath("$.watermark").value(100))
                .andExpect(jsonPath("$.messageCount").value(100));
    }
    // ---------- 会话关闭 ----------

    @Test
    void closeRequiresWatermarkEqualToFinalSeq() throws Exception {
        uploadBatch("batch-w1", "op-1", List.of(
                        createMessage("dev-1", "s-1", 1, "obs-a", "站点A", "1.0", "首条"),
                        createMessage("dev-1", "s-1", 3, "obs-c", "站点C", "3.0", "跳序")))
                .andExpect(status().isOk());

        // 水位 1，声明末序号 3：洞未补，拒绝关闭
        closeSession("dev-1", "s-1", "close-1", "op-1", 3)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(1));
        getSession("dev-1", "s-1").andExpect(jsonPath("$.closed").value(false));

        // 补洞后水位 3，关闭成功
        uploadBatch("batch-w2", "op-1", List.of(
                        createMessage("dev-1", "s-1", 2, "obs-b", "站点B", "2.0", "补洞")))
                .andExpect(status().isOk());
        closeSession("dev-1", "s-1", "close-1", "op-1", 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermark").value(3))
                .andExpect(jsonPath("$.closed").value(true))
                .andExpect(jsonPath("$.finalSeq").value(3));
        getSession("dev-1", "s-1")
                .andExpect(jsonPath("$.closed").value(true))
                .andExpect(jsonPath("$.finalSeq").value(3));
    }

    @Test
    void closedSessionRejectsNewMessagesButReplaysOldKeys() throws Exception {
        List<Map<String, Object>> firstBatch = List.of(
                createMessage("dev-1", "s-1", 1, "obs-a", "站点A", "1.0", "首条"));
        uploadBatch("batch-z1", "op-1", firstBatch).andExpect(status().isOk());
        closeSession("dev-1", "s-1", "close-z1", "op-1", 1).andExpect(status().isOk());

        // 新消息 409
        uploadBatch("batch-z2", "op-1", List.of(
                        createMessage("dev-1", "s-1", 2, "obs-b", "站点B", "2.0", "关闭后")))
                .andExpect(status().isConflict());
        getSession("dev-1", "s-1")
                .andExpect(jsonPath("$.watermark").value(1))
                .andExpect(jsonPath("$.messageCount").value(1));

        // 已成功旧键仍重放首次响应（首次水位 1），不受关闭影响
        uploadBatch("batch-z1", "op-1", firstBatch)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].watermark").value(1))
                .andExpect(jsonPath("$.sessions[0].closed").value(false));

        // 重复关闭（新键）409；同键重放关闭仍返回首次结果
        closeSession("dev-1", "s-1", "close-z2", "op-1", 1).andExpect(status().isConflict());
        closeSession("dev-1", "s-1", "close-z1", "op-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(true));
        // 同键异参 409
        closeSession("dev-1", "s-1", "close-z1", "op-1", 2).andExpect(status().isConflict());
    }

    @Test
    void closeUnknownSessionAndQueryUnknownSessionReturn404() throws Exception {
        closeSession("dev-9", "s-9", "close-404", "op-1", 1).andExpect(status().isNotFound());
        getSession("dev-9", "s-9").andExpect(status().isNotFound());
        assertThat(countRows("SELECT COUNT(*) FROM session_request_log")).isZero();
    }
}