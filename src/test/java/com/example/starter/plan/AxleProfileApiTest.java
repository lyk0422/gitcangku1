package com.example.starter.plan;

import static com.example.starter.plan.AxleEnvelopeApiTest.key;
import static com.example.starter.plan.AxleEnvelopeApiTest.sectionCreateBody;
import static com.example.starter.plan.AxleEnvelopeApiTest.sectionUpdateBody;
import static com.example.starter.plan.AxleEnvelopeApiTest.trainCreateBody;
import static com.example.starter.plan.AxleEnvelopeApiTest.trainUpdateBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 列车轴重档案与区段轴限档案的 CRUD、参数校验、幂等重放与
 * "更新不得使已发布计划非法" 409 保护的端到端测试（H2 内存库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AxleProfileApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程 ----------

    @Test
    void trainProfileCrudMainFlow() throws Exception {
        String train = key("TRAIN");
        MvcResult created = mvc.perform(post("/api/v1/train-axle-profiles/{trainNo}", train)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(key("RID"), "dispatcher-1", "[4000,8000,4000]")))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(created);
        assertThat(body.get("trainNo").asText()).isEqualTo(train);
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("axleWeightsKg")).hasSize(3);

        MvcResult detail = mvc.perform(get("/api/v1/train-axle-profiles/{trainNo}", train))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail)).isEqualTo(body);

        MvcResult updated = mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", train)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(key("RID"), "dispatcher-2", 1,
                                "[4000,8000,4000,6000]")))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(updated).get("version").asInt()).isEqualTo(2);
        assertThat(read(updated).get("axleWeightsKg")).hasSize(4);
    }

    @Test
    void sectionLimitCrudMainFlow() throws Exception {
        String section = key("SEC");
        mvc.perform(post("/api/v1/section-axle-limits/{sectionId}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionCreateBody(key("RID"), "dispatcher-1", 10000, 18000)))
                .andExpect(status().isCreated());

        MvcResult updated = mvc.perform(put("/api/v1/section-axle-limits/{sectionId}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionUpdateBody(key("RID"), "dispatcher-1", 1, 11000, 20000)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(updated);
        assertThat(body.get("version").asInt()).isEqualTo(2);
        assertThat(body.get("singleAxleLimitKg").asLong()).isEqualTo(11000L);
        assertThat(body.get("window3LimitKg").asLong()).isEqualTo(20000L);

        MvcResult detail = mvc.perform(get("/api/v1/section-axle-limits/{sectionId}", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail)).isEqualTo(body);
    }

    // ---------- 参数校验 400 ----------

    @Test
    void profileParamValidation() throws Exception {
        // 轴数少于 2
        mvc.perform(post("/api/v1/train-axle-profiles/{trainNo}", key("TRAIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(key("RID"), "dispatcher-1", "[4000]")))
                .andExpect(status().isBadRequest());
        // 轴数超过 16
        mvc.perform(post("/api/v1/train-axle-profiles/{trainNo}", key("TRAIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(key("RID"), "dispatcher-1",
                                "[1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1]")))
                .andExpect(status().isBadRequest());
        // 轴重非正整数
        mvc.perform(post("/api/v1/train-axle-profiles/{trainNo}", key("TRAIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(key("RID"), "dispatcher-1", "[4000,0]")))
                .andExpect(status().isBadRequest());
        // 缺操作者
        mvc.perform(post("/api/v1/train-axle-profiles/{trainNo}", key("TRAIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key("RID")
                                + "\",\"axleWeightsKg\":[4000,4000]}"))
                .andExpect(status().isBadRequest());
        // 空白操作者
        mvc.perform(post("/api/v1/train-axle-profiles/{trainNo}", key("TRAIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(key("RID"), " ", "[4000,4000]")))
                .andExpect(status().isBadRequest());
        // 限值非正
        mvc.perform(post("/api/v1/section-axle-limits/{sectionId}", key("SEC"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionCreateBody(key("RID"), "dispatcher-1", 0, 18000)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 幂等：同参重放、异参 409、失败不占键 ----------

    @Test
    void trainProfileIdempotentReplayAndParamConflict() throws Exception {
        String train = key("TRAIN");
        String requestId = key("RID");
        String body = trainCreateBody(requestId, "dispatcher-1", "[4000,8000,4000,8000]");

        MvcResult first = mvc.perform(post("/api/v1/train-axle-profiles/{trainNo}", train)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        // 同键同参重放返回首次完整响应
        MvcResult replay = mvc.perform(post("/api/v1/train-axle-profiles/{trainNo}", train)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键异参 → 409；轴序换位也是改参
        mvc.perform(post("/api/v1/train-axle-profiles/{trainNo}", train)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(requestId, "dispatcher-1",
                                "[8000,4000,4000,8000]")))
                .andExpect(status().isConflict());
        // 同键仅改操作者 → 409（操作者参与结构化比较）
        mvc.perform(post("/api/v1/train-axle-profiles/{trainNo}", train)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(requestId, "dispatcher-2",
                                "[4000,8000,4000,8000]")))
                .andExpect(status().isConflict());
    }

    @Test
    void failedUpdateDoesNotConsumeRequestId() throws Exception {
        String train = key("TRAIN");
        mvc.perform(post("/api/v1/train-axle-profiles/{trainNo}", train)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(key("RID"), "dispatcher-1", "[4000,4000]")))
                .andExpect(status().isCreated());

        // 期望版本不符 → 409，失败不占键
        String requestId = key("RID");
        mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", train)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(requestId, "dispatcher-1", 5, "[4000,5000]")))
                .andExpect(status().isConflict());
        // 修正参数后同一 requestId 成功
        mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", train)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(requestId, "dispatcher-1", 1, "[4000,5000]")))
                .andExpect(status().isOk());
    }

    @Test
    void duplicateTrainProfileAndSectionLimitRejected() throws Exception {
        String train = key("TRAIN");
        mvc.perform(post("/api/v1/train-axle-profiles/{trainNo}", train)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(key("RID"), "dispatcher-1", "[4000,4000]")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/train-axle-profiles/{trainNo}", train)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(key("RID"), "dispatcher-1", "[5000,5000]")))
                .andExpect(status().isConflict());

        String section = key("SEC");
        mvc.perform(post("/api/v1/section-axle-limits/{sectionId}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionCreateBody(key("RID"), "dispatcher-1", 10000, 18000)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/section-axle-limits/{sectionId}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionCreateBody(key("RID"), "dispatcher-1", 9000, 17000)))
                .andExpect(status().isConflict());
    }

    // ---------- 档案更新不得使已发布计划非法 409 ----------

    @Test
    void trainProfileUpdateBreakingPublishedPlanRejected409() throws Exception {
        String train = key("TRAIN");
        String section = key("SEC");
        mvc.perform(post("/api/v1/train-axle-profiles/{trainNo}", train)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(key("RID"), "dispatcher-1",
                                "[4000,8000,4000,6000]")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/section-axle-limits/{sectionId}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionCreateBody(key("RID"), "dispatcher-1", 10000, 18000)))
                .andExpect(status().isCreated());
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(train, section, iso(8), iso(9)));
        publishPlan(scheduleKey);

        // 末轴改为 8 吨使第二窗 20 吨越限 → 409，版本不变
        MvcResult rejected = mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", train)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(key("RID"), "dispatcher-1", 1,
                                "[4000,8000,4000,8000]")))
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("PROFILE_UPDATE_CONFLICT");
        assertThat(error.get("details").get(0).get("scheduleKey").asText())
                .isEqualTo(scheduleKey);
        assertThat(error.get("details").get(0).get("windowStartIndex").asInt()).isEqualTo(1);

        MvcResult profile = mvc.perform(get("/api/v1/train-axle-profiles/{trainNo}", train))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(profile).get("version").asInt()).isEqualTo(1);

        // 需先取消相关计划，不自动减载；取消后同一修改合法
        cancelPlan(scheduleKey);
        mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", train)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(key("RID"), "dispatcher-1", 1,
                                "[4000,8000,4000,8000]")))
                .andExpect(status().isOk());
    }

    @Test
    void sectionLimitUpdateBreakingPublishedPlanRejected409() throws Exception {
        String train = key("TRAIN");
        String section = key("SEC");
        mvc.perform(post("/api/v1/train-axle-profiles/{trainNo}", train)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(key("RID"), "dispatcher-1",
                                "[4000,8000,4000,6000]")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/section-axle-limits/{sectionId}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionCreateBody(key("RID"), "dispatcher-1", 10000, 18000)))
                .andExpect(status().isCreated());
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(train, section, iso(8), iso(9)));
        publishPlan(scheduleKey);

        // 收紧三轴窗限至 17 吨使第二窗 18 吨越限 → 409，版本不变
        MvcResult rejected = mvc.perform(put("/api/v1/section-axle-limits/{sectionId}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionUpdateBody(key("RID"), "dispatcher-1", 1, 10000, 17000)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(rejected).get("code").asText()).isEqualTo("PROFILE_UPDATE_CONFLICT");

        MvcResult limit = mvc.perform(get("/api/v1/section-axle-limits/{sectionId}", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(limit).get("version").asInt()).isEqualTo(1);

        // 取消相关计划后修改合法
        cancelPlan(scheduleKey);
        mvc.perform(put("/api/v1/section-axle-limits/{sectionId}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionUpdateBody(key("RID"), "dispatcher-1", 1, 10000, 17000)))
                .andExpect(status().isOk());
    }

    // ---------- 辅助 ----------

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":["
                                + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\"}"))
                .andExpect(status().isOk());
    }

    private void cancelPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String iso(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant().toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }
}
