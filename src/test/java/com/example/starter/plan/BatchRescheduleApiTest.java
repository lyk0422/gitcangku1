package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 批量原子改签 API 测试：整批主流程、含非法计划整批回滚（时隙与编组均不变）、
 * 批内新计划两两冲突、幂等重放与异参 409（H2 内存库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchRescheduleApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程 ----------

    @Test
    void batchRescheduleSuccessPublishesAllAndWritesSnapshots() throws Exception {
        String section1 = key("SEC");
        String section2 = key("SEC");
        String old1 = key("SCH");
        String old2 = key("SCH");
        String new1 = key("SCH");
        String new2 = key("SCH");
        createPlan(old1, occ("G1", section1, iso(8), iso(9)));
        createPlan(old2, occ("G2", section2, iso(8), iso(9)));
        publish(old1);
        publish(old2);
        createPlan(new1, occ("G3", section1, iso(10), iso(11)));
        createPlan(new2, occ("G4", section2, iso(10), iso(11)));

        MvcResult result = mvc.perform(post("/api/v1/plans/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), "op-1",
                                item(old1, new1, 1, 1), item(old2, new2, 1, 1))))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("results")).hasSize(2);
        // 结果顺序与请求一致
        assertThat(body.get("results").get(0).get("oldScheduleKey").asText()).isEqualTo(old1);
        assertThat(body.get("results").get(0).get("newScheduleKey").asText()).isEqualTo(new1);
        assertThat(body.get("results").get(0).get("oldPlan").get("status").asText())
                .isEqualTo("CANCELLED");
        assertThat(body.get("results").get(0).get("newPlan").get("status").asText())
                .isEqualTo("PUBLISHED");
        assertThat(body.get("results").get(1).get("newPlan").get("status").asText())
                .isEqualTo("PUBLISHED");

        // 改签链可查
        MvcResult chain = mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", new1))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = read(chain).get("chain");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("scheduleKey").asText()).isEqualTo(old1);
        assertThat(items.get(1).get("scheduleKey").asText()).isEqualTo(new1);

        // 每个新计划固化批量来源快照
        MvcResult snapshots = mvc.perform(get("/api/v1/plans/{key}/hazard-snapshots", new2))
                .andExpect(status().isOk()).andReturn();
        JsonNode snapshotList = read(snapshots);
        assertThat(snapshotList).hasSize(1);
        assertThat(snapshotList.get(0).get("source").asText()).isEqualTo("BATCH_RESCHEDULE");
        assertThat(snapshotList.get(0).get("validationResult").asText()).isEqualTo("PASSED");
    }

    // ---------- 含非法计划：整批不改变时隙或编组 ----------

    @Test
    void batchWithConflictingItemRollsBackEntirely() throws Exception {
        String section1 = key("SEC");
        String section2 = key("SEC");
        String old1 = key("SCH");
        String old2 = key("SCH");
        String new1 = key("SCH");
        String new2 = key("SCH");
        // 旧计划 1/2 已发布；新计划 2 与第三方已发布计划区段重叠 → 非法
        String thirdParty = key("SCH");
        createPlan(old1, occ("G1", section1, iso(8), iso(9)));
        createPlan(old2, occ("G2", section2, iso(8), iso(9)));
        publish(old1);
        publish(old2);
        createPlan(thirdParty, occ("G9", section2, iso(10), iso(11)));
        publish(thirdParty);
        createPlan(new1, occ("G3", section1, iso(10), iso(11)));
        createPlan(new2, occ("G4", section2, iso(10), iso(11)));

        MvcResult result = mvc.perform(post("/api/v1/plans/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), "op-1",
                                item(old1, new1, 1, 1), item(old2, new2, 1, 1))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("BATCH_CONFLICT");
        assertThat(error.get("details").get(0).get("type").asText())
                .isEqualTo("SECTION_CONFLICT");

        // 整批未生效：旧计划仍发布、新计划仍草稿、无关联、无快照
        assertThat(getPlanStatus(old1)).isEqualTo("PUBLISHED");
        assertThat(getPlanStatus(old2)).isEqualTo("PUBLISHED");
        assertThat(getPlanStatus(new1)).isEqualTo("DRAFT");
        assertThat(getPlanStatus(new2)).isEqualTo("DRAFT");
        MvcResult chain = mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", old1))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(chain).get("chain")).hasSize(1);
        MvcResult snapshots = mvc.perform(get("/api/v1/plans/{key}/hazard-snapshots", new1))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(snapshots)).isEmpty();
    }

    @Test
    void batchWithHazardViolationRollsBackEntirely() throws Exception {
        // 限制区段仅开放 FLAMMABLE；新计划 2 编组含未授权类别 → 整批 422
        String section1 = key("SEC");
        String section2 = key("SEC");
        configureSection(section2);
        String old1 = key("SCH");
        String old2 = key("SCH");
        String new1 = key("SCH");
        String new2 = key("SCH");
        createPlan(old1, occ("G1", section1, iso(8), iso(9)));
        createPlan(old2, occ("G2", section2, iso(8), iso(9)));
        publish(old1);
        publish(old2);
        createPlan(new1, occ("G3", section1, iso(10), iso(11)));
        createPlan(new2, occ("G4", section2, iso(10), iso(11)));
        putCars(new2, 1, car(1, "EXPLOSIVE"));

        MvcResult result = mvc.perform(post("/api/v1/plans/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), "op-1",
                                item(old1, new1, 1, 1), item(old2, new2, 1, 2))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("BATCH_CONFLICT");
        assertThat(error.get("details").get(0).get("type").asText())
                .isEqualTo("SECTION_CLASS_NOT_ALLOWED");

        assertThat(getPlanStatus(old1)).isEqualTo("PUBLISHED");
        assertThat(getPlanStatus(new1)).isEqualTo("DRAFT");
        assertThat(getPlanStatus(new2)).isEqualTo("DRAFT");
    }

    @Test
    void batchIntraConflictBetweenNewPlansRejected() throws Exception {
        // 批内两个新计划占用同一区段同一时段 → 整批 422
        String section1 = key("SEC");
        String section2 = key("SEC");
        String shared = key("SEC");
        String old1 = key("SCH");
        String old2 = key("SCH");
        String new1 = key("SCH");
        String new2 = key("SCH");
        createPlan(old1, occ("G1", section1, iso(8), iso(9)));
        createPlan(old2, occ("G2", section2, iso(8), iso(9)));
        publish(old1);
        publish(old2);
        createPlan(new1, occ("G3", shared, iso(10), iso(11)));
        createPlan(new2, occ("G4", shared, iso(10), iso(11)));

        MvcResult result = mvc.perform(post("/api/v1/plans/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), "op-1",
                                item(old1, new1, 1, 1), item(old2, new2, 1, 1))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode detail = read(result).get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("SECTION_CONFLICT");
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(new2);

        assertThat(getPlanStatus(new1)).isEqualTo("DRAFT");
        assertThat(getPlanStatus(new2)).isEqualTo("DRAFT");
    }

    // ---------- 版本冲突 409：整批不变 ----------

    @Test
    void batchVersionConflictLeavesAllUnchanged() throws Exception {
        String section1 = key("SEC");
        String section2 = key("SEC");
        String old1 = key("SCH");
        String old2 = key("SCH");
        String new1 = key("SCH");
        String new2 = key("SCH");
        createPlan(old1, occ("G1", section1, iso(8), iso(9)));
        createPlan(old2, occ("G2", section2, iso(8), iso(9)));
        publish(old1);
        publish(old2);
        createPlan(new1, occ("G3", section1, iso(10), iso(11)));
        createPlan(new2, occ("G4", section2, iso(10), iso(11)));

        mvc.perform(post("/api/v1/plans/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), "op-1",
                                item(old1, new1, 1, 1), item(old2, new2, 5, 1))))
                .andExpect(status().isConflict());

        assertThat(getPlanStatus(old1)).isEqualTo("PUBLISHED");
        assertThat(getPlanStatus(old2)).isEqualTo("PUBLISHED");
        assertThat(getPlanStatus(new1)).isEqualTo("DRAFT");
        assertThat(getPlanStatus(new2)).isEqualTo("DRAFT");
    }

    // ---------- 幂等：同参重放、异参 409 ----------

    @Test
    void batchIdempotentReplayAndParamConflict() throws Exception {
        String section1 = key("SEC");
        String old1 = key("SCH");
        String new1 = key("SCH");
        createPlan(old1, occ("G1", section1, iso(8), iso(9)));
        publish(old1);
        createPlan(new1, occ("G3", section1, iso(10), iso(11)));

        String requestKey = key("REQ");
        String body = batchBody(requestKey, "op-1", item(old1, new1, 1, 1));
        MvcResult first = mvc.perform(post("/api/v1/plans/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/plans/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        // 重放不重复改签：旧计划仍只取消一次、链长为 2
        MvcResult chain = mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", new1))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(chain).get("chain")).hasSize(2);

        // 同键异参 → 409
        String other = key("SCH");
        createPlan(other, occ("G5", section1, iso(12), iso(13)));
        mvc.perform(post("/api/v1/plans/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(requestKey, "op-1", item(old1, other, 1, 1))))
                .andExpect(status().isConflict());
    }

    // ---------- 辅助 ----------

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":["
                                + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private void publish(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\"}"))
                .andExpect(status().isOk());
    }

    private void configureSection(String sectionId) throws Exception {
        mvc.perform(put("/api/v1/hazard/restricted-sections/{id}", sectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ")
                                + "\",\"operator\":\"op-1\",\"allowedClasses\":[\"FLAMMABLE\"]}"))
                .andExpect(status().isOk());
    }

    private void putCars(String scheduleKey, int expectedVersion, String... cars)
            throws Exception {
        mvc.perform(put("/api/v1/plans/{key}/hazard-cars", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ")
                                + "\",\"operator\":\"op-1\",\"expectedVersion\":" + expectedVersion
                                + ",\"cars\":[" + String.join(",", cars) + "]}"))
                .andExpect(status().isOk());
    }

    private String getPlanStatus(String scheduleKey) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(detail).get("status").asText();
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

    private static String car(int position, String hazardClass) {
        return "{\"carPosition\":" + position + ",\"hazardClass\":\"" + hazardClass + "\"}";
    }

    private static String item(String oldKey, String newKey, int expectedOld, int expectedNew) {
        return "{\"oldScheduleKey\":\"" + oldKey + "\",\"newScheduleKey\":\"" + newKey
                + "\",\"expectedOldVersion\":" + expectedOld
                + ",\"expectedNewVersion\":" + expectedNew + "}";
    }

    private static String batchBody(String requestKey, String operator, String... items) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator
                + "\",\"items\":[" + String.join(",", items) + "]}";
    }
}
