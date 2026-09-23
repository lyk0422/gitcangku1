package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 多计划容量闭环原子交换 API 测试（H2 内存库）：三计划闭环交换、预览冲突、
 * 外部冲突、整体回滚、幂等重放与交换项换序、swapKey 唯一、各类 400/404/409/422 分支。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CapacitySwapApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 23);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程：三计划闭环交换 ----------

    @Test
    void threePlanClosedLoopSwapActivatesAtomically() throws Exception {
        String section = key("SEC");
        Plan a = publishPlan("A", section, 8, 10);
        Plan b = publishPlan("B", section, 10, 12);
        Plan c = publishPlan("C", section, 12, 14);

        // 闭环：A 拿 B 的时隙，B 拿 C 的，C 拿 A 的；逐项看都会“临时冲突”，完整后态合法。
        List<JsonNode> items = new ArrayList<>();
        items.add(swapItem(a.key, 1, List.of(occ("TA", section, 8, 10)),
                List.of(occ("TA", section, 10, 12))));
        items.add(swapItem(b.key, 1, List.of(occ("TB", section, 10, 12)),
                List.of(occ("TB", section, 12, 14))));
        items.add(swapItem(c.key, 1, List.of(occ("TC", section, 12, 14)),
                List.of(occ("TC", section, 8, 10))));

        String swapKey = key("SWAP");
        String createReqKey = key("REQ");
        MvcResult preview = mvc.perform(post("/api/v1/capacity-swaps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(swapCreateBody(createReqKey, swapKey, items)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode previewBody = read(preview);
        assertThat(previewBody.get("swapKey").asText()).isEqualTo(swapKey);
        assertThat(previewBody.get("status").asText()).isEqualTo("PREVIEW");
        assertThat(previewBody.get("plans")).hasSize(3);
        assertThat(previewBody.get("conflicts")).isEmpty();
        for (JsonNode plan : previewBody.get("plans")) {
            assertThat(plan.get("version").asInt()).isEqualTo(1);
        }

        // 预览不改变占用：已发布时隙仍是交换前三段
        assertThat(slotScheduleKeys(section)).containsExactlyInAnyOrder(a.key, b.key, c.key);

        String activateReqKey = key("REQ");
        MvcResult activated = mvc.perform(post("/api/v1/capacity-swaps/{swapKey}/activate", swapKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(activateReqKey)))
                .andExpect(status().isOk()).andReturn();
        JsonNode activeBody = read(activated);
        assertThat(activeBody.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(activeBody.get("plans")).hasSize(3);

        Map<String, JsonNode> plansByKey = indexByKey(activeBody.get("plans"));
        // 版本各加一、仍 PUBLISHED，占用整体替换为目标时隙
        assertPlanTarget(plansByKey.get(a.key), a.key, "TA", section, 10, 12);
        assertPlanTarget(plansByKey.get(b.key), b.key, "TB", section, 12, 14);
        assertPlanTarget(plansByKey.get(c.key), c.key, "TC", section, 8, 10);

        // 计划明细接口验证真实落库状态
        assertPersisted(a.key, 2, "PUBLISHED", "TA", section, 10, 12);
        assertPersisted(b.key, 2, "PUBLISHED", "TB", section, 12, 14);
        assertPersisted(c.key, 2, "PUBLISHED", "TC", section, 8, 10);

        // 不可变前后快照
        JsonNode before = activeBody.get("beforeSnapshots");
        JsonNode after = activeBody.get("afterSnapshots");
        assertThat(before).hasSize(3);
        assertThat(after).hasSize(3);
        for (int i = 0; i < 3; i++) {
            assertThat(before.get(i).get("phase").asText()).isEqualTo("BEFORE");
            assertThat(before.get(i).get("itemSeq").asInt()).isEqualTo(i);
            assertThat(after.get(i).get("phase").asText()).isEqualTo("AFTER");
            assertThat(after.get(i).get("version").asInt())
                    .isEqualTo(before.get(i).get("version").asInt() + 1);
        }

        // 证据查询只读且稳定排序，与激活响应一致
        MvcResult evidence = mvc.perform(get("/api/v1/capacity-swaps/{swapKey}/evidence", swapKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode evidenceBody = read(evidence);
        assertThat(evidenceBody.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(evidenceBody.get("plans")).isEqualTo(activeBody.get("plans"));
        assertThat(evidenceBody.get("beforeSnapshots")).isEqualTo(before);
        assertThat(evidenceBody.get("afterSnapshots")).isEqualTo(after);
    }

    // ---------- 预览冲突（不阻断创建） ----------

    @Test
    void previewReportsInternalAndExternalConflicts() throws Exception {
        String section = key("SEC");
        Plan a = publishPlan("A", section, 8, 10);
        Plan b = publishPlan("B", section, 10, 12);
        Plan external = publishPlan("E", section, 12, 14);

        // A、B 目标都占用 8-9（参与计划间冲突）；B 目标还撞外部 E 的 12-14
        List<JsonNode> items = List.of(
                swapItem(a.key, 1, List.of(occ("TA", section, 8, 10)),
                        List.of(occ("TA", section, 8, 9))),
                swapItem(b.key, 1, List.of(occ("TB", section, 10, 12)),
                        List.of(occ("TB", section, 8, 9), occ("TB", section, 12, 14))));

        String swapKey = key("SWAP");
        MvcResult preview = mvc.perform(post("/api/v1/capacity-swaps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(swapCreateBody(key("REQ"), swapKey, items)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode conflicts = read(preview).get("conflicts");
        List<String> types = new ArrayList<>();
        conflicts.forEach(n -> types.add(n.get("type").asText()));
        assertThat(types).contains("INTERNAL_TARGET_CONFLICT", "SECTION_CONFLICT");
        assertThat(conflicts).anySatisfy(n -> {
            assertThat(n.get("type").asText()).isEqualTo("SECTION_CONFLICT");
            assertThat(n.get("conflictingScheduleKey").asText()).isEqualTo(external.key);
        });

        // 预览冲突不影响现有占用，激活带冲突组合必须 422
        mvc.perform(post("/api/v1/capacity-swaps/{swapKey}/activate", swapKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- 外部冲突：激活 422 且整体回滚 ----------

    @Test
    void externalConflictRejectsActivationAndRollsBackEverything() throws Exception {
        String section = key("SEC");
        Plan a = publishPlan("A", section, 8, 10);
        Plan b = publishPlan("B", section, 10, 12);
        Plan external = publishPlan("E", section, 12, 14);

        // A 目标 10-12（B 释放，合法），B 目标 12-14（与未参与的 E 冲突）
        List<JsonNode> items = List.of(
                swapItem(a.key, 1, List.of(occ("TA", section, 8, 10)),
                        List.of(occ("TA", section, 10, 12))),
                swapItem(b.key, 1, List.of(occ("TB", section, 10, 12)),
                        List.of(occ("TB", section, 12, 14))));

        String swapKey = key("SWAP");
        createSwap(swapKey, items);

        String activateReqKey = key("REQ");
        MvcResult result = mvc.perform(post("/api/v1/capacity-swaps/{swapKey}/activate", swapKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(activateReqKey)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        assertThat(error.get("details").get(0).get("type").asText()).isEqualTo("SECTION_CONFLICT");
        assertThat(error.get("details").get(0).get("conflictingScheduleKey").asText())
                .isEqualTo(external.key);

        // 整体回滚：A、B 占用与版本保持交换前，仍 PUBLISHED；交换单仍 PREVIEW
        assertPersisted(a.key, 1, "PUBLISHED", "TA", section, 8, 10);
        assertPersisted(b.key, 1, "PUBLISHED", "TB", section, 10, 12);
        MvcResult evidence = mvc.perform(get("/api/v1/capacity-swaps/{swapKey}/evidence", swapKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode evidenceBody = read(evidence);
        assertThat(evidenceBody.get("status").asText()).isEqualTo("PREVIEW");
        assertThat(evidenceBody.get("beforeSnapshots")).isEmpty();
        assertThat(evidenceBody.get("afterSnapshots")).isEmpty();

        // 失败不占键：外部计划取消后，同一 requestKey 重试成功
        cancelPlan(external.key, key("REQ"));
        mvc.perform(post("/api/v1/capacity-swaps/{swapKey}/activate", swapKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(activateReqKey)))
                .andExpect(status().isOk());
        assertPersisted(a.key, 2, "PUBLISHED", "TA", section, 10, 12);
        assertPersisted(b.key, 2, "PUBLISHED", "TB", section, 12, 14);
    }

    // ---------- 幂等：同参重放、交换项换序、异参 409 ----------

    @Test
    void activationReplaysFirstResponseAndReorderingItemsIsSameRequest() throws Exception {
        String section = key("SEC");
        Plan a = publishPlan("A", section, 8, 10);
        Plan b = publishPlan("B", section, 10, 12);

        List<JsonNode> itemsAb = new ArrayList<>();
        itemsAb.add(swapItem(a.key, 1, List.of(occ("TA", section, 8, 10)),
                List.of(occ("TA", section, 10, 12))));
        itemsAb.add(swapItem(b.key, 1, List.of(occ("TB", section, 10, 12)),
                List.of(occ("TB", section, 8, 10))));
        String swapKey = key("SWAP");
        String createReqKey = key("REQ");
        MvcResult firstCreate = mvc.perform(post("/api/v1/capacity-swaps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(swapCreateBody(createReqKey, swapKey, itemsAb)))
                .andExpect(status().isCreated()).andReturn();

        // 创建同参重放（交换项换序视为同参）：同一 requestKey、A/B 顺序颠倒，返回首次响应
        List<JsonNode> itemsBa = new ArrayList<>();
        itemsBa.add(itemsAb.get(1));
        itemsBa.add(itemsAb.get(0));
        MvcResult reordered = mvc.perform(post("/api/v1/capacity-swaps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(swapCreateBody(createReqKey, swapKey, itemsBa)))
                .andExpect(status().isCreated()).andReturn();
        // 规范化后参与项排序一致，plans 稳定排序后与首次响应完全相同
        assertThat(read(reordered)).isEqualTo(read(firstCreate));

        // 同一 requestKey 异参（改期望版本）→ 409
        List<JsonNode> changed = new ArrayList<>();
        changed.add(swapItem(a.key, 9, List.of(occ("TA", section, 8, 10)),
                List.of(occ("TA", section, 10, 12))));
        changed.add(swapItem(b.key, 1, List.of(occ("TB", section, 10, 12)),
                List.of(occ("TB", section, 8, 10))));
        mvc.perform(post("/api/v1/capacity-swaps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(swapCreateBody(createReqKey, key("OTHER-SWAP"), changed)))
                .andExpect(status().isConflict());

        // 激活成功后同 requestKey 重放首次响应
        String activateReqKey = key("REQ");
        MvcResult firstActivate = mvc.perform(
                        post("/api/v1/capacity-swaps/{swapKey}/activate", swapKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(actionBody(activateReqKey)))
                .andExpect(status().isOk()).andReturn();
        MvcResult replayActivate = mvc.perform(
                        post("/api/v1/capacity-swaps/{swapKey}/activate", swapKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(actionBody(activateReqKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replayActivate)).isEqualTo(read(firstActivate));

        // 版本只递增一次
        assertPersisted(a.key, 2, "PUBLISHED", "TA", section, 10, 12);

        // 已激活交换单用另一 requestKey → 409
        mvc.perform(post("/api/v1/capacity-swaps/{swapKey}/activate", swapKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());
    }

    // ---------- swapKey 跨请求唯一 ----------
    @Test
    void duplicateSwapKeyRejected() throws Exception {
        String section = key("SEC");
        Plan a = publishPlan("A", section, 8, 10);
        Plan b = publishPlan("B", section, 10, 12);
        List<JsonNode> items = List.of(
                swapItem(a.key, 1, List.of(occ("TA", section, 8, 10)),
                        List.of(occ("TA", section, 10, 12))),
                swapItem(b.key, 1, List.of(occ("TB", section, 10, 12)),
                        List.of(occ("TB", section, 8, 10))));
        String swapKey = key("SWAP");
        createSwap(swapKey, items);

        // 不同 requestKey 复用同一 swapKey → 409
        mvc.perform(post("/api/v1/capacity-swaps").contentType(MediaType.APPLICATION_JSON)
                        .content(swapCreateBody(key("REQ"), swapKey, items)))
                .andExpect(status().isConflict());
    }

    // ---------- 激活时 409 分支 ----------

    @Test
    void activationRejectsVersionStateAndCurrentMismatch() throws Exception {
        String section = key("SEC");
        Plan a = publishPlan("A", section, 8, 10);
        Plan b = publishPlan("B", section, 10, 12);

        // 1) 期望版本错误
        List<JsonNode> wrongVersion = List.of(
                swapItem(a.key, 7, List.of(occ("TA", section, 8, 10)),
                        List.of(occ("TA", section, 10, 12))),
                swapItem(b.key, 1, List.of(occ("TB", section, 10, 12)),
                        List.of(occ("TB", section, 8, 10))));
        String swap1 = key("SWAP");
        createSwap(swap1, wrongVersion);
        expectActivateConflict(swap1, "VERSION_CONFLICT");

        // 2) 当前占用不精确匹配
        List<JsonNode> wrongCurrent = List.of(
                swapItem(a.key, 1, List.of(occ("TA", section, 8, 9)),
                        List.of(occ("TA", section, 10, 12))),
                swapItem(b.key, 1, List.of(occ("TB", section, 10, 12)),
                        List.of(occ("TB", section, 8, 10))));
        String swap2 = key("SWAP");
        createSwap(swap2, wrongCurrent);
        expectActivateConflict(swap2, "CURRENT_OCCUPANCY_MISMATCH");

        // 3) 计划已取消：不是 PUBLISHED
        cancelPlan(b.key, key("REQ"));
        List<JsonNode> cancelled = List.of(
                swapItem(a.key, 1, List.of(occ("TA", section, 8, 10)),
                        List.of(occ("TA", section, 10, 12))),
                swapItem(b.key, 1, List.of(occ("TB", section, 10, 12)),
                        List.of(occ("TB", section, 8, 10))));
        String swap3 = key("SWAP");
        // 预览本身允许已取消计划存在（只查存在与运营日），激活时裁决状态
        createSwap(swap3, cancelled);
        expectActivateConflict(swap3, "PLAN_STATE_CONFLICT");

        // 全部失败后 A 的占用与版本不变，没有任何部分释放
        assertPersisted(a.key, 1, "PUBLISHED", "TA", section, 8, 10);
    }

    // ---------- 400 / 404 ----------

    @Test
    void invalidRequestsRejected() throws Exception {
        String section = key("SEC");
        Plan a = publishPlan("A", section, 8, 10);
        Plan b = publishPlan("B", section, 10, 12);
        String oneItem = objectMapper.writeValueAsString(swapItem(a.key, 1,
                List.of(occ("TA", section, 8, 10)), List.of(occ("TA", section, 10, 12))));

        // 仅 1 个参与项
        mvc.perform(post("/api/v1/capacity-swaps").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"swapKey\":\"" + key("SWAP")
                                + "\",\"opDate\":\"" + DAY + "\",\"items\":[" + oneItem + "]}"))
                .andExpect(status().isBadRequest());

        // 交换单内计划重复
        List<JsonNode> dup = List.of(
                swapItem(a.key, 1, List.of(occ("TA", section, 8, 10)),
                        List.of(occ("TA", section, 10, 12))),
                swapItem(a.key, 1, List.of(occ("TA", section, 8, 10)),
                        List.of(occ("TA", section, 8, 9))));
        mvc.perform(post("/api/v1/capacity-swaps").contentType(MediaType.APPLICATION_JSON)
                        .content(swapCreateBody(key("REQ"), key("SWAP"), dup)))
                .andExpect(status().isConflict());

        // 参与计划不存在
        List<JsonNode> missing = List.of(
                swapItem("SCH-NO-SUCH-" + UUID.randomUUID(), 1,
                        List.of(occ("TA", section, 8, 10)), List.of(occ("TA", section, 10, 12))),
                swapItem(b.key, 1, List.of(occ("TB", section, 10, 12)),
                        List.of(occ("TB", section, 8, 10))));
        mvc.perform(post("/api/v1/capacity-swaps").contentType(MediaType.APPLICATION_JSON)
                        .content(swapCreateBody(key("REQ"), key("SWAP"), missing)))
                .andExpect(status().isNotFound());

        // 占用结束早于开始
        List<JsonNode> badOcc = List.of(
                swapItem(a.key, 1, List.of(occ("TA", section, 8, 10)),
                        List.of(occ("TA", section, 10, 9))),
                swapItem(b.key, 1, List.of(occ("TB", section, 10, 12)),
                        List.of(occ("TB", section, 8, 10))));
        mvc.perform(post("/api/v1/capacity-swaps").contentType(MediaType.APPLICATION_JSON)
                        .content(swapCreateBody(key("REQ"), key("SWAP"), badOcc)))
                .andExpect(status().isBadRequest());

        // 运营日不一致
        List<JsonNode> wrongDay = List.of(
                swapItem(a.key, 1, List.of(occ("TA", section, 8, 10)),
                        List.of(occ("TA", section, 10, 12))),
                swapItem(b.key, 1, List.of(occ("TB", section, 10, 12)),
                        List.of(occ("TB", section, 8, 10))));
        mvc.perform(post("/api/v1/capacity-swaps").contentType(MediaType.APPLICATION_JSON)
                        .content(swapCreateBodyForDate(key("REQ"), key("SWAP"),
                                DAY.plusDays(1), wrongDay)))
                .andExpect(status().isConflict());

        // 不存在的交换单：激活 404、证据 404
        mvc.perform(post("/api/v1/capacity-swaps/{key}/activate", key("NO-SWAP"))
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/capacity-swaps/{key}/evidence", key("NO-SWAP")))
                .andExpect(status().isNotFound());
    }

    // ---------- 辅助 ----------

    private record Plan(String key) {
    }

    private Plan publishPlan(String prefix, String section, int startHour, int endHour)
            throws Exception {
        String scheduleKey = key("SCH-" + prefix);
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey,
                                occ("T" + prefix, section, startHour, endHour))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        return new Plan(scheduleKey);
    }

    private void cancelPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk());
    }

    private void createSwap(String swapKey, List<JsonNode> items) throws Exception {
        mvc.perform(post("/api/v1/capacity-swaps").contentType(MediaType.APPLICATION_JSON)
                        .content(swapCreateBody(key("REQ"), swapKey, items)))
                .andExpect(status().isCreated());
    }

    private void expectActivateConflict(String swapKey, String code) throws Exception {
        MvcResult result = mvc.perform(
                        post("/api/v1/capacity-swaps/{swapKey}/activate", swapKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(actionBody(key("REQ"))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo(code);
    }

    private void assertPersisted(String scheduleKey, int version, String status, String train,
                                 String section, int startHour, int endHour) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(detail);
        assertThat(body.get("version").asInt()).isEqualTo(version);
        assertThat(body.get("status").asText()).isEqualTo(status);
        JsonNode occupancies = body.get("occupancies");
        assertThat(occupancies).hasSize(1);
        assertThat(occupancies.get(0).get("trainNo").asText()).isEqualTo(train);
        assertThat(occupancies.get(0).get("sectionId").asText()).isEqualTo(section);
        assertThat(occupancies.get(0).get("startUtc").asText()).isEqualTo(iso(startHour));
        assertThat(occupancies.get(0).get("endUtc").asText()).isEqualTo(iso(endHour));
    }

    private void assertPlanTarget(JsonNode planView, String scheduleKey, String train,
                                  String section, int startHour, int endHour) {
        assertThat(planView.get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(planView.get("version").asInt()).isEqualTo(2);
        JsonNode o = planView.get("occupancies");
        assertThat(o).hasSize(1);
        assertThat(o.get(0).get("trainNo").asText()).isEqualTo(train);
        assertThat(o.get(0).get("sectionId").asText()).isEqualTo(section);
        assertThat(o.get(0).get("startUtc").asText()).isEqualTo(iso(startHour));
        assertThat(o.get(0).get("endUtc").asText()).isEqualTo(iso(endHour));
    }

    private List<String> slotScheduleKeys(String section) throws Exception {
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        List<String> keys = new ArrayList<>();
        read(slots).forEach(n -> keys.add(n.get("scheduleKey").asText()));
        return keys;
    }

    private Map<String, JsonNode> indexByKey(JsonNode array) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        array.forEach(n -> map.put(n.get("scheduleKey").asText(), n));
        return map;
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant().toString();
    }

    private String occ(String train, String section, int startHour, int endHour) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + iso(startHour) + "\",\"endUtc\":\"" + iso(endHour) + "\"}";
    }

    private com.fasterxml.jackson.databind.node.ObjectNode swapItem(String scheduleKey,
                              int expectedVersion, List<String> current, List<String> target) {
        com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
        node.put("scheduleKey", scheduleKey);
        node.put("expectedVersion", expectedVersion);
        node.set("currentOccupancies", arrayOf(current));
        node.set("targetOccupancies", arrayOf(target));
        return node;
    }

    private JsonNode arrayOf(List<String> raw) {
        try {
            return objectMapper.readTree("[" + String.join(",", raw) + "]");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String swapCreateBody(String requestKey, String swapKey, List<JsonNode> items)
            throws Exception {
        return swapCreateBodyForDate(requestKey, swapKey, DAY, items);
    }

    private String swapCreateBodyForDate(String requestKey, String swapKey, LocalDate opDate,
                                         List<JsonNode> items) throws Exception {
        return objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("requestKey", requestKey)
                .put("swapKey", swapKey)
                .put("opDate", opDate.toString())
                .set("items", objectMapper.valueToTree(items)));
    }

    private String createBody(String requestKey, String scheduleKey, String occupancy) {
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":[" + occupancy + "]}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }
}
