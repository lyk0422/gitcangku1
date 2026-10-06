package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * 列车轴重档案与区段限值档案 API 测试：主流程、参数校验、版本冲突与幂等边界（H2 内存库）。
 * 各用例使用唯一 trainNo/sectionId/requestId，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AxleProfileApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程 ----------

    @Test
    void trainProfileCreateUpdateAndGet() throws Exception {
        String trainNo = key("TRN");
        MvcResult created = mvc.perform(post("/api/v1/train-axle-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(key("RID"), trainNo, "[4000,8000,4000,6000]")))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(created);
        assertThat(body.get("trainNo").asText()).isEqualTo(trainNo);
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("axleWeightsKg").toString()).isEqualTo("[4000,8000,4000,6000]");

        MvcResult updated = mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(key("RID"), 1, "[5000,5000]")))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(updated).get("version").asInt()).isEqualTo(2);

        MvcResult detail = mvc.perform(get("/api/v1/train-axle-profiles/{trainNo}", trainNo))
                .andExpect(status().isOk()).andReturn();
        JsonNode fetched = read(detail);
        assertThat(fetched.get("version").asInt()).isEqualTo(2);
        assertThat(fetched.get("axleWeightsKg").toString()).isEqualTo("[5000,5000]");
    }

    @Test
    void sectionProfileCreateUpdateAndGet() throws Exception {
        String sectionId = key("SEC");
        mvc.perform(post("/api/v1/section-limit-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionCreateBody(key("RID"), sectionId, 10000, 18000)))
                .andExpect(status().isCreated());

        MvcResult updated = mvc.perform(put("/api/v1/section-limit-profiles/{sectionId}", sectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionUpdateBody(key("RID"), 1, 12000, 20000)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(updated).get("version").asInt()).isEqualTo(2);

        MvcResult detail = mvc.perform(get("/api/v1/section-limit-profiles/{sectionId}", sectionId))
                .andExpect(status().isOk()).andReturn();
        JsonNode fetched = read(detail);
        assertThat(fetched.get("singleAxleLimitKg").asLong()).isEqualTo(12000L);
        assertThat(fetched.get("window3LimitKg").asLong()).isEqualTo(20000L);
    }

    // ---------- 参数非法 400 ----------

    @Test
    void trainProfileRejectsInvalidParams() throws Exception {
        // 少于 2 轴
        mvc.perform(post("/api/v1/train-axle-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(key("RID"), key("TRN"), "[4000]")))
                .andExpect(status().isBadRequest());
        // 超过 16 轴
        mvc.perform(post("/api/v1/train-axle-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(key("RID"), key("TRN"),
                                "[1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1]")))
                .andExpect(status().isBadRequest());
        // 非正整数轴重
        mvc.perform(post("/api/v1/train-axle-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(key("RID"), key("TRN"), "[4000,0]")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/train-axle-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(key("RID"), key("TRN"), "[4000,-1]")))
                .andExpect(status().isBadRequest());
        // 空操作者
        mvc.perform(post("/api/v1/train-axle-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key("RID") + "\",\"operator\":\"\""
                                + ",\"trainNo\":\"" + key("TRN") + "\",\"axleWeightsKg\":[1,1]}"))
                .andExpect(status().isBadRequest());
        // 缺 requestId
        mvc.perform(post("/api/v1/train-axle-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"dispatcher\",\"trainNo\":\"" + key("TRN")
                                + "\",\"axleWeightsKg\":[1,1]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sectionProfileRejectsInvalidParams() throws Exception {
        // 非正限值
        mvc.perform(post("/api/v1/section-limit-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(sectionCreateBody(key("RID"), key("SEC"), 0, 18000)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/section-limit-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(sectionCreateBody(key("RID"), key("SEC"), 10000, -5)))
                .andExpect(status().isBadRequest());
        // 空操作者
        mvc.perform(post("/api/v1/section-limit-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key("RID") + "\",\"operator\":\"\""
                                + ",\"sectionId\":\"" + key("SEC")
                                + "\",\"singleAxleLimitKg\":10000,\"window3LimitKg\":18000}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 不存在 404 ----------

    @Test
    void missingProfilesReturn404() throws Exception {
        mvc.perform(get("/api/v1/train-axle-profiles/{trainNo}", key("TRN")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/section-limit-profiles/{sectionId}", key("SEC")))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", key("TRN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(key("RID"), 1, "[4000,4000]")))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/section-limit-profiles/{sectionId}", key("SEC"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionUpdateBody(key("RID"), 1, 10000, 18000)))
                .andExpect(status().isNotFound());
    }

    // ---------- 冲突 409 ----------

    @Test
    void duplicateCreateAndVersionConflictRejected() throws Exception {
        String trainNo = key("TRN");
        mvc.perform(post("/api/v1/train-axle-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(key("RID"), trainNo, "[4000,4000]")))
                .andExpect(status().isCreated());
        // 同列车重复登记（新 requestId）→ 409
        MvcResult dup = mvc.perform(post("/api/v1/train-axle-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(key("RID"), trainNo, "[4000,4000]")))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(dup).get("code").asText()).isEqualTo("TRAIN_PROFILE_EXISTS");
        // 期望版本不匹配 → 409
        mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(key("RID"), 7, "[5000,5000]")))
                .andExpect(status().isConflict());

        String sectionId = key("SEC");
        mvc.perform(post("/api/v1/section-limit-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(sectionCreateBody(key("RID"), sectionId, 10000, 18000)))
                .andExpect(status().isCreated());
        MvcResult dupSection = mvc.perform(post("/api/v1/section-limit-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionCreateBody(key("RID"), sectionId, 10000, 18000)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(dupSection).get("code").asText()).isEqualTo("SECTION_PROFILE_EXISTS");
    }

    // ---------- 幂等 ----------

    @Test
    void idempotentReplayAndParamConflict() throws Exception {
        String trainNo = key("TRN");
        String requestId = key("RID");
        String body = trainCreateBody(requestId, trainNo, "[4000,8000,4000,6000]");
        MvcResult first = mvc.perform(post("/api/v1/train-axle-profiles")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        // 同键同参重放返回首次完整响应
        MvcResult replay = mvc.perform(post("/api/v1/train-axle-profiles")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        // 同键异参 → 409
        mvc.perform(post("/api/v1/train-axle-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(requestId, trainNo, "[4000,4000]")))
                .andExpect(status().isConflict());

        // 更新重放：版本只加一次
        String updateId = key("RID");
        String updateBody = trainUpdateBody(updateId, 1, "[5000,5000]");
        MvcResult updated = mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk()).andReturn();
        MvcResult updatedReplay = mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(updatedReplay)).isEqualTo(read(updated));
        MvcResult detail = mvc.perform(get("/api/v1/train-axle-profiles/{trainNo}", trainNo))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("version").asInt()).isEqualTo(2);
    }

    @Test
    void failureDoesNotConsumeRequestId() throws Exception {
        String trainNo = key("TRN");
        mvc.perform(post("/api/v1/train-axle-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(key("RID"), trainNo, "[4000,4000]")))
                .andExpect(status().isCreated());

        // 版本冲突失败不占键：同 requestId 修正参数后重试成功
        String requestId = key("RID");
        mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(requestId, 9, "[5000,5000]")))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(requestId, 1, "[5000,5000]")))
                .andExpect(status().isOk());
    }

    // ---------- 辅助 ----------

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String trainCreateBody(String requestId, String trainNo, String weightsJson) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"dispatcher\""
                + ",\"trainNo\":\"" + trainNo + "\",\"axleWeightsKg\":" + weightsJson + "}";
    }

    private static String trainUpdateBody(String requestId, int expectedVersion,
                                          String weightsJson) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"dispatcher\""
                + ",\"expectedVersion\":" + expectedVersion
                + ",\"axleWeightsKg\":" + weightsJson + "}";
    }

    private static String sectionCreateBody(String requestId, String sectionId, long single,
                                            long window3) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"dispatcher\""
                + ",\"sectionId\":\"" + sectionId + "\",\"singleAxleLimitKg\":" + single
                + ",\"window3LimitKg\":" + window3 + "}";
    }

    private static String sectionUpdateBody(String requestId, int expectedVersion, long single,
                                            long window3) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"dispatcher\""
                + ",\"expectedVersion\":" + expectedVersion + ",\"singleAxleLimitKg\":" + single
                + ",\"window3LimitKg\":" + window3 + "}";
    }
}
