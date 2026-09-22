package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 原子改签 API 的主流程、失败回滚、幂等与改签链查询端到端测试（H2 内存库，MODE=MySQL）。
 * 各用例使用唯一 scheduleKey/requestKey/区段 ID，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RescheduleApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 23);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程 ----------

    @Test
    void rescheduleAtomicallyCancelsOldPublishesNewAndLinksChain() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String newKey = key("NEW");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        createPlan(newKey, occ("G2", section, iso(10), iso(11)));

        MvcResult result = mvc.perform(post("/api/v1/reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 1, newKey, 1)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("scheduleKey").asText()).isEqualTo(newKey);
        assertThat(body.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(body.get("version").asInt()).isEqualTo(1);

        // 旧计划取消但原始占用保留不改写
        JsonNode oldDetail = read(mvc.perform(get("/api/v1/plans/{key}", oldKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(oldDetail.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(oldDetail.get("occupancies")).hasSize(1);
        assertThat(oldDetail.get("occupancies").get(0).get("trainNo").asText()).isEqualTo("G1");

        // 已发布时隙仅来自新计划
        JsonNode slots = read(mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn());
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).get("scheduleKey").asText()).isEqualTo(newKey);
        assertThat(slots.get(0).get("trainNo").asText()).isEqualTo("G2");

        // 改签链：从旧、从中查询均返回完整有序链 [旧, 新]
        for (String queryKey : new String[]{oldKey, newKey}) {
            JsonNode chain = read(mvc.perform(get("/api/v1/plans/{key}/chain", queryKey))
                    .andExpect(status().isOk()).andReturn());
            assertThat(chain.get("plans")).hasSize(2);
            assertThat(chain.get("plans").get(0).get("scheduleKey").asText()).isEqualTo(oldKey);
            assertThat(chain.get("plans").get(0).get("status").asText()).isEqualTo("CANCELLED");
            assertThat(chain.get("plans").get(1).get("scheduleKey").asText()).isEqualTo(newKey);
            assertThat(chain.get("plans").get(1).get("status").asText()).isEqualTo("PUBLISHED");
        }
    }

    @Test
    void rescheduleCanRepeatOnSuccessorToBuildMultiHopChain() throws Exception {
        String section = key("SEC");
        String a = key("A");
        String b = key("B");
        String c = key("C");
        createPlan(a, occ("G1", section, iso(8), iso(9)));
        publishPlan(a, key("REQ"));
        createPlan(b, occ("G2", section, iso(9), iso(10)));
        reschedule(a, 1, b, 1);
        createPlan(c, occ("G3", section, iso(10), iso(11)));
        reschedule(b, 1, c, 1);

        // 从任意版本查询都返回 A -> B -> C 完整有序链
        for (String queryKey : new String[]{a, b, c}) {
            JsonNode chain = read(mvc.perform(get("/api/v1/plans/{key}/chain", queryKey))
                    .andExpect(status().isOk()).andReturn());
            assertThat(chain.get("plans")).hasSize(3);
            assertThat(chain.get("plans").get(0).get("scheduleKey").asText()).isEqualTo(a);
            assertThat(chain.get("plans").get(1).get("scheduleKey").asText()).isEqualTo(b);
            assertThat(chain.get("plans").get(2).get("scheduleKey").asText()).isEqualTo(c);
        }
        // 历史占用快照原样保留
        JsonNode chainOfA = read(mvc.perform(get("/api/v1/plans/{key}/chain", a))
                .andExpect(status().isOk()).andReturn());
        assertThat(chainOfA.get("plans").get(0).get("occupancies").get(0)
                .get("trainNo").asText()).isEqualTo("G1");
        assertThat(chainOfA.get("plans").get(2).get("occupancies").get(0)
                .get("trainNo").asText()).isEqualTo("G3");
    }

    @Test
    void rescheduleExcludesOnlyOldOccupanciesButNotThirdParty() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String thirdParty = key("TP");
        String newKey = key("NEW");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        // 第三方已发布计划占用 10:00-11:00
        createPlan(thirdParty, occ("G9", section, iso(10), iso(11)));
        publishPlan(thirdParty, key("REQ"));

        // 新草稿与旧计划完全重叠：仅排除旧计划，合法
        String overlapOld = key("NEW");
        createPlan(overlapOld, occ("G2", section, iso(8), iso(9)));
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 1, overlapOld, 1)))
                .andExpect(status().isOk());

        // 后继再改签：新草稿与第三方重叠 -> 422，回滚不改任何状态
        String oldKey2 = overlapOld;
        createPlan(newKey, occ("G3", section, isoMin(10, 30), isoMin(11, 30)));
        MvcResult conflict = mvc.perform(post("/api/v1/reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey2, 1, newKey, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(conflict);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("SECTION_CONFLICT");
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(thirdParty);

        // 回滚：上一代仍发布、新草稿仍草稿、链上仍只有 [oldKey, overlapOld]
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", oldKey2))
                .andExpect(status().isOk()).andReturn()).get("status").asText())
                .isEqualTo("PUBLISHED");
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", newKey))
                .andExpect(status().isOk()).andReturn()).get("status").asText())
                .isEqualTo("DRAFT");
        JsonNode chain = read(mvc.perform(get("/api/v1/plans/{key}/chain", oldKey2))
                .andExpect(status().isOk()).andReturn());
        assertThat(chain.get("plans")).hasSize(2);

        // 相邻时隙合法
        String adjacent = key("NEW");
        createPlan(adjacent, occ("G4", section, iso(11), iso(12)));
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey2, 1, adjacent, 1)))
                .andExpect(status().isOk());
    }

    @Test
    void rescheduleRejectsTrainOverlapInsideNewDraft() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String newKey = key("NEW");
        createPlan(oldKey, occ("G1", section, iso(8), iso(10)));
        publishPlan(oldKey, key("REQ"));
        createPlan(newKey,
                occ("G2", section, iso(12), iso(13)),
                occ("G2", key("SEC2"), iso(12), iso(14)),
                occ("G2", key("SEC2"), iso(13), iso(15)));

        MvcResult result = mvc.perform(post("/api/v1/reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 1, newKey, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        assertThat(error.get("details").get(0).get("type").asText()).isEqualTo("TRAIN_OVERLAP");

        // 状态、版本、占用、关联均不变
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", oldKey))
                .andExpect(status().isOk()).andReturn()).get("status").asText())
                .isEqualTo("PUBLISHED");
        JsonNode newDetail = read(mvc.perform(get("/api/v1/plans/{key}", newKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(newDetail.get("status").asText()).isEqualTo("DRAFT");
        assertThat(newDetail.get("version").asInt()).isEqualTo(1);
        JsonNode chain = read(mvc.perform(get("/api/v1/plans/{key}/chain", oldKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(chain.get("plans")).hasSize(1);
    }

    // ---------- 参数非法 400 ----------

    @Test
    void rescheduleRejectsInvalidArguments() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));

        // 新旧计划相同
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 1, oldKey, 1)))
                .andExpect(status().isBadRequest());

        // 新草稿运营日不同（占用也位于另一运营日，草稿可创建）
        String otherDay = key("NEW");
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), otherDay, DAY.plusDays(1),
                                occ("G2", section, iso(8, 1), iso(9, 1)))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 1, otherDay, 1)))
                .andExpect(status().isBadRequest());

        // 缺字段 / 非法期望版本
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"oldScheduleKey\":\""
                                + oldKey + "\",\"expectedOldVersion\":1,\"newScheduleKey\":\""
                                + otherDay + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 0, otherDay, 1)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 不存在 404 ----------

    @Test
    void rescheduleMissingPlanReturns404AndChainMissingReturns404() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));

        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 1, key("MISSING"), 1)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), key("MISSING"), 1, oldKey, 1)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plans/{key}/chain", key("MISSING")))
                .andExpect(status().isNotFound());
    }

    // ---------- 版本/状态/关联冲突 409 ----------

    @Test
    void rescheduleRejectsVersionAndStateAndSuccessionConflicts() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String newKey = key("NEW");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        createPlan(newKey, occ("G2", section, iso(12), iso(13)));

        // 旧计划版本不匹配
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 99, newKey, 1)))
                .andExpect(status().isConflict());
        // 新草稿版本不匹配
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 1, newKey, 99)))
                .andExpect(status().isConflict());

        // 旧计划是草稿不可改签
        String draftOld = key("OLD");
        createPlan(draftOld, occ("G1", section, iso(13), iso(14)));
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), draftOld, 1, newKey, 1)))
                .andExpect(status().isConflict());

        // 新计划已发布不可作为改签目标
        String publishedNew = key("NEW");
        createPlan(publishedNew, occ("G3", section, iso(14), iso(15)));
        publishPlan(publishedNew, key("REQ"));
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 1, publishedNew, 1)))
                .andExpect(status().isConflict());

        // 成功一次
        reschedule(oldKey, 1, newKey, 1);

        // 旧计划已取消（已有后继）：不可再次改签
        String another = key("NEW");
        createPlan(another, occ("G4", section, iso(15), iso(16)));
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 1, another, 1)))
                .andExpect(status().isConflict());

        // 新计划已有前驱：不能再次被关联
        String yetAnother = key("OLD");
        createPlan(yetAnother, occ("G5", section, iso(16), iso(17)));
        publishPlan(yetAnother, key("REQ"));
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), yetAnother, 1, newKey, 1)))
                .andExpect(status().isConflict());
    }

    // ---------- 幂等 ----------

    @Test
    void rescheduleIdempotentReplayParamConflictAndFailureDoesNotOccupyKey() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String thirdParty = key("TP");
        String newKey = key("NEW");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        // 第三方占用 10-11；新草稿横跨 08-11，排除旧计划后仍与第三方冲突
        createPlan(thirdParty, occ("G9", section, iso(10), iso(11)));
        publishPlan(thirdParty, key("REQ"));
        createPlan(newKey, occ("G2", section, iso(8), iso(11)));

        String requestKey = key("REQ");
        // 与第三方冲突 -> 422，失败不占键
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(requestKey, oldKey, 1, newKey, 1)))
                .andExpect(status().isUnprocessableEntity());

        // 同键同参再试仍被重新裁决为 422（键未被缓存为重放结果）
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(requestKey, oldKey, 1, newKey, 1)))
                .andExpect(status().isUnprocessableEntity());

        // 第三方取消后同键同参重试成功
        cancelPlan(thirdParty, key("REQ"));
        MvcResult first = mvc.perform(post("/api/v1/reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(requestKey, oldKey, 1, newKey, 1)))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放返回首次结果
        MvcResult replay = mvc.perform(post("/api/v1/reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(requestKey, oldKey, 1, newKey, 1)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键改参 -> 409
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(requestKey, oldKey, 1, key("OTHER"), 1)))
                .andExpect(status().isConflict());

        // 重放不复活/改变已取消旧计划
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", oldKey))
                .andExpect(status().isOk()).andReturn()).get("status").asText())
                .isEqualTo("CANCELLED");
    }

    @Test
    void replayDoesNotResurrectCancelledPlanAfterFurtherReschedule() throws Exception {
        String section = key("SEC");
        String a = key("A");
        String b = key("B");
        String c = key("C");
        createPlan(a, occ("G1", section, iso(8), iso(9)));
        publishPlan(a, key("REQ"));
        createPlan(b, occ("G2", section, iso(9), iso(10)));
        String firstKey = key("REQ");
        rescheduleWithKey(firstKey, a, 1, b, 1);
        createPlan(c, occ("G3", section, iso(10), iso(11)));
        reschedule(b, 1, c, 1);

        // 首次改签请求重放：返回首次结果快照（B 已发布），链状态不被改变
        MvcResult replay = mvc.perform(post("/api/v1/reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(firstKey, a, 1, b, 1)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay).get("scheduleKey").asText()).isEqualTo(b);
        JsonNode chain = read(mvc.perform(get("/api/v1/plans/{key}/chain", a))
                .andExpect(status().isOk()).andReturn());
        assertThat(chain.get("plans")).hasSize(3);
        assertThat(chain.get("plans").get(2).get("scheduleKey").asText()).isEqualTo(c);
    }

    // ---------- 辅助 ----------

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey, DAY, occupancies)))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(requestKey)))
                .andExpect(status().isOk());
    }

    private void cancelPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(requestKey)))
                .andExpect(status().isOk());
    }

    private void reschedule(String oldKey, int oldVersion, String newKey, int newVersion)
            throws Exception {
        rescheduleWithKey(key("REQ"), oldKey, oldVersion, newKey, newVersion);
    }

    private void rescheduleWithKey(String requestKey, String oldKey, int oldVersion,
                                   String newKey, int newVersion) throws Exception {
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(requestKey, oldKey, oldVersion, newKey, newVersion)))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour) {
        return iso(hour, 0);
    }

    private static String iso(int hour, int dayOffset) {
        Instant instant = DAY.plusDays(dayOffset).atTime(hour, 0).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String isoMin(int hour, int minute) {
        Instant instant = DAY.atTime(hour, minute).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String createBody(String requestKey, String scheduleKey, LocalDate day,
                                     String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + day + "\",\"occupancies\":[" + String.join(",", occupancies)
                + "]}";
    }

    private static String rescheduleBody(String requestKey, String oldKey, int oldVersion,
                                         String newKey, int newVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"oldScheduleKey\":\"" + oldKey
                + "\",\"expectedOldVersion\":" + oldVersion
                + ",\"newScheduleKey\":\"" + newKey
                + "\",\"expectedNewVersion\":" + newVersion + "}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }
}
