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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 铁路危险品隔离端到端测试（真实 H2 内存库，MODE=MySQL）：
 * 配置版本写入与生效边界、发布完整编组/完整路径校验的主流程与全部 422 失败分支、
 * 发布快照固化且不可改写、改签与批量改签整体事务、幂等重放/异参 409/失败不占键。
 * 时间判定使用可控时钟（基准 2026-09-22T12:00:00Z），不依赖睡眠。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControlledTimeTestConfig.class)
class HazardSeparationApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 危险品配置为全局版本数据，各用例用唯一业务键隔离计划数据；
     * 每个用例前清空配置表与其幂等记录，避免生效区间跨用例重叠，版本号从 1 重新计。
     * 计划与发布数据不清空，用例以唯一 scheduleKey/sectionId 自隔离。
     */
    @BeforeEach
    void clearHazardConfig() {
        jdbc.update("DELETE FROM hazard_class_separation");
        jdbc.update("DELETE FROM hazard_section_open_class");
        jdbc.update("DELETE FROM hazard_restricted_section");
        jdbc.update("DELETE FROM hazard_config_version");
        jdbc.update("DELETE FROM idempotency_record WHERE op_type = 'SECTION_CONFIG'");
    }

    // ---------- 配置版本 ----------

    @Test
    void writeConfigThenQueryVersionAndEffective() throws Exception {
        String body = configBody(key("REQ"), "dispatcher-1",
                "2020-01-01T00:00:00Z", null,
                "[{\"classA\":\"EX1\",\"classB\":\"EX2\",\"minCars\":2}]",
                "[{\"sectionId\":\"SEC-TUNNEL\",\"openHazardClasses\":[\"EX1\",\"EX2\"]}]");
        MvcResult created = mvc.perform(post("/api/v1/hazard-configs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode view = read(created);
        int versionNo = view.get("versionNo").asInt();
        assertThat(versionNo).isGreaterThanOrEqualTo(1);
        assertThat(view.get("fingerprint").asText()).hasSize(64);
        // 类别对归一化（请求按 B,A 给，响应为 A,B）
        assertThat(view.get("separations").get(0).get("classA").asText()).isEqualTo("EX1");
        assertThat(view.get("separations").get(0).get("classB").asText()).isEqualTo("EX2");
        assertThat(view.get("restrictedSections").get(0).get("openHazardClasses"))
                .hasSize(2);

        MvcResult byNo = mvc.perform(get("/api/v1/hazard-configs/{versionNo}", versionNo))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(byNo)).isEqualTo(view);

        // 发布基准时刻 2026-09-22T12:00:00Z 落在生效区间内
        MvcResult effective = mvc.perform(get("/api/v1/hazard-configs/effective")
                        .param("atUtc", "2026-09-22T12:00:00Z"))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(effective).get("versionNo").asInt()).isEqualTo(versionNo);

        // 区间左闭右开：生效开始前一刻无版本
        mvc.perform(get("/api/v1/hazard-configs/effective")
                        .param("atUtc", "2019-12-31T23:59:59Z"))
                .andExpect(status().isNotFound());
    }

    @Test
    void configIdempotentReplayParamConflictOverlapAndInvalidArgs() throws Exception {
        String body = configBody(key("REQ"), "op",
                "2031-01-01T00:00:00Z", "2032-01-01T00:00:00Z", "[]", "[]");
        MvcResult first = mvc.perform(post("/api/v1/hazard-configs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/hazard-configs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同参 → 409
        mvc.perform(post("/api/v1/hazard-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(keyExtract(body), "op",
                                "2031-06-01T00:00:00Z", "2032-01-01T00:00:00Z", "[]", "[]")))
                .andExpect(status().isConflict());

        // 生效区间重叠（相邻区间合法，重叠区间 409）
        mvc.perform(post("/api/v1/hazard-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op",
                                "2031-06-01T00:00:00Z", "2031-07-01T00:00:00Z", "[]", "[]")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/hazard-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op",
                                "2032-01-01T00:00:00Z", null, "[]", "[]")))
                .andExpect(status().isCreated());

        // 结束不晚于开始 → 400
        mvc.perform(post("/api/v1/hazard-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op",
                                "2040-01-01T00:00:00Z", "2040-01-01T00:00:00Z", "[]", "[]")))
                .andExpect(status().isBadRequest());
        // 互斥类别相同 → 400
        mvc.perform(post("/api/v1/hazard-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op",
                                "2041-01-01T00:00:00Z", null,
                                "[{\"classA\":\"X\",\"classB\":\"X\",\"minCars\":1}]", "[]")))
                .andExpect(status().isBadRequest());
    }

    // ---------- 发布主流程与快照 ----------

    @Test
    void publishWithValidHazardsFreezesSnapshot() throws Exception {
        String section = key("TUN");
        writeBaseConfigForSection(key("CFG"), section);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, "alice",
                new String[]{occ("G1", section, iso(8), iso(9))},
                new String[]{car("G1", 1, "EX1"), car("G1", 4, "EX2")});

        publish(scheduleKey, key("REQ"), "alice");

        JsonNode plan = getPlan(scheduleKey);
        assertThat(plan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(plan.get("hazardCars")).hasSize(2);
        assertThat(plan.get("hazardCars").get(0).get("position").asInt()).isEqualTo(1);

        MvcResult snapshotRes = mvc.perform(get("/api/v1/plans/{key}/publish-snapshot", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode snap = read(snapshotRes);
        assertThat(snap.get("conclusion").asText()).isEqualTo("PASS");
        assertThat(snap.get("operator").asText()).isEqualTo("alice");
        assertThat(snap.get("configVersionNo").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(snap.get("configFingerprint").asText()).hasSize(64);
        assertThat(snap.get("hazardCars")).hasSize(2);
        // 快照固化限制区段开放清单
        assertThat(snap.get("restrictedSections"))
                .extracting(s -> s.get("sectionId").asText())
                .containsOnly(section);
    }

    @Test
    void laterConfigAndCancelDoNotRewriteSnapshot() throws Exception {
        String section = key("TUN");
        writeBaseConfigForSection(key("CFG"), section);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, null,
                new String[]{occ("G1", section, iso(8), iso(9))},
                new String[]{car("G1", 1, "EX1")});
        publish(scheduleKey, key("REQ"), null);

        JsonNode before = getSnapshot(scheduleKey);
        int oldVersion = before.get("configVersionNo").asInt();
        String oldFingerprint = before.get("configFingerprint").asText();
        String createdAt = before.get("createdAtUtc").asText();

        // 写入后续配置版本（开放清单移除 EX1），历史快照不改写
        mvc.perform(post("/api/v1/hazard-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op",
                                "2040-01-01T00:00:00Z", null, "[]",
                                "[{\"sectionId\":\"" + section + "\",\"openHazardClasses\":[\"EX2\"]}]")))
                .andExpect(status().isCreated());
        cancel(scheduleKey, key("REQ2"), null);

        JsonNode after = getSnapshot(scheduleKey);
        assertThat(after.get("configVersionNo").asInt()).isEqualTo(oldVersion);
        assertThat(after.get("configFingerprint").asText()).isEqualTo(oldFingerprint);
        assertThat(after.get("createdAtUtc").asText()).isEqualTo(createdAt);
        assertThat(after.get("conclusion").asText()).isEqualTo("PASS");
        assertThat(after.get("hazardCars")).hasSize(1);
        assertThat(after.get("restrictedSections").get(0).get("hazardClass").asText())
                .isEqualTo("EX1");
        // 取消后计划明细保留编组历史
        assertThat(getPlan(scheduleKey).get("hazardCars")).hasSize(1);
    }

    // ---------- 422 失败分支 ----------

    @Test
    void publishRejectsDuplicatePositionAndKeepsDraft() throws Exception {
        writeBaseConfig(key("CFG"));
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, null,
                new String[]{occ("G1", key("SEC"), iso(8), iso(9))},
                new String[]{car("G1", 3, "EX1"), car("G1", 3, "EX2")});

        JsonNode error = publishExpect422(scheduleKey, key("REQ"));
        assertThat(error.get("details"))
                .anySatisfy(d -> assertThat(d.get("type").asText())
                        .isEqualTo("HAZARD_DUPLICATE_POSITION"));
        assertThat(getPlan(scheduleKey).get("status").asText()).isEqualTo("DRAFT");

        // 修正编组后同一发布键可重试成功（失败不占键）
        replace(scheduleKey, 1,
                new String[]{occ("G1", key("SEC"), iso(8), iso(9))},
                new String[]{car("G1", 3, "EX1")});
        publish(scheduleKey, key("REQ2"), null);
    }

    @Test
    void publishRejectsInsufficientSeparationWithActualRequiredShortfall() throws Exception {
        String section = key("TUN");
        writeBaseConfigForSection(key("CFG"), section);
        String scheduleKey = key("SCH");
        // EX1/EX2 要求相隔至少 2 节；位置 1 与 3 实际相隔 1 节
        createPlan(scheduleKey, null,
                new String[]{occ("G1", section, iso(8), iso(9))},
                new String[]{car("G1", 1, "EX1"), car("G1", 3, "EX2")});

        JsonNode error = publishExpect422(scheduleKey, key("REQ"));
        JsonNode detail = firstOfType(error, "HAZARD_SEPARATION_INSUFFICIENT");
        assertThat(detail.get("actualCars").asInt()).isEqualTo(1);
        assertThat(detail.get("requiredCars").asInt()).isEqualTo(2);
        assertThat(detail.get("shortfallCars").asInt()).isEqualTo(1);
        assertThat(detail.get("hazardClassA").asText()).isEqualTo("EX1");
        assertThat(detail.get("hazardClassB").asText()).isEqualTo("EX2");
    }

    @Test
    void publishRejectsUnauthorizedClassOnRestrictedSection() throws Exception {
        String section = key("TUN");
        writeBaseConfigForSection(key("CFG"), section);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, null,
                new String[]{occ("G1", section, iso(8), iso(9))},
                new String[]{car("G1", 2, "EX3")});

        JsonNode error = publishExpect422(scheduleKey, key("REQ"));
        JsonNode detail = firstOfType(error, "HAZARD_CLASS_UNAUTHORIZED");
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("hazardClass").asText()).isEqualTo("EX3");
        assertThat(detail.get("openHazardClasses"))
                .extracting(JsonNode::asText)
                .containsExactly("EX1", "EX2");
        assertThat(getPlan(scheduleKey).get("status").asText()).isEqualTo("DRAFT");

        // 同一列车经非限制区段则合法（完整路径不经过限制区段）
        String freePlan = key("SCH");
        createPlan(freePlan, null,
                new String[]{occ("G2", key("SEC"), iso(8), iso(9))},
                new String[]{car("G2", 2, "EX3")});
        publish(freePlan, key("REQ"), null);
    }

    @Test
    void publishRejectsInvalidRestrictedSectionConfig() throws Exception {
        String section = key("TUN");
        // 登记限制区段但开放清单为空 → 配置无效
        mvc.perform(post("/api/v1/hazard-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op",
                                "2020-01-01T00:00:00Z", null, "[]",
                                "[{\"sectionId\":\"" + section + "\",\"openHazardClasses\":[]}]")))
                .andExpect(status().isCreated());
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, null,
                new String[]{occ("G1", section, iso(8), iso(9))},
                new String[]{car("G1", 1, "EX1")});

        JsonNode error = publishExpect422(scheduleKey, key("REQ"));
        assertThat(firstOfType(error, "HAZARD_CONFIG_INVALID").get("sectionId").asText())
                .isEqualTo(section);
        assertThat(getPlan(scheduleKey).get("status").asText()).isEqualTo("DRAFT");
    }

    // ---------- 改签危险品校验与回滚 ----------

    @Test
    void rescheduleValidatesHazardsAndRollsBack() throws Exception {
        String section = key("TUN");
        writeBaseConfigForSection(key("CFG"), section);
        String oldKey = key("SCH");
        String newKey = key("SCH");
        createPlan(oldKey, null,
                new String[]{occ("G1", section, iso(8), iso(9))},
                new String[]{car("G1", 1, "EX1")});
        publish(oldKey, key("REQ"), null);
        // 新草稿编组间隔不足（1 与 3，要求相隔 2）
        createPlan(newKey, null,
                new String[]{occ("G2", section, iso(8), iso(10))},
                new String[]{car("G2", 1, "EX1"), car("G2", 3, "EX2")});

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), null, newKey, 1, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(firstOfType(read(result), "HAZARD_SEPARATION_INSUFFICIENT")).isNotNull();

        // 整体回滚：旧仍发布、新仍草稿、无新快照
        assertThat(getPlan(oldKey).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(getPlan(newKey).get("status").asText()).isEqualTo("DRAFT");
        mvc.perform(get("/api/v1/plans/{key}/publish-snapshot", newKey))
                .andExpect(status().isNotFound());

        // 修正后同键新参重试成功，新计划产生快照
        replace(newKey, 1,
                new String[]{occ("G2", section, iso(8), iso(10))},
                new String[]{car("G2", 1, "EX1"), car("G2", 4, "EX2")});
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ2"), null, newKey, 1, 2)))
                .andExpect(status().isOk());
        assertThat(getSnapshot(newKey).get("conclusion").asText()).isEqualTo("PASS");
    }

    // ---------- 批量改签 ----------

    @Test
    void batchRescheduleSucceedsAtomicallyAndReplays() throws Exception {
        String section = key("SEC");
        String oldA = key("SCH");
        String oldB = key("SCH");
        String newA = key("SCH");
        String newB = key("SCH");
        createPlan(oldA, null, new String[]{occ("G1", section, iso(8), iso(9))}, new String[]{});
        createPlan(oldB, null, new String[]{occ("G2", section, iso(10), iso(11))}, new String[]{});
        publish(oldA, key("REQ"), null);
        publish(oldB, key("REQ"), null);
        createPlan(newA, null, new String[]{occ("G3", section, iso(8), iso(9))}, new String[]{});
        createPlan(newB, null, new String[]{occ("G4", section, iso(10), iso(11))}, new String[]{});

        String body = batchBody(key("BREQ"), null,
                batchItem(oldA, newA), batchItem(oldB, newB));
        MvcResult first = mvc.perform(post("/api/v1/reschedules/batch")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode firstJson = read(first);
        assertThat(firstJson.get("items")).hasSize(2);
        assertThat(firstJson.get("items").get(0).get("oldPlan").get("status").asText())
                .isEqualTo("CANCELLED");
        assertThat(firstJson.get("items").get(1).get("newPlan").get("status").asText())
                .isEqualTo("PUBLISHED");

        // 同参重放返回首次完整响应快照
        MvcResult replay = mvc.perform(post("/api/v1/reschedules/batch")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(firstJson);

        assertThat(getPlan(oldA).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(getPlan(newB).get("status").asText()).isEqualTo("PUBLISHED");
    }

    @Test
    void batchRescheduleWithAnyIllegalPlanChangesNothing() throws Exception {
        String section = key("TUN");
        writeBaseConfigForSection(key("CFG"), section);
        // 第一项合法，第二项新草稿类别未经限制区段授权
        String oldA = key("SCH");
        String oldB = key("SCH");
        String newA = key("SCH");
        String newB = key("SCH");
        createPlan(oldA, null, new String[]{occ("G1", section, iso(8), iso(9))},
                new String[]{car("G1", 1, "EX1")});
        createPlan(oldB, null, new String[]{occ("G2", section, iso(11), iso(12))},
                new String[]{car("G2", 1, "EX1")});
        publish(oldA, key("REQ"), null);
        publish(oldB, key("REQ"), null);
        createPlan(newA, null, new String[]{occ("G3", section, iso(8), iso(9))},
                new String[]{car("G3", 1, "EX1")});
        createPlan(newB, null, new String[]{occ("G4", section, iso(11), iso(12))},
                new String[]{car("G4", 1, "EX3")});

        String requestKey = key("BREQ");
        mvc.perform(post("/api/v1/reschedules/batch").contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(requestKey, null,
                                batchItem(oldA, newA), batchItem(oldB, newB))))
                .andExpect(status().isUnprocessableEntity());

        // 整批不变：旧仍发布、新仍草稿、无关联
        assertThat(getPlan(oldA).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(getPlan(oldB).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(getPlan(newA).get("status").asText()).isEqualTo("DRAFT");
        assertThat(getPlan(newB).get("status").asText()).isEqualTo("DRAFT");

        // 失败不占键：修正第二项后同键成功
        replace(newB, 1,
                new String[]{occ("G4", section, iso(11), iso(12))},
                new String[]{car("G4", 1, "EX1")});
        mvc.perform(post("/api/v1/reschedules/batch").contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(requestKey, null,
                                batchItem(oldA, newA, 1, 1), batchItem(oldB, newB, 1, 2))))
                .andExpect(status().isOk());
        assertThat(getPlan(newA).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(getPlan(newB).get("status").asText()).isEqualTo("PUBLISHED");
    }

    @Test
    void batchRescheduleRejectsConflictsWithinBatch() throws Exception {
        String section = key("SEC");
        String oldA = key("SCH");
        String oldB = key("SCH");
        String newA = key("SCH");
        String newB = key("SCH");
        createPlan(oldA, null, new String[]{occ("G1", section, iso(8), iso(10))}, new String[]{});
        createPlan(oldB, null, new String[]{occ("G2", section, iso(12), iso(13))}, new String[]{});
        publish(oldA, key("REQ"), null);
        publish(oldB, key("REQ"), null);
        // 两个新草稿在同区段 09:00-10:00 互相重叠
        createPlan(newA, null, new String[]{occ("G3", section, iso(9), iso(10))}, new String[]{});
        createPlan(newB, null, new String[]{occ("G4", section, iso(9), iso(10))}, new String[]{});

        MvcResult result = mvc.perform(post("/api/v1/reschedules/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("BREQ"), null,
                                batchItem(oldA, newA), batchItem(oldB, newB))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(firstOfType(error, "BATCH_SECTION_CONFLICT")).isNotNull();
        assertThat(getPlan(newA).get("status").asText()).isEqualTo("DRAFT");
        assertThat(getPlan(newB).get("status").asText()).isEqualTo("DRAFT");
    }

    // ---------- 只读诊断 ----------

    @Test
    void hazardDiagnosisIsReadOnlyAndListsConflicts() throws Exception {
        String section = key("TUN");
        writeBaseConfigForSection(key("CFG"), section);
        String scheduleKey = key("SCH");
        // 编组间隔不足（EX1@1 与 EX2@3，要求相隔 2）且 EX3@5 未授权
        createPlan(scheduleKey, null,
                new String[]{occ("G1", section, iso(8), iso(9))},
                new String[]{car("G1", 1, "EX1"), car("G1", 3, "EX2"), car("G1", 5, "EX3")});

        MvcResult first = mvc.perform(get("/api/v1/plans/{key}/hazard-diagnosis", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode diag = read(first);
        assertThat(diag.get("valid").asBoolean()).isFalse();
        assertThat(diag.get("configVersionNo").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(firstOfType(diag, "HAZARD_SEPARATION_INSUFFICIENT")).isNotNull();
        assertThat(firstOfType(diag, "HAZARD_CLASS_UNAUTHORIZED")).isNotNull();

        // 查询不修改状态：计划仍草稿，重复诊断结果一致
        assertThat(getPlan(scheduleKey).get("status").asText()).isEqualTo("DRAFT");
        MvcResult second = mvc.perform(get("/api/v1/plans/{key}/hazard-diagnosis", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(second)).isEqualTo(diag);
        mvc.perform(get("/api/v1/plans/{key}/publish-snapshot", scheduleKey))
                .andExpect(status().isNotFound());

        // 合法编组诊断通过
        String good = key("SCH");
        createPlan(good, null,
                new String[]{occ("G2", section, iso(10), iso(11))},
                new String[]{car("G2", 1, "EX1")});
        MvcResult goodDiag = mvc.perform(get("/api/v1/plans/{key}/hazard-diagnosis", good))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(goodDiag).get("valid").asBoolean()).isTrue();
        assertThat(read(goodDiag).get("details")).isEmpty();
    }

    // ---------- 发布历史查询 ----------
    @Test
    void publishHistoryStableSortedAndReadOnly() throws Exception {
        String section = key("SEC");
        String first = key("SCH");
        createPlan(first, null, new String[]{occ("G1", section, iso(8), iso(9))}, new String[]{});
        publish(first, key("REQ"), null);
        String second = key("SCH");
        createPlan(second, null, new String[]{occ("G2", section, iso(9), iso(10))}, new String[]{});
        publish(second, key("REQ"), null);

        MvcResult history = mvc.perform(get("/api/v1/publish-history")
                        .param("fromDate", DAY.toString()).param("toDate", DAY.toString())
                        .param("limit", "100"))
                .andExpect(status().isOk()).andReturn();
        JsonNode list = read(history).get("snapshots");
        assertThat(list).hasSizeGreaterThanOrEqualTo(2);
        // 稳定排序：createdAt 升序、id 升序
        for (int i = 1; i < list.size(); i++) {
            long prevId = list.get(i - 1).get("snapshotId").asLong();
            long curId = list.get(i).get("snapshotId").asLong();
            assertThat(curId).isGreaterThan(prevId);
        }

        // 连续两次查询一致（只读）
        MvcResult again = mvc.perform(get("/api/v1/publish-history").param("limit", "100"))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(again)).isEqualTo(read(history));
    }

    // ---------- 辅助 ----------

    private void writeBaseConfig(String reqKey) throws Exception {
        writeBaseConfigForSection(reqKey, key("TUN"));
    }

    private void writeBaseConfigForSection(String reqKey, String section) throws Exception {
        mvc.perform(post("/api/v1/hazard-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(reqKey, "dispatcher",
                                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z",
                                "[{\"classA\":\"EX1\",\"classB\":\"EX2\",\"minCars\":2}]",
                                "[{\"sectionId\":\"" + section
                                        + "\",\"openHazardClasses\":[\"EX1\",\"EX2\"]}]")))
                .andExpect(status().isCreated());
    }

    private JsonNode firstOfType(JsonNode error, String type) {
        for (JsonNode d : error.get("details")) {
            if (d.get("type").asText().equals(type)) {
                return d;
            }
        }
        return null;
    }

    private JsonNode publishExpect422(String scheduleKey, String requestKey) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey, null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        return error;
    }

    private void createPlan(String scheduleKey, String operator, String[] occupancies,
                            String[] hazardCars) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey, operator, occupancies, hazardCars)))
                .andExpect(status().isCreated());
    }

    private void replace(String scheduleKey, int expectedVersion, String[] occupancies,
                         String[] hazardCars) throws Exception {
        mvc.perform(put("/api/v1/plans/{key}/occupancies", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), expectedVersion, occupancies, hazardCars)))
                .andExpect(status().isOk());
    }

    private void publish(String scheduleKey, String requestKey, String operator) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey, operator)))
                .andExpect(status().isOk());
    }

    private void cancel(String scheduleKey, String requestKey, String operator) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey, operator)))
                .andExpect(status().isOk());
    }

    private JsonNode getPlan(String scheduleKey) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(r);
    }

    private JsonNode getSnapshot(String scheduleKey) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/plans/{key}/publish-snapshot", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(r);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String keyExtract(String body) throws Exception {
        // 仅用于同键不同参：从已有请求体提取 requestKey
        return new ObjectMapper().readTree(body).get("requestKey").asText();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant().toString();
    }

    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String car(String train, int position, String hazardClass) {
        return "{\"trainNo\":\"" + train + "\",\"position\":" + position
                + ",\"hazardClass\":\"" + hazardClass + "\"}";
    }

    private static String createBody(String requestKey, String scheduleKey, String operator,
                                     String[] occupancies, String[] hazardCars) {
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey + "\""
                + (operator == null ? "" : ",\"operator\":\"" + operator + "\"")
                + ",\"opDate\":\"" + DAY + "\",\"occupancies\":[" + String.join(",", occupancies)
                + "],\"hazardCars\":[" + String.join(",", hazardCars) + "]}";
    }

    private static String updateBody(String requestKey, int expectedVersion,
                                     String[] occupancies, String[] hazardCars) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"occupancies\":[" + String.join(",", occupancies) + "],\"hazardCars\":["
                + String.join(",", hazardCars) + "]}";
    }

    private static String actionBody(String requestKey, String operator) {
        return "{\"requestKey\":\"" + requestKey + "\""
                + (operator == null ? "" : ",\"operator\":\"" + operator + "\"") + "}";
    }

    private static String rescheduleBody(String requestKey, String operator, String newKey,
                                         int oldVersion, int newVersion) {
        return "{\"requestKey\":\"" + requestKey + "\""
                + (operator == null ? "" : ",\"operator\":\"" + operator + "\"")
                + ",\"newScheduleKey\":\"" + newKey + "\""
                + ",\"expectedOldVersion\":" + oldVersion
                + ",\"expectedNewVersion\":" + newVersion + "}";
    }

    private static String batchItem(String oldKey, String newKey) {
        return batchItem(oldKey, newKey, 1, 1);
    }

    private static String batchItem(String oldKey, String newKey, int oldVersion, int newVersion) {
        return "{\"oldScheduleKey\":\"" + oldKey + "\",\"newScheduleKey\":\"" + newKey + "\""
                + ",\"expectedOldVersion\":" + oldVersion
                + ",\"expectedNewVersion\":" + newVersion + "}";
    }

    private static String batchBody(String requestKey, String operator, String... items) {
        return "{\"requestKey\":\"" + requestKey + "\""
                + (operator == null ? "" : ",\"operator\":\"" + operator + "\"")
                + ",\"items\":[" + String.join(",", items) + "]}";
    }

    private static String configBody(String requestKey, String operator, String from, String to,
                                     String separationsJson, String sectionsJson) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator + "\""
                + ",\"effectiveFromUtc\":\"" + from + "\""
                + (to == null ? "" : ",\"effectiveToUtc\":\"" + to + "\"")
                + ",\"separations\":" + separationsJson
                + ",\"restrictedSections\":" + sectionsJson + "}";
    }
}
