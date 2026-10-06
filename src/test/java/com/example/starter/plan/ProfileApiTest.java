package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 列车/区段档案配置 API 测试：版本递增、expectedVersion 校验、
 * requestId 幂等（同参重放、异参 409、失败不占键）与结构化身份防分隔符碰撞。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProfileApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 版本与 expectedVersion ----------

    @Test
    void createThenUpdateIncrementsVersion() throws Exception {
        String trainNo = key("TRN");
        MvcResult created = mvc.perform(put("/api/v1/train-profiles/{tn}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(key("RID"), "op-a", 0, "1", "2")))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(created);
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("reactionTime").decimalValue()).isEqualByComparingTo("1");
        assertThat(body.get("baseDeceleration").decimalValue()).isEqualByComparingTo("2");

        // 重复创建（expectedVersion=0）→ 409
        mvc.perform(put("/api/v1/train-profiles/{tn}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(key("RID"), "op-a", 0, "1", "2")))
                .andExpect(status().isConflict());
        // 版本不匹配 → 409
        mvc.perform(put("/api/v1/train-profiles/{tn}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(key("RID"), "op-a", 5, "1.5", "2")))
                .andExpect(status().isConflict());
        // 版本匹配 → 修改成功，版本递增为 2
        MvcResult updated = mvc.perform(put("/api/v1/train-profiles/{tn}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(key("RID"), "op-a", 1, "1.5", "2.5")))
                .andExpect(status().isOk()).andReturn();
        JsonNode updatedBody = read(updated);
        assertThat(updatedBody.get("version").asInt()).isEqualTo(2);
        assertThat(updatedBody.get("reactionTime").decimalValue()).isEqualByComparingTo("1.5");

        MvcResult queried = mvc.perform(get("/api/v1/train-profiles/{tn}", trainNo))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(queried)).isEqualTo(updatedBody);
    }

    @Test
    void missingProfileReturns404() throws Exception {
        mvc.perform(get("/api/v1/train-profiles/{tn}", key("TRN")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/section-profiles/{sid}", key("SEC")))
                .andExpect(status().isNotFound());
        // 修改不存在的档案 → 404
        mvc.perform(put("/api/v1/train-profiles/{tn}", key("TRN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(key("RID"), "op-a", 1, "1", "2")))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/section-profiles/{sid}", key("SEC"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(key("RID"), "op-a", 1, "35")))
                .andExpect(status().isNotFound());
    }

    // ---------- 参数非法 400 ----------

    @Test
    void rejectsNonPositiveOrOverPreciseParams() throws Exception {
        // t 必须为正
        mvc.perform(put("/api/v1/train-profiles/{tn}", key("TRN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(key("RID"), "op-a", 0, "0", "2")))
                .andExpect(status().isBadRequest());
        // a 必须为正
        mvc.perform(put("/api/v1/train-profiles/{tn}", key("TRN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(key("RID"), "op-a", 0, "1", "-0.5")))
                .andExpect(status().isBadRequest());
        // t 超过 3 位小数
        mvc.perform(put("/api/v1/train-profiles/{tn}", key("TRN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(key("RID"), "op-a", 0, "1.0001", "2")))
                .andExpect(status().isBadRequest());
        // d 必须为正
        mvc.perform(put("/api/v1/section-profiles/{sid}", key("SEC"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(key("RID"), "op-a", 0, "0")))
                .andExpect(status().isBadRequest());
        // d 超过 3 位小数
        mvc.perform(put("/api/v1/section-profiles/{sid}", key("SEC"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(key("RID"), "op-a", 0, "35.0001")))
                .andExpect(status().isBadRequest());
    }

    // ---------- 幂等 ----------

    @Test
    void idempotentReplayAndParamConflict() throws Exception {
        String trainNo = key("TRN");
        String requestId = key("RID");
        String body = trainBody(requestId, "op-a", 0, "1", "2");

        MvcResult first = mvc.perform(put("/api/v1/train-profiles/{tn}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放返回首次完整响应
        MvcResult replay = mvc.perform(put("/api/v1/train-profiles/{tn}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键异参 → 409
        mvc.perform(put("/api/v1/train-profiles/{tn}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(requestId, "op-a", 0, "3", "2")))
                .andExpect(status().isConflict());
        // 同键异操作者 → 409
        mvc.perform(put("/api/v1/train-profiles/{tn}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(requestId, "op-b", 0, "1", "2")))
                .andExpect(status().isConflict());
    }

    @Test
    void failedRequestDoesNotOccupyKey() throws Exception {
        String trainNo = key("TRN");
        mvc.perform(put("/api/v1/train-profiles/{tn}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(key("RID"), "op-a", 0, "1", "2")))
                .andExpect(status().isOk());

        // 版本冲突失败不占键：同 requestId 修正参数后可成功
        String requestId = key("RID");
        mvc.perform(put("/api/v1/train-profiles/{tn}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(requestId, "op-a", 9, "1.5", "2")))
                .andExpect(status().isConflict());
        MvcResult retried = mvc.perform(put("/api/v1/train-profiles/{tn}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(requestId, "op-a", 1, "1.5", "2")))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(retried).get("version").asInt()).isEqualTo(2);
    }

    @Test
    void structuredIdentityAvoidsDelimiterCollision() throws Exception {
        // 朴素分隔符拼接下两者身份相同（"a" + "b|c" 与 "a|b" + "c"），结构化比较必须区分
        String requestId = key("RID");
        mvc.perform(put("/api/v1/train-profiles/{tn}", "b|c")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(requestId, "a", 0, "1", "2")))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/train-profiles/{tn}", "c")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(requestId, "a|b", 0, "1", "2")))
                .andExpect(status().isConflict());
        // 第二列车未被误创建
        mvc.perform(get("/api/v1/train-profiles/{tn}", "c"))
                .andExpect(status().isNotFound());
    }

    // ---------- 辅助 ----------

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String trainBody(String requestId, String operator, int expectedVersion,
                                    String reactionTime, String baseDeceleration) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"expectedVersion\":" + expectedVersion
                + ",\"reactionTime\":" + reactionTime
                + ",\"baseDeceleration\":" + baseDeceleration + "}";
    }

    private static String sectionBody(String requestId, String operator, int expectedVersion,
                                      String protectionDistance) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"expectedVersion\":" + expectedVersion
                + ",\"protectionDistance\":" + protectionDistance + "}";
    }
}
