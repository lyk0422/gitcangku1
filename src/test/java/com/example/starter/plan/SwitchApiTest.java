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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 区段封锁切换单 API 测试（H2 内存库）：登记/预览、原子激活主流程、
 * 各类失败整体回滚、幂等重放与键复用、查询只读。各用例以唯一业务键隔离数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SwitchApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 23);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 登记与预览 ----------

    @Test
    void registerSwitchAndPreviewEmptyThenAffected() throws Exception {
        String section = key("SEC");
        String switchKey = key("SW");

        // 尚无计划与窗口相交：预览为空且不写业务数据
        register(switchKey, section, iso(8), iso(12));
        MvcResult emptyPreview = mvc.perform(get("/api/v1/switches/{key}/preview", switchKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode emptyBody = read(emptyPreview);
        assertThat(emptyBody.get("switchInfo").get("status").asText()).isEqualTo("REGISTERED");
        assertThat(emptyBody.get("affectedPlans")).isEmpty();

        // 登记后查询详情为 REGISTERED、无映射
        MvcResult detail = mvc.perform(get("/api/v1/switches/{key}", switchKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("switchInfo").get("status").asText()).isEqualTo("REGISTERED");
        assertThat(read(detail).get("mappings")).isEmpty();

        // 新增一个与窗口相交的已发布计划和一个不相交的已发布计划
        String inside = key("SCH");
        String outside = key("SCH");
        createPlan(inside, occ("G1", section, iso(8), iso(9)));
        publishPlan(inside, key("REQ"));
        String otherSection = key("SEC");
        createPlan(outside, occ("G2", otherSection, iso(8), iso(9)));
        publishPlan(outside, key("REQ"));

        MvcResult preview = mvc.perform(get("/api/v1/switches/{key}/preview", switchKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode affected = read(preview).get("affectedPlans");
        assertThat(affected).hasSize(1);
        assertThat(affected.get(0).get("scheduleKey").asText()).isEqualTo(inside);
        assertThat(affected.get(0).get("version").asInt()).isEqualTo(1);
        assertThat(affected.get(0).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(affected.get(0).get("occupancies")).hasSize(1);
        assertThat(affected.get(0).get("occupancies").get(0).get("sectionId").asText())
                .isEqualTo(section);
    }

    @Test
    void registerRejectsInvalidWindowAndMissingSwitch() throws Exception {
        // 结束早于开始 → 400
        mvc.perform(post("/api/v1/switches").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("SW"), key("SEC"), iso(12), iso(8))))
                .andExpect(status().isBadRequest());
        // 查询不存在的切换单 → 404
        mvc.perform(get("/api/v1/switches/{key}/preview", key("SW")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/switches/{key}", key("SW")))
                .andExpect(status().isNotFound());
    }

    // ---------- 激活主流程 ----------

    @Test
    void activateSuspendsOldPublishesReplacementsWritesLinksAndSnapshot() throws Exception {
        String section = key("SEC");
        String switchKey = key("SW");
        String old1 = key("SCH");
        String old2 = key("SCH");
        String repl1 = key("SCH");
        String repl2 = key("SCH");
        createPlan(old1, occ("G1", section, iso(8), iso(9)));
        createPlan(old2, occ("G2", section, iso(10), iso(11)));
        publishPlan(old1, key("REQ"));
        publishPlan(old2, key("REQ"));
        // 替代草稿复用各自旧计划时隙（激活校验排除全部旧计划），且彼此不冲突
        createPlan(repl1, occ("R1", section, iso(8), iso(9)));
        createPlan(repl2, occ("R2", section, iso(10), iso(11)));
        register(switchKey, section, iso(8), iso(12));

        MvcResult result = mvc.perform(post("/api/v1/switches/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(key("REQ"),
                                mapping(old1, repl1, 1, 1),
                                mapping(old2, repl2, 1, 1))))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("switchInfo").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("mappings")).hasSize(2);

        // 旧计划 SUSPENDED 且占用历史保留；替代计划 PUBLISHED
        assertPlan(old1, "SUSPENDED", "G1", iso(8), iso(9));
        assertPlan(old2, "SUSPENDED", "G2", iso(10), iso(11));
        assertPlan(repl1, "PUBLISHED", "R1", iso(8), iso(9));
        assertPlan(repl2, "PUBLISHED", "R2", iso(10), iso(11));

        // 生效时隙全部归属替代计划，ACTIVE 封锁窗口内无旧占用
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        JsonNode slotList = read(slots);
        assertThat(slotList).hasSize(2);
        assertThat(slotList).extracting(n -> n.get("scheduleKey").asText())
                .containsExactlyInAnyOrder(repl1, repl2);

        // 详情查询返回 ACTIVE 快照，映射含前后占用
        MvcResult detail = mvc.perform(get("/api/v1/switches/{key}", switchKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode detailBody = read(detail);
        assertThat(detailBody.get("switchInfo").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(detailBody.get("mappings")).hasSize(2);
        for (JsonNode m : detailBody.get("mappings")) {
            assertThat(m.get("oldStatus").asText()).isEqualTo("SUSPENDED");
            assertThat(m.get("replacementStatus").asText()).isEqualTo("PUBLISHED");
            assertThat(m.get("beforeOccupancies")).hasSize(1);
            assertThat(m.get("afterOccupancies")).hasSize(1);
        }
    }

    @Test
    void activateFreesSlotsOnlyForSuspendedPlansUnaffectedPlanKeepsSlot() throws Exception {
        String section = key("SEC");
        String switchKey = key("SW");
        // thirdParty 与窗口不相交（13:00-14:00，窗口 08-12），激活后时隙保留
        String thirdParty = key("SCH");
        createPlan(thirdParty, occ("G9", section, iso(13), iso(14)));
        publishPlan(thirdParty, key("REQ"));

        String oldKey = key("SCH");
        String replKey = key("SCH");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        createPlan(replKey, occ("R1", section, iso(8), iso(9)));
        register(switchKey, section, iso(8), iso(12));
        activate(switchKey, key("REQ"), mapping(oldKey, replKey, 1, 1));

        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        JsonNode slotList = read(slots);
        assertThat(slotList).hasSize(2);
        assertThat(slotList).extracting(n -> n.get("scheduleKey").asText())
                .containsExactlyInAnyOrder(thirdParty, replKey);
    }

    // ---------- 激活失败：影响集合不完整 409 与整体回滚 ----------

    @Test
    void activateRejectsMissingAffectedPlanAndRollsBack() throws Exception {
        String section = key("SEC");
        String switchKey = key("SW");
        String old1 = key("SCH");
        String old2 = key("SCH");
        String repl1 = key("SCH");
        createPlan(old1, occ("G1", section, iso(8), iso(9)));
        createPlan(old2, occ("G2", section, iso(10), iso(11)));
        publishPlan(old1, key("REQ"));
        publishPlan(old2, key("REQ"));
        createPlan(repl1, occ("R1", section, iso(8), iso(9)));
        register(switchKey, section, iso(8), iso(12));

        // 只提交 old1，遗漏 old2 → 409
        MvcResult result = mvc.perform(post("/api/v1/switches/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(key("REQ"), mapping(old1, repl1, 1, 1))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("AFFECTED_SET_MISMATCH");
        assertSwitchUnchanged(switchKey, new String[]{old1, old2}, new String[]{repl1}, section);
    }

    @Test
    void activateRejectsExtraNonIntersectingPlanAndRollsBack() throws Exception {
        String section = key("SEC");
        String switchKey = key("SW");
        String old1 = key("SCH");
        String oldExtra = key("SCH");
        String repl1 = key("SCH");
        String replExtra = key("SCH");
        createPlan(old1, occ("G1", section, iso(8), iso(9)));
        publishPlan(old1, key("REQ"));
        // oldExtra 在窗口外（窗口 08-12，占用 13-14）
        createPlan(oldExtra, occ("G2", section, iso(13), iso(14)));
        publishPlan(oldExtra, key("REQ"));
        createPlan(repl1, occ("R1", section, iso(8), iso(9)));
        createPlan(replExtra, occ("R2", section, iso(13), iso(14)));
        register(switchKey, section, iso(8), iso(12));

        MvcResult result = mvc.perform(post("/api/v1/switches/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(key("REQ"),
                                mapping(old1, repl1, 1, 1),
                                mapping(oldExtra, replExtra, 1, 1))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("AFFECTED_SET_MISMATCH");
        assertSwitchUnchanged(switchKey, new String[]{old1, oldExtra},
                new String[]{repl1, replExtra}, section);
    }

    // ---------- 激活失败：版本/状态/运营日/重复/引用 409 ----------

    @Test
    void activateRejectsVersionStateOpDateAndDuplicateConflicts() throws Exception {
        // 场景一：替代计划已发布（非草稿）→ 409 PLAN_STATE_CONFLICT
        String section1 = key("SEC");
        String oldPublished = key("SCH");
        String replPublished = key("SCH");
        createPlan(oldPublished, occ("G2", section1, iso(8), iso(9)));
        publishPlan(oldPublished, key("REQ"));
        // 替代计划已发布（占用窗口外时隙，避免普通发布时与旧计划冲突）
        createPlan(replPublished, occ("R2", section1, iso(12), iso(13)));
        publishPlan(replPublished, key("REQ"));
        String switchPublished = registerSwitch(section1);
        MvcResult stateResult = mvc.perform(post("/api/v1/switches/{key}/activate", switchPublished)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(key("REQ"),
                                mapping(oldPublished, replPublished, 1, 1))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(stateResult).get("code").asText()).isEqualTo("PLAN_STATE_CONFLICT");
        // 回滚：切换单仍登记、旧计划仍发布；替代计划保持其原有已发布状态不变
        assertThat(getSwitchStatus(switchPublished)).isEqualTo("REGISTERED");
        assertThat(getPlanStatus(oldPublished)).isEqualTo("PUBLISHED");
        assertThat(getPlanStatus(replPublished)).isEqualTo("PUBLISHED");

        // 场景二：期望版本不匹配 → 409 VERSION_CONFLICT
        String section2 = key("SEC");
        String oldV = key("SCH");
        String replV = key("SCH");
        createPlan(oldV, occ("G3", section2, iso(8), iso(9)));
        publishPlan(oldV, key("REQ"));
        createPlan(replV, occ("R3", section2, iso(8), iso(9)));
        String switchV = registerSwitch(section2);
        MvcResult versionResult = mvc.perform(post("/api/v1/switches/{key}/activate", switchV)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(key("REQ"), mapping(oldV, replV, 9, 1))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(versionResult).get("code").asText()).isEqualTo("VERSION_CONFLICT");
        assertSwitchUnchanged(switchV, new String[]{oldV}, new String[]{replV}, section2);

        // 场景三：运营日不同 → 409 OP_DATE_MISMATCH
        String section3 = key("SEC");
        String oldD = key("SCH");
        String replD = key("SCH");
        createPlan(oldD, occ("G4", section3, iso(8), iso(9)));
        publishPlan(oldD, key("REQ"));
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBodyOnDate(key("REQ"), replD, DAY.plusDays(1),
                                occ("R4", section3, iso(8, 0, 1), iso(9, 0, 1)))))
                .andExpect(status().isCreated());
        String switchD = registerSwitch(section3);
        MvcResult dateResult = mvc.perform(post("/api/v1/switches/{key}/activate", switchD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(key("REQ"), mapping(oldD, replD, 1, 1))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(dateResult).get("code").asText()).isEqualTo("OP_DATE_MISMATCH");
        assertSwitchUnchanged(switchD, new String[]{oldD}, new String[]{replD}, section3);

        // 场景四：同一替代计划重复映射给两个旧计划 → 409 MAPPING_CONFLICT
        String section4 = key("SEC");
        String oldA = key("SCH");
        String oldB = key("SCH");
        String replDup = key("SCH");
        createPlan(oldA, occ("G5", section4, iso(8), iso(9)));
        createPlan(oldB, occ("G6", section4, iso(10), iso(11)));
        publishPlan(oldA, key("REQ"));
        publishPlan(oldB, key("REQ"));
        createPlan(replDup, occ("R5", section4, iso(8), iso(9)));
        String switchDup = registerSwitch(section4);
        MvcResult dupResult = mvc.perform(post("/api/v1/switches/{key}/activate", switchDup)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(key("REQ"),
                                mapping(oldA, replDup, 1, 1),
                                mapping(oldB, replDup, 1, 1))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(dupResult).get("code").asText()).isEqualTo("MAPPING_CONFLICT");
        assertSwitchUnchanged(switchDup, new String[]{oldA, oldB}, new String[]{replDup}, section4);

        // 场景五：引用不存在的计划 → 409 MAPPING_CONFLICT
        String section5 = key("SEC");
        String oldRef = key("SCH");
        createPlan(oldRef, occ("G7", section5, iso(8), iso(9)));
        publishPlan(oldRef, key("REQ"));
        String switchRef = registerSwitch(section5);
        mvc.perform(post("/api/v1/switches/{key}/activate", switchRef)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(key("REQ"), mapping(oldRef, key("SCH"), 1, 1))))
                .andExpect(status().isConflict());
        assertSwitchUnchanged(switchRef, new String[]{oldRef}, new String[]{}, section5);
    }

    // ---------- 激活失败：改签链环 409 ----------

    @Test
    void activateCoexistsWithExistingRescheduleChain() throws Exception {
        // 既有改签链 A(CANCELLED) -> B(CANCELLED) -> C(PUBLISHED)，切换作用于链尾 C
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        String planC = key("SCH");
        String replD = key("SCH");
        createPlan(planA, occ("G1", section, iso(8), iso(9)));
        publishPlan(planA, key("REQ"));
        createPlan(planB, occ("G2", section, iso(8), iso(9)));
        reschedule(planA, planB, 1, 1);
        createPlan(planC, occ("G3", section, iso(8), iso(9)));
        reschedule(planB, planC, 1, 1);
        createPlan(replD, occ("R1", section, iso(8), iso(9)));

        String switchKey = registerSwitch(section);
        MvcResult result = mvc.perform(post("/api/v1/switches/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(key("REQ"), mapping(planC, replD, 1, 1))))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(result).get("switchInfo").get("status").asText()).isEqualTo("ACTIVE");
        assertPlan(planC, "SUSPENDED", "G3", iso(8), iso(9));
        assertPlan(replD, "PUBLISHED", "R1", iso(8), iso(9));

        // 原改签链仍可完整查询，替代关系独立存在
        MvcResult chain = mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", planA))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(chain).get("chain")).hasSize(3);
    }

    @Test
    void activateRejectsReplacementLinkedToOldByRescheduleChain() throws Exception {
        // 直接构造历史改签关联（替代草稿 R 被登记为旧计划 O 的直接后继），
        // 模拟“替代计划是旧计划祖先或后继”的链环数据：激活必须 409 LINK_CONFLICT 并整体回滚。
        String section = key("SEC");
        String oldKey = key("SCH");
        String replKey = key("SCH");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        createPlan(replKey, occ("R1", section, iso(8), iso(9)));
        long oldId = planId(oldKey);
        long replId = planId(replKey);
        jdbc.update("INSERT INTO rail_plan_reschedule_link"
                        + " (predecessor_plan_id, successor_plan_id, created_at) VALUES (?, ?, ?)",
                oldId, replId, System.currentTimeMillis());
        String switchKey = registerSwitch(section);

        MvcResult result = mvc.perform(post("/api/v1/switches/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(key("REQ"), mapping(oldKey, replKey, 1, 1))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("LINK_CONFLICT");
        assertSwitchUnchanged(switchKey, new String[]{oldKey}, new String[]{replKey}, section);
    }

    // ---------- 激活失败：时隙冲突 422 与整体回滚 ----------

    @Test
    void activateRejectsMutualReplacementSlotConflictAndRollsBack() throws Exception {
        String section = key("SEC");
        String old1 = key("SCH");
        String old2 = key("SCH");
        String repl1 = key("SCH");
        String repl2 = key("SCH");
        createPlan(old1, occ("G1", section, iso(8), iso(9)));
        createPlan(old2, occ("G2", section, iso(10), iso(11)));
        publishPlan(old1, key("REQ"));
        publishPlan(old2, key("REQ"));
        // 两个替代草稿在同一区段时隙重叠
        createPlan(repl1, occ("R1", section, iso(8), iso(10)));
        createPlan(repl2, occ("R2", section, iso(9), iso(10)));
        String switchKey = registerSwitch(section);

        MvcResult result = mvc.perform(post("/api/v1/switches/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(key("REQ"),
                                mapping(old1, repl1, 1, 1),
                                mapping(old2, repl2, 1, 1))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("SLOT_CONFLICT");
        assertThat(read(result).get("details").get(0).get("type").asText())
                .isEqualTo("REPLACEMENT_CONFLICT");
        assertSwitchUnchanged(switchKey, new String[]{old1, old2},
                new String[]{repl1, repl2}, section);
    }

    @Test
    void activateRejectsConflictWithUnaffectedPublishedPlanAndRollsBack() throws Exception {
        String section = key("SEC");
        // thirdParty 与窗口相交但未被提交为旧计划——它本身会导致影响集合遗漏 409；
        // 为单独验证“与未受影响已发布计划冲突”，让 thirdParty 占用窗口外时隙，
        // 而替代计划占用延伸到窗口外与 thirdParty 重叠。
        String thirdParty = key("SCH");
        createPlan(thirdParty, occ("G9", section, iso(11), iso(12)));
        publishPlan(thirdParty, key("REQ"));

        String oldKey = key("SCH");
        String replKey = key("SCH");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        // 替代草稿占用 08-12：08-09 复用旧时隙（窗口内），11-12 与窗口外第三方冲突
        createPlan(replKey, occ("R1", section, iso(8), iso(12)));
        // 窗口 08-11，使第三方 11-12 不与窗口相交，但替代延伸段与其冲突
        String switchKey = key("SW");
        register(switchKey, section, iso(8), iso(11));

        MvcResult result = mvc.perform(post("/api/v1/switches/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(key("REQ"), mapping(oldKey, replKey, 1, 1))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        assertThat(error.get("details").get(0).get("type").asText()).isEqualTo("SECTION_CONFLICT");
        assertThat(error.get("details").get(0).get("conflictingScheduleKey").asText())
                .isEqualTo(thirdParty);
        assertSwitchUnchanged(switchKey, new String[]{oldKey}, new String[]{replKey}, section);
    }

    // ---------- 幂等 ----------

    @Test
    void registerIdempotentReplayAndKeyConflicts() throws Exception {
        String section = key("SEC");
        String switchKey = key("SW");
        String requestKey = key("REQ");
        String body = registerBody(requestKey, switchKey, section, iso(8), iso(12));
        MvcResult first = mvc.perform(post("/api/v1/switches").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/switches").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同 requestKey 不同参数 → 409
        mvc.perform(post("/api/v1/switches").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, key("SW"), section, iso(8), iso(12))))
                .andExpect(status().isConflict());
        // 同 switchKey 换 requestKey 复用 → 409
        mvc.perform(post("/api/v1/switches").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), switchKey, section, iso(8), iso(12))))
                .andExpect(status().isConflict());
    }

    @Test
    void activateIdempotentReplayIgnoresMappingOrderAndRejectsDifferentParams() throws Exception {
        String section = key("SEC");
        String switchKey = key("SW");
        String old1 = key("SCH");
        String old2 = key("SCH");
        String repl1 = key("SCH");
        String repl2 = key("SCH");
        createPlan(old1, occ("G1", section, iso(8), iso(9)));
        createPlan(old2, occ("G2", section, iso(10), iso(11)));
        publishPlan(old1, key("REQ"));
        publishPlan(old2, key("REQ"));
        createPlan(repl1, occ("R1", section, iso(8), iso(9)));
        createPlan(repl2, occ("R2", section, iso(10), iso(11)));
        register(switchKey, section, iso(8), iso(12));

        String requestKey = key("REQ");
        String bodyOrdered = activateBody(requestKey,
                mapping(old1, repl1, 1, 1), mapping(old2, repl2, 1, 1));
        MvcResult first = mvc.perform(post("/api/v1/switches/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON).content(bodyOrdered))
                .andExpect(status().isOk()).andReturn();

        // 映射换序 + 已激活状态：重放首次快照
        String bodyReversed = activateBody(requestKey,
                mapping(old2, repl2, 1, 1), mapping(old1, repl1, 1, 1));
        MvcResult replay = mvc.perform(post("/api/v1/switches/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON).content(bodyReversed))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同参数（改掉一个期望版本）→ 409
        mvc.perform(post("/api/v1/switches/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(requestKey,
                                mapping(old1, repl1, 2, 1), mapping(old2, repl2, 1, 1))))
                .andExpect(status().isConflict());
    }

    @Test
    void activateFailureDoesNotConsumeRequestKey() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        String replKey = key("SCH");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        // 替代草稿内部无法构造列车重叠（占用创建时允许同列车重叠，仅发布/切换校验）
        createPlanRaw(replKey,
                occ("R1", section, iso(8), iso(10)),
                occ("R1", section, iso(9), iso(11)));
        String switchKey = registerSwitch(section);

        String requestKey = key("REQ");
        // 首次激活 422（替代草稿同列车重叠）
        mvc.perform(post("/api/v1/switches/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(requestKey, mapping(oldKey, replKey, 1, 1))))
                .andExpect(status().isUnprocessableEntity());

        // 失败不占键：修正草稿（版本升到 2）后同键新参重试成功
        mvc.perform(put("/api/v1/plans/{key}/occupancies", replKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, occ("R1", section, iso(8), iso(9)))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/switches/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(requestKey, mapping(oldKey, replKey, 1, 2))))
                .andExpect(status().isOk());
        assertPlan(oldKey, "SUSPENDED", "G1", iso(8), iso(9));
    }

    // ---------- 辅助 ----------

    private void assertSwitchUnchanged(String switchKey, String[] oldKeys, String[] replKeys,
                                       String section) throws Exception {
        JsonNode info = read(mvc.perform(get("/api/v1/switches/{key}", switchKey))
                .andExpect(status().isOk()).andReturn()).get("switchInfo");
        assertThat(info.get("status").asText()).isEqualTo("REGISTERED");
        for (String oldKey : oldKeys) {
            MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", oldKey))
                    .andExpect(status().isOk()).andReturn();
            assertThat(read(detail).get("status").asText()).isEqualTo("PUBLISHED");
        }
        for (String replKey : replKeys) {
            MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", replKey))
                    .andExpect(status().isOk()).andReturn();
            assertThat(read(detail).get("status").asText()).isEqualTo("DRAFT");
        }
        // 旧计划仍 PUBLISHED 故仍占用区段时隙（状态校验已保证替代草稿未发布、无部分切换）
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        java.util.List<String> slotKeys = new java.util.ArrayList<>();
        read(slots).forEach(n -> slotKeys.add(n.get("scheduleKey").asText()));
        for (String oldKey : oldKeys) {
            assertThat(slotKeys).contains(oldKey);
        }
    }

    private void assertPlan(String scheduleKey, String status, String trainNo, String startIso,
                            String endIso) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(detail);
        assertThat(body.get("status").asText()).isEqualTo(status);
        assertThat(body.get("occupancies")).hasSize(1);
        assertThat(body.get("occupancies").get(0).get("trainNo").asText()).isEqualTo(trainNo);
        assertThat(body.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(startIso);
        assertThat(body.get("occupancies").get(0).get("endUtc").asText()).isEqualTo(endIso);
    }

    private String getPlanStatus(String scheduleKey) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(detail).get("status").asText();
    }

    private String getSwitchStatus(String switchKey) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/switches/{key}", switchKey))
                .andExpect(status().isOk()).andReturn();
        return read(detail).get("switchInfo").get("status").asText();
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey, occupancies)))
                .andExpect(status().isCreated());
    }

    private void createPlanRaw(String scheduleKey, String... occupancies) throws Exception {
        createPlan(scheduleKey, occupancies);
    }

    private void publishPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk());
    }

    private void reschedule(String oldKey, String newKey, int oldVersion, int newVersion)
            throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, oldVersion, newVersion)))
                .andExpect(status().isOk());
    }

    private void register(String switchKey, String section, String startIso, String endIso)
            throws Exception {
        mvc.perform(post("/api/v1/switches").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), switchKey, section, startIso, endIso)))
                .andExpect(status().isCreated());
    }

    private String registerSwitch(String section) throws Exception {
        String switchKey = key("SW");
        register(switchKey, section, iso(8), iso(12));
        return switchKey;
    }

    private void activate(String switchKey, String requestKey, String... mappings) throws Exception {
        mvc.perform(post("/api/v1/switches/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(requestKey, mappings)))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long planId(String scheduleKey) {
        return jdbc.queryForObject("SELECT id FROM rail_day_plan WHERE schedule_key = ?",
                Long.class, scheduleKey);
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    private static String iso(int hour) {
        return iso(hour, 0);
    }

    private static String iso(int hour, int minute, int dayOffset) {
        Instant instant = DAY.plusDays(dayOffset).atTime(hour, minute).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String createBody(String requestKey, String scheduleKey, String... occupancies) {
        return createBodyOnDate(requestKey, scheduleKey, DAY, occupancies);
    }

    private static String createBodyOnDate(String requestKey, String scheduleKey, LocalDate opDate,
                                           String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + opDate + "\",\"occupancies\":[" + String.join(",", occupancies)
                + "]}";
    }

    private static String updateBody(String requestKey, int expectedVersion, String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"occupancies\":[" + String.join(",", occupancies) + "]}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }

    private static String registerBody(String requestKey, String switchKey, String sectionId,
                                       String startIso, String endIso) {
        return "{\"requestKey\":\"" + requestKey + "\",\"switchKey\":\"" + switchKey
                + "\",\"sectionId\":\"" + sectionId + "\",\"startUtc\":\"" + startIso
                + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String mapping(String oldKey, String replKey, int oldVersion, int replVersion) {
        return "{\"oldScheduleKey\":\"" + oldKey + "\",\"replacementScheduleKey\":\"" + replKey
                + "\",\"expectedOldVersion\":" + oldVersion
                + ",\"expectedReplacementVersion\":" + replVersion + "}";
    }

    private static String activateBody(String requestKey, String... mappings) {
        return "{\"requestKey\":\"" + requestKey + "\",\"mappings\":[" + String.join(",", mappings)
                + "]}";
    }
}
