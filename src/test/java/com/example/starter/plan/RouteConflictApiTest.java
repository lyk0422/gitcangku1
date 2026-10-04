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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 进路互斥业务测试（H2 内存库）：合成 R1/R2 互斥场景、自身默认冲突、未声明并行、
 * 取消释放与改签排除旧占用。每个用例前清空进路相关表并重置矩阵版本。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RouteConflictApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanRouteTables() {
        jdbc.update("DELETE FROM rail_route_conflict");
        jdbc.update("DELETE FROM rail_route");
        jdbc.update("UPDATE rail_route_matrix SET version = 1 WHERE id = 1");
        // 本类用例复用字面进路号 R1/R2：清理上个用例遗留的已发布占用，避免自身默认冲突串扰
        jdbc.update("UPDATE rail_day_plan SET status = 'CANCELLED' WHERE status = 'PUBLISHED'");
    }

    /**
     * 题干合成场景：R1 与 R2 互斥，R1 在 10:00 至 10:10 占用；
     * R2 的 10:09 申请失败（409），10:10 开始（端点相接）成功。
     */
    @Test
    void syntheticR1R2MutualExclusion() throws Exception {
        createRoute("R1");
        createRoute("R2");
        declareConflict("R1", "R2");

        // R1 在 10:00-10:10 占用（区段 sectionA）
        String sectionA = key("SEC");
        String sectionB = key("SEC");
        String planA = key("SCH");
        createPlan(planA, occ("G1", sectionA, "R1", iso(10, 0), iso(10, 10)));
        publishPlan(planA);

        // R2 的 10:09 申请与 R1 互斥且区间重叠 → 409，给出两条进路及计划号
        String planB = key("SCH");
        createPlan(planB, occ("G2", sectionB, "R2", iso(10, 9), iso(10, 20)));
        MvcResult conflict = mvc.perform(post("/api/v1/plans/{key}/publish", planB)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody()))
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(conflict);
        assertThat(error.get("code").asText()).isEqualTo("ROUTE_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("ROUTE_CONFLICT");
        assertThat(detail.get("routeId").asText()).isEqualTo("R2");
        assertThat(detail.get("conflictingRouteId").asText()).isEqualTo("R1");
        assertThat(detail.get("scheduleKey").asText()).isEqualTo(planB);
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(planA);

        // 整个计划不占任何新时隙：B 保持草稿，sectionB 上无生效时隙
        MvcResult detailB = mvc.perform(get("/api/v1/plans/{key}", planB))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detailB).get("status").asText()).isEqualTo("DRAFT");
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", sectionB))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).isEmpty();

        // R2 的 10:10 开始与 R1 的 10:10 结束端点相接（左闭右开）→ 合法
        String planC = key("SCH");
        createPlan(planC, occ("G3", sectionB, "R2", iso(10, 10), iso(10, 20)));
        publishPlan(planC);
    }

    @Test
    void unknownRouteRejectedOnCreateAndUpdate() throws Exception {
        // 创建时引用不存在的进路 → 422
        MvcResult created = mvc.perform(post("/api/v1/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("SCH"),
                                occ("G1", key("SEC"), key("R"), iso(10, 0), iso(10, 10)))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(created).get("code").asText()).isEqualTo("ROUTE_NOT_FOUND");

        // 整体替换时引用不存在的进路 → 422
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occNoRoute("G1", key("SEC"), iso(10, 0), iso(10, 10)));
        MvcResult updated = mvc.perform(put("/api/v1/plans/{key}/occupancies", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"expectedVersion\":1,"
                                + "\"occupancies\":[" + occ("G1", key("SEC"), key("R"),
                                iso(10, 0), iso(10, 10)) + "]}"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(updated).get("code").asText()).isEqualTo("ROUTE_NOT_FOUND");
    }

    @Test
    void sameRouteDefaultsToSelfConflict() throws Exception {
        createRoute("R1");
        String planA = key("SCH");
        createPlan(planA, occ("G1", key("SEC"), "R1", iso(10, 0), iso(10, 10)));
        publishPlan(planA);

        // 同一进路默认冲突，无需声明冲突对；不同区段同样互斥
        String planB = key("SCH");
        createPlan(planB, occ("G2", key("SEC"), "R1", iso(10, 5), iso(10, 15)));
        MvcResult conflict = mvc.perform(post("/api/v1/plans/{key}/publish", planB)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody()))
                .andExpect(status().isConflict()).andReturn();
        JsonNode detail = read(conflict).get("details").get(0);
        assertThat(detail.get("routeId").asText()).isEqualTo("R1");
        assertThat(detail.get("conflictingRouteId").asText()).isEqualTo("R1");
    }

    @Test
    void undeclaredRoutesCanOverlap() throws Exception {
        createRoute("R1");
        createRoute("R2");
        // 未声明冲突的不同进路可同时使用（区间完全重叠也合法）
        String planA = key("SCH");
        String planB = key("SCH");
        createPlan(planA, occ("G1", key("SEC"), "R1", iso(10, 0), iso(10, 10)));
        createPlan(planB, occ("G2", key("SEC"), "R2", iso(10, 0), iso(10, 10)));
        publishPlan(planA);
        publishPlan(planB);
    }

    @Test
    void cancelReleasesRouteOccupancyButKeepsHistory() throws Exception {
        createRoute("R1");
        createRoute("R2");
        declareConflict("R1", "R2");
        String planA = key("SCH");
        createPlan(planA, occ("G1", key("SEC"), "R1", iso(10, 0), iso(10, 10)));
        publishPlan(planA);

        // 互斥进路重叠申请被拒
        String planB = key("SCH");
        String sectionB = key("SEC");
        createPlan(planB, occ("G2", sectionB, "R2", iso(10, 5), iso(10, 15)));
        mvc.perform(post("/api/v1/plans/{key}/publish", planB)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody()))
                .andExpect(status().isConflict());

        // 取消 A 释放当前占用后，同一申请成功；A 的历史占用保留不删除
        cancelPlan(planA);
        publishPlan(planB);
        MvcResult detailA = mvc.perform(get("/api/v1/plans/{key}", planA))
                .andExpect(status().isOk()).andReturn();
        JsonNode bodyA = read(detailA);
        assertThat(bodyA.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(bodyA.get("occupancies")).hasSize(1);
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", sectionB))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).hasSize(1);
    }
    // ---------- 改签与进路 ----------

    @Test
    void rescheduleExcludesOwnOldRouteOccupancy() throws Exception {
        createRoute("R1");
        // 旧计划 R1 10:00-10:10 已发布
        String oldKey = key("SCH");
        createPlan(oldKey, occ("G1", key("SEC"), "R1", iso(10, 0), iso(10, 10)));
        publishPlan(oldKey);
        // 新草稿复用同一进路同一时隙：改签先在最终状态移除旧计划，自身旧占用不误阻断
        String newKey = key("SCH");
        createPlan(newKey, occ("G2", key("SEC"), "R1", iso(10, 0), iso(10, 10)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(newKey, 1, 1)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("oldPlan").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("newPlan").get("status").asText()).isEqualTo("PUBLISHED");
    }

    @Test
    void rescheduleBlockedByThirdPartyRouteConflictAndRollsBack() throws Exception {
        createRoute("R1");
        createRoute("R2");
        declareConflict("R1", "R2");
        // 第三方 R1 10:00-10:10 已发布
        String thirdParty = key("SCH");
        createPlan(thirdParty, occ("G9", key("SEC"), "R1", iso(10, 0), iso(10, 10)));
        publishPlan(thirdParty);
        // 旧计划（无进路）已发布，新草稿 R2 10:05-10:15 撞上第三方 R1
        String oldKey = key("SCH");
        createPlan(oldKey, occNoRoute("G1", key("SEC"), iso(8, 0), iso(9, 0)));
        publishPlan(oldKey);
        String newKey = key("SCH");
        createPlan(newKey, occ("G2", key("SEC"), "R2", iso(10, 5), iso(10, 15)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(newKey, 1, 1)))
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("ROUTE_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("routeId").asText()).isEqualTo("R2");
        assertThat(detail.get("conflictingRouteId").asText()).isEqualTo("R1");
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(thirdParty);

        // 回滚：旧计划仍发布、新计划仍草稿
        MvcResult oldDetail = mvc.perform(get("/api/v1/plans/{key}", oldKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(oldDetail).get("status").asText()).isEqualTo("PUBLISHED");
        MvcResult newDetail = mvc.perform(get("/api/v1/plans/{key}", newKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(newDetail).get("status").asText()).isEqualTo("DRAFT");
    }

    // ---------- 辅助 ----------

    private void createRoute(String routeId) throws Exception {
        mvc.perform(post("/api/v1/routes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key("REQ") + "\",\"operator\":\"tester\","
                                + "\"routeId\":\"" + routeId + "\"}"))
                .andExpect(status().isCreated());
    }

    private void declareConflict(String a, String b) throws Exception {
        mvc.perform(put("/api/v1/route-matrix").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key("REQ") + "\",\"operator\":\"tester\","
                                + "\"expectedVersion\":" + matrixVersion()
                                + ",\"addConflicts\":[{\"routeA\":\"" + a + "\",\"routeB\":\""
                                + b + "\"}],\"removeConflicts\":[]}"))
                .andExpect(status().isOk());
    }

    private int matrixVersion() throws Exception {
        MvcResult matrix = mvc.perform(get("/api/v1/route-matrix"))
                .andExpect(status().isOk()).andReturn();
        return read(matrix).get("version").asInt();
    }

    private void createPlan(String scheduleKey, String occupancy) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(scheduleKey, occupancy)))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody()))
                .andExpect(status().isOk());
    }

    private void cancelPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody()))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute) {
        Instant instant = DAY.atTime(hour, minute).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String occ(String train, String section, String route,
                              String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"routeId\":\"" + route + "\",\"startUtc\":\"" + startIso
                + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String occNoRoute(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String createBody(String scheduleKey, String occupancy) {
        return "{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":[" + occupancy + "]}";
    }

    private static String actionBody() {
        return "{\"requestKey\":\"" + key("REQ") + "\"}";
    }

    private static String rescheduleBody(String newScheduleKey, int expectedOldVersion,
                                         int expectedNewVersion) {
        return "{\"requestKey\":\"" + key("REQ") + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }
}