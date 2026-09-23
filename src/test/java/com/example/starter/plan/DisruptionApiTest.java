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
 * 区段封锁切换 API 端到端测试（H2 内存库）：登记/预览/提交/激活主流程、
 * 失败分支与整体回滚、幂等（同参换序重放、异参 409、失败不占键、switchKey 换请求复用 409）。
 * 各用例使用唯一业务键，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DisruptionApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 主流程 ----------

    @Test
    void registerPreviewSubmitActivateAtomicallySwitchesCluster() throws Exception {
        String blockedSection = key("SEC");
        String altSection = key("SEC");
        String oldKey = key("OLD");
        String replKey = key("REP");
        createDraft(oldKey, occ("G1", blockedSection, iso(8), iso(9)));
        publishPlan(oldKey);
        createDraft(replKey, occ("G1X", altSection, iso(8), iso(9)));

        String switchKey = key("SW");
        // 登记
        MvcResult registered = mvc.perform(post("/api/v1/disruptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), switchKey, blockedSection, iso(7), iso(10))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode sw = read(registered);
        assertThat(sw.get("switchKey").asText()).isEqualTo(switchKey);
        assertThat(sw.get("status").asText()).isEqualTo("REGISTERED");

        // 预览：相交旧计划 O（08-09 落在 07-10 窗口内）
        MvcResult preview = mvc.perform(get("/api/v1/disruptions/{key}/preview", switchKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode previewBody = read(preview);
        assertThat(previewBody.get("affectedPlans")).hasSize(1);
        assertThat(previewBody.get("affectedPlans").get(0).get("scheduleKey").asText())
                .isEqualTo(oldKey);
        assertThat(previewBody.get("affectedPlans").get(0).get("version").asInt()).isEqualTo(1);

        // 提交映射
        mvc.perform(put("/api/v1/disruptions/{key}/mappings", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(mapping(oldKey, replKey))))
                .andExpect(status().isOk());

        // 激活
        String activateReq = key("REQ");
        MvcResult activated = mvc.perform(post("/api/v1/disruptions/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(activateReq, mapping(oldKey, replKey))))
                .andExpect(status().isOk()).andReturn();
        JsonNode detail = read(activated);
        assertThat(detail.get("disruption").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(detail.get("mappings")).hasSize(1);
        assertThat(detail.get("beforePlans").get(0).get("scheduleKey").asText()).isEqualTo(oldKey);
        assertThat(detail.get("beforePlans").get(0).get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(detail.get("beforePlans").get(0).get("occupancies")).hasSize(1);
        assertThat(detail.get("afterPlans").get(0).get("scheduleKey").asText()).isEqualTo(replKey);
        assertThat(detail.get("afterPlans").get(0).get("status").asText()).isEqualTo("PUBLISHED");

        // 旧时隙释放、替代时隙生效
        assertThat(publishedSlots(blockedSection)).isEmpty();
        JsonNode altSlots = publishedSlots(altSection);
        assertThat(altSlots).hasSize(1);
        assertThat(altSlots.get(0).get("scheduleKey").asText()).isEqualTo(replKey);

        // 计划终态与不可变替代链
        assertThat(getPlanStatus(oldKey)).isEqualTo("SUSPENDED");
        assertThat(getPlanStatus(replKey)).isEqualTo("PUBLISHED");
        Integer links = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_disruption_replace_link", Integer.class);
        assertThat(links).isGreaterThanOrEqualTo(1);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_disruption_snapshot s"
                        + " JOIN rail_disruption_switch d ON d.id = s.switch_id"
                        + " WHERE d.switch_key = ?", Integer.class, switchKey);
        assertThat(snapshots).isEqualTo(1);

        // 查询返回激活时不可变快照
        MvcResult queried = mvc.perform(get("/api/v1/disruptions/{key}", switchKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(queried)).isEqualTo(detail);
    }

    @Test
    void adjacentWindowBoundaryUsesHalfOpenInterval() throws Exception {
        String section = key("SEC");
        // 占用 10:00-11:00，窗口 07:00-10:00：start(10) < windowEnd(10) 为假，不相交
        String touching = key("OLD");
        createDraft(touching, occ("G1", section, iso(10), iso(11)));
        publishPlan(touching);
        String switchKey = key("SW");
        register(switchKey, section, iso(7), iso(10));

        JsonNode previewBody = read(mvc.perform(get("/api/v1/disruptions/{key}/preview", switchKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(previewBody.get("affectedPlans")).isEmpty();
    }

    // ---------- 登记失败分支 ----------

    @Test
    void registerRejectsBadWindowAndDuplicateKey() throws Exception {
        // 结束不晚于开始
        mvc.perform(post("/api/v1/disruptions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("SW"), key("SEC"), iso(9), iso(9))))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("INVALID_ARGUMENT"));

        String switchKey = key("SW");
        register(switchKey, key("SEC"), iso(7), iso(10));
        // 同 switchKey 再登记 → 409
        mvc.perform(post("/api/v1/disruptions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), switchKey, key("SEC"), iso(7), iso(10))))
                .andExpect(status().isConflict());
    }

    @Test
    void missingSwitchReturns404() throws Exception {
        String missing = key("SW");
        mvc.perform(get("/api/v1/disruptions/{key}/preview", missing))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/disruptions/{key}", missing))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/disruptions/{key}/mappings", missing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(mapping(key("OLD"), key("REP")))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/disruptions/{key}/activate", missing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(key("REQ"), mapping(key("OLD"), key("REP")))))
                .andExpect(status().isNotFound());
    }

    // ---------- 提交失败分支 ----------

    @Test
    void submitRejectsMissingOrExtraOrUnchangedAffectedSet() throws Exception {
        String section = key("SEC");
        String old1 = key("OLD");
        String old2 = key("OLD");
        createDraft(old1, occ("G1", section, iso(8), iso(9)));
        createDraft(old2, occ("G2", section, iso(9), iso(10)));
        publishPlan(old1);
        publishPlan(old2);
        String rep1 = key("REP");
        String rep2 = key("REP");
        createDraft(rep1, occ("R1", key("SEC"), iso(8), iso(9)));
        createDraft(rep2, occ("R2", key("SEC"), iso(8), iso(9)));

        String switchKey = key("SW");
        register(switchKey, section, iso(7), iso(11));

        // 遗漏 old2 → 409
        mvc.perform(put("/api/v1/disruptions/{key}/mappings", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(mapping(old1, rep1))))
                .andExpect(status().isConflict());

        // 多余：加入窗口外的已发布计划
        String outside = key("OLD");
        String outsideRep = key("REP");
        createDraft(outside, occ("G3", section, iso(20), iso(21)));
        publishPlan(outside);
        createDraft(outsideRep, occ("R3", key("SEC"), iso(20), iso(21)));
        mvc.perform(put("/api/v1/disruptions/{key}/mappings", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(mapping(old1, rep1), mapping(old2, rep2),
                                mapping(outside, outsideRep))))
                .andExpect(status().isConflict());

        // 完整集合提交成功
        mvc.perform(put("/api/v1/disruptions/{key}/mappings", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(mapping(old1, rep1), mapping(old2, rep2))))
                .andExpect(status().isOk());

        // 查询不写数据：切换单仍 REGISTERED，映射为 2 条
        JsonNode detail = read(mvc.perform(get("/api/v1/disruptions/{key}", switchKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(detail.get("disruption").get("status").asText()).isEqualTo("REGISTERED");
        assertThat(detail.get("mappings")).hasSize(2);
        assertThat(detail.get("beforePlans")).hasSize(2);
        assertThat(detail.get("afterPlans")).hasSize(2);
    }

    @Test
    void submitRejectsNonDraftDifferentDateSamePlanAndDuplicateReplacement() throws Exception {
        String section = key("SEC");
        String old1 = key("OLD");
        String old2 = key("OLD");
        // 同区段相邻时隙（左闭右开），两张计划可同时发布且都与 07-10 窗口相交
        createDraft(old1, occ("G1", section, iso(8), iso(9)));
        createDraft(old2, occ("G2", section, iso(9), iso(10)));
        publishPlan(old1);
        publishPlan(old2);

        String switchKey = key("SW");
        register(switchKey, section, iso(7), iso(10));

        // 替代计划已发布（非 DRAFT）→ 409
        String publishedRep = key("REP");
        createDraft(publishedRep, occ("R1", key("SEC"), iso(8), iso(9)));
        publishPlan(publishedRep);
        mvc.perform(put("/api/v1/disruptions/{key}/mappings", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(mapping(old1, publishedRep))))
                .andExpect(status().isConflict());

        // 替代计划运营日不同 → 409
        String otherDateRep = key("REP");
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), otherDateRep, DAY.plusDays(1),
                                occAt(otherDateRep, key("SEC"),
                                        DAY.plusDays(1).atTime(8, 0), DAY.plusDays(1).atTime(9, 0)))))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/v1/disruptions/{key}/mappings", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(mapping(old1, otherDateRep))))
                .andExpect(status().isConflict());

        // 旧计划与替代计划相同 → 409
        mvc.perform(put("/api/v1/disruptions/{key}/mappings", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(mapping(old1, old1))))
                .andExpect(status().isConflict());

        // 两个旧计划映射到同一替代草稿 → 422
        String sharedRep = key("REP");
        createDraft(sharedRep, occ("R9", key("SEC"), iso(8), iso(9)));
        mvc.perform(put("/api/v1/disruptions/{key}/mappings", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(mapping(old1, sharedRep), mapping(old2, sharedRep))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void submitRejectsReplacementOnRescheduleChain() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        createDraft(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey);
        String replKey = key("REP");
        createDraft(replKey, occ("R1", key("SEC"), iso(8), iso(9)));

        // 直接在库中构造旧计划→替代草稿的改签链环（正常业务下草稿不会在链上），校验链环拦截
        Long oldId = planId(oldKey);
        Long repId = planId(replKey);
        jdbc.update("INSERT INTO rail_plan_reschedule_link"
                + " (predecessor_plan_id, successor_plan_id, created_at) VALUES (?, ?, ?)",
                oldId, repId, System.currentTimeMillis());

        String switchKey = key("SW");
        register(switchKey, section, iso(7), iso(10));
        MvcResult result = mvc.perform(put("/api/v1/disruptions/{key}/mappings", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(mapping(oldKey, replKey))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("LINK_CONFLICT");
    }

    // ---------- 激活失败与整体回滚 ----------

    @Test
    void activateSlotConflictRollsBackEverythingAndDoesNotOccupyKey() throws Exception {
        String blockedSection = key("SEC");
        String altSection = key("SEC");
        String oldKey = key("OLD");
        String replKey = key("REP");
        String unaffected = key("OBS");
        createDraft(oldKey, occ("G1", blockedSection, iso(8), iso(9)));
        publishPlan(oldKey);
        // 未受影响的已发布计划占用 altSection 12:00-13:00
        createDraft(unaffected, occ("G2", altSection, iso(12), iso(13)));
        publishPlan(unaffected);
        // 替代草稿与未受影响计划在 altSection 同时段冲突
        createDraft(replKey, occ("R1", altSection, iso(12), iso(13)));

        String switchKey = key("SW");
        register(switchKey, blockedSection, iso(7), iso(10));
        submit(switchKey, mapping(oldKey, replKey));

        String activateReq = key("REQ");
        MvcResult result = mvc.perform(post("/api/v1/disruptions/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(activateReq, mapping(oldKey, replKey))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("SLOT_CONFLICT");

        // 整体回滚：封锁仍 REGISTERED，旧计划仍 PUBLISHED 且时隙保留，替代仍 DRAFT，无链无快照
        JsonNode switchRow = read(mvc.perform(get("/api/v1/disruptions/{key}", switchKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(switchRow.get("disruption").get("status").asText()).isEqualTo("REGISTERED");
        assertThat(getPlanStatus(oldKey)).isEqualTo("PUBLISHED");
        assertThat(getPlanStatus(replKey)).isEqualTo("DRAFT");
        assertThat(publishedSlots(blockedSection)).hasSize(1);
        Integer links = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_disruption_replace_link r"
                        + " JOIN rail_day_plan p ON p.id = r.suspended_plan_id"
                        + " WHERE p.schedule_key = ?", Integer.class, oldKey);
        assertThat(links).isEqualTo(0);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_disruption_snapshot s"
                        + " JOIN rail_disruption_switch d ON d.id = s.switch_id"
                        + " WHERE d.switch_key = ?", Integer.class, switchKey);
        assertThat(snapshots).isEqualTo(0);

        // 失败不占键：修正替代草稿到空闲区段后，同一 requestId 激活成功
        String fixedSection = key("SEC");
        mvc.perform(put("/api/v1/plans/{key}/occupancies", replKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, occ("R1", fixedSection, iso(8), iso(9)))))
                .andExpect(status().isOk());
        // 替代改到空闲区段，需重新提交映射（内容相同）
        submit(switchKey, mapping(oldKey, replKey));
        mvc.perform(post("/api/v1/disruptions/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(activateReq, mapping(oldKey, replKey))))
                .andExpect(status().isOk());
        assertThat(getPlanStatus(oldKey)).isEqualTo("SUSPENDED");
        assertThat(getPlanStatus(replKey)).isEqualTo("PUBLISHED");
    }

    @Test
    void activateRejectsConflictsBetweenTwoReplacements() throws Exception {
        String section = key("SEC");
        String old1 = key("OLD");
        String old2 = key("OLD");
        createDraft(old1, occ("G1", section, iso(8), iso(9)));
        createDraft(old2, occ("G2", section, iso(9), iso(10)));
        publishPlan(old1);
        publishPlan(old2);
        String rep1 = key("REP");
        String rep2 = key("REP");
        // 两个替代草稿在同一区段同一时隙互撞
        String altSection = key("ALT");
        createDraft(rep1, occ("R1", altSection, iso(14), iso(15)));
        createDraft(rep2, occ("R2", altSection, iso(14), iso(15)));

        String switchKey = key("SW");
        register(switchKey, section, iso(7), iso(11));
        submit(switchKey, mapping(old1, rep1), mapping(old2, rep2));

        mvc.perform(post("/api/v1/disruptions/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(key("REQ"), mapping(old1, rep1), mapping(old2, rep2))))
                .andExpect(status().isUnprocessableEntity());
        assertThat(getPlanStatus(old1)).isEqualTo("PUBLISHED");
        assertThat(getPlanStatus(old2)).isEqualTo("PUBLISHED");
        assertThat(getPlanStatus(rep1)).isEqualTo("DRAFT");
        assertThat(getPlanStatus(rep2)).isEqualTo("DRAFT");
    }

    @Test
    void activateRejectsChangedMappingVersionAndState() throws Exception {
        String blockedSection = key("SEC");
        String oldKey = key("OLD");
        createDraft(oldKey, occ("G1", blockedSection, iso(8), iso(9)));
        publishPlan(oldKey);
        String replKey = key("REP");
        createDraft(replKey, occ("R1", key("SEC"), iso(8), iso(9)));

        String switchKey = key("SW");
        register(switchKey, blockedSection, iso(7), iso(10));
        submit(switchKey, mapping(oldKey, replKey));

        // 激活映射与提交不一致（换成未提交的旧键）→ 409 MAPPING_CONFLICT
        String otherOld = key("OLD");
        MvcResult mappingConflict = mvc.perform(post("/api/v1/disruptions/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(key("REQ"), mapping(otherOld, replKey))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(mappingConflict).get("code").asText()).isEqualTo("MAPPING_CONFLICT");

        // 提交后旧计划版本被外部改动（直接改库模拟版本漂移）→ 409 VERSION_CONFLICT
        jdbc.update("UPDATE rail_day_plan SET version = 7 WHERE schedule_key = ?", oldKey);
        MvcResult versionConflict = mvc.perform(post("/api/v1/disruptions/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(key("REQ"), mapping(oldKey, replKey))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(versionConflict).get("code").asText()).isEqualTo("VERSION_CONFLICT");
        jdbc.update("UPDATE rail_day_plan SET version = 1 WHERE schedule_key = ?", oldKey);

        // 替代草稿在激活前被发布 → 409 状态冲突
        publishPlan(replKey);
        mvc.perform(post("/api/v1/disruptions/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(key("REQ"), mapping(oldKey, replKey))))
                .andExpect(status().isConflict());
    }

    // ---------- 幂等 ----------

    @Test
    void activationReplaySameParamsReorderedAndConflicts() throws Exception {
        String section = key("SEC");
        String old1 = key("OLD");
        String old2 = key("OLD");
        createDraft(old1, occ("G1", section, iso(8), iso(9)));
        createDraft(old2, occ("G2", section, iso(9), iso(10)));
        publishPlan(old1);
        publishPlan(old2);
        String rep1 = key("REP");
        String rep2 = key("REP");
        createDraft(rep1, occ("R1", key("SEC"), iso(8), iso(9)));
        createDraft(rep2, occ("R2", key("SEC"), iso(8), iso(9)));

        String switchKey = key("SW");
        register(switchKey, section, iso(7), iso(11));
        submit(switchKey, mapping(old1, rep1), mapping(old2, rep2));

        String activateReq = key("REQ");
        MvcResult first = mvc.perform(post("/api/v1/disruptions/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(activateReq, mapping(old1, rep1), mapping(old2, rep2))))
                .andExpect(status().isOk()).andReturn();
        // 映射换序视为同参，重放首次快照
        MvcResult replay = mvc.perform(post("/api/v1/disruptions/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(activateReq, mapping(old2, rep2), mapping(old1, rep1))))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同 requestId 异参 → 409
        mvc.perform(post("/api/v1/disruptions/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(activateReq, mapping(old1, rep1))))
                .andExpect(status().isConflict());

        // switchKey 换请求复用：另一 requestId 再次激活 → 409
        MvcResult reused = mvc.perform(post("/api/v1/disruptions/{key}/activate", switchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody(key("REQ"), mapping(old1, rep1), mapping(old2, rep2))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(reused).get("code").asText()).isEqualTo("SWITCH_STATE_CONFLICT");

        // 链仍为恰好两条、快照仅一份
        Integer links = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_disruption_replace_link r"
                        + " JOIN rail_disruption_switch d ON d.id = r.switch_id"
                        + " WHERE d.switch_key = ?", Integer.class, switchKey);
        assertThat(links).isEqualTo(2);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_disruption_snapshot s"
                        + " JOIN rail_disruption_switch d ON d.id = s.switch_id"
                        + " WHERE d.switch_key = ?", Integer.class, switchKey);
        assertThat(snapshots).isEqualTo(1);
    }

    @Test
    void registerIdempotentReplayAndParamConflict() throws Exception {
        String switchKey = key("SW");
        String section = key("SEC");
        String requestId = key("REQ");
        String body = registerBody(requestId, switchKey, section, iso(7), iso(10));

        MvcResult first = mvc.perform(post("/api/v1/disruptions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/disruptions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同参（窗口变化）→ 409
        mvc.perform(post("/api/v1/disruptions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestId, switchKey, section, iso(8), iso(11))))
                .andExpect(status().isConflict());
    }

    // ---------- 辅助 ----------

    private void createDraft(String scheduleKey, String occupancy) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey, DAY, occupancy)))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\"}"))
                .andExpect(status().isOk());
    }

    private void register(String switchKey, String section, String start, String end) throws Exception {
        mvc.perform(post("/api/v1/disruptions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), switchKey, section, start, end)))
                .andExpect(status().isCreated());
    }

    private void submit(String switchKey, String... mappings) throws Exception {
        mvc.perform(put("/api/v1/disruptions/{key}/mappings", switchKey)
                        .contentType(MediaType.APPLICATION_JSON).content(submitBody(mappings)))
                .andExpect(status().isOk());
    }

    private String getPlanStatus(String scheduleKey) throws Exception {
        return read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn()).get("status").asText();
    }

    private JsonNode publishedSlots(String section) throws Exception {
        return read(mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn());
    }

    private Long planId(String scheduleKey) {
        return jdbc.queryForObject("SELECT id FROM rail_day_plan WHERE schedule_key = ?",
                Long.class, scheduleKey);
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

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String occAt(String train, String section, java.time.LocalDateTime start,
                                java.time.LocalDateTime end) {
        Instant s = start.atZone(SH).toInstant();
        Instant e = end.atZone(SH).toInstant();
        return occ(train, section, s.toString(), e.toString());
    }

    private static String mapping(String oldKey, String replKey) {
        return "{\"oldScheduleKey\":\"" + oldKey + "\",\"replacementScheduleKey\":\"" + replKey + "\"}";
    }

    private static String registerBody(String requestId, String switchKey, String section,
                                       String start, String end) {
        return "{\"requestId\":\"" + requestId + "\",\"switchKey\":\"" + switchKey
                + "\",\"sectionId\":\"" + section + "\",\"windowStartUtc\":\"" + start
                + "\",\"windowEndUtc\":\"" + end + "\"}";
    }

    private static String submitBody(String... mappings) {
        return "{\"mappings\":[" + String.join(",", mappings) + "]}";
    }

    private static String activateBody(String requestId, String... mappings) {
        return "{\"requestId\":\"" + requestId + "\",\"mappings\":["
                + String.join(",", mappings) + "]}";
    }

    private static String createBody(String requestKey, String scheduleKey, LocalDate opDate,
                                     String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + opDate + "\",\"occupancies\":["
                + String.join(",", occupancies) + "]}";
    }

    private static String updateBody(String requestKey, int expectedVersion, String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"occupancies\":[" + String.join(",", occupancies) + "]}";
    }
}
