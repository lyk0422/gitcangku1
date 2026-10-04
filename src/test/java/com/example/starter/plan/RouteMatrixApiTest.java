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
 * 进路注册与冲突矩阵 API 测试：声明上限、矩阵版本与乐观更新、发布/改签/取消的
 * 进路互斥裁决、requestId 幂等边界（H2 内存库）。每个用例前清空全部业务表。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RouteMatrixApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM rail_plan_reschedule_link");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM rail_plan_occupancy");
        jdbc.update("DELETE FROM rail_day_plan");
        jdbc.update("DELETE FROM rail_route_conflict");
        jdbc.update("UPDATE rail_route_matrix SET version = 1 WHERE id = 1");
        jdbc.update("DELETE FROM rail_route");
    }

    // ---------- 进路声明 ----------

    @Test
    void declareRouteAndListRoutes() throws Exception {
        MvcResult declared = mvc.perform(put("/api/v1/routes/{id}", "R1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declareBody("REQ-D1", "dispatcher-1")))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(declared).get("routeId").asText()).isEqualTo("R1");

        MvcResult list = mvc.perform(get("/api/v1/routes"))
                .andExpect(status().isOk()).andReturn();
        JsonNode routes = read(list);
        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).get("routeId").asText()).isEqualTo("R1");

        // 重复声明（新 requestId）幂等返回当前状态
        mvc.perform(put("/api/v1/routes/{id}", "R1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declareBody("REQ-D2", "dispatcher-2")))
                .andExpect(status().isOk());
    }

    @Test
    void declareRouteIdempotentReplayAndParamConflict() throws Exception {
        String body = declareBody("REQ-DEM", "dispatcher-1");
        MvcResult first = mvc.perform(put("/api/v1/routes/{id}", "R-IDEM")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放首次完整响应
        MvcResult replay = mvc.perform(put("/api/v1/routes/{id}", "R-IDEM")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键异参（操作者不同）→ 409
        mvc.perform(put("/api/v1/routes/{id}", "R-IDEM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declareBody("REQ-DEM", "dispatcher-2")))
                .andExpect(status().isConflict());
        // 同键异参（资源不同）→ 409
        mvc.perform(put("/api/v1/routes/{id}", "R-OTHER")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void declareRouteLimitTwenty() throws Exception {
        for (int i = 1; i <= 20; i++) {
            mvc.perform(put("/api/v1/routes/{id}", "R-L" + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(declareBody("REQ-L" + i, "dispatcher")))
                    .andExpect(status().isOk());
        }
        // 第 21 条进路超出上限 → 409
        MvcResult overflow = mvc.perform(put("/api/v1/routes/{id}", "R-L21")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declareBody("REQ-L21", "dispatcher")))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(overflow).get("code").asText()).isEqualTo("ROUTE_LIMIT_EXCEEDED");

        // 已满时重复声明既有进路仍幂等成功
        mvc.perform(put("/api/v1/routes/{id}", "R-L1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declareBody("REQ-L1R", "dispatcher")))
                .andExpect(status().isOk());
    }
    // ---------- 冲突矩阵维护 ----------

    @Test
    void matrixStartsAtVersionOneAndIncrementsWithOptimisticCheck() throws Exception {
        MvcResult initial = mvc.perform(get("/api/v1/route-matrix"))
                .andExpect(status().isOk()).andReturn();
        JsonNode matrix = read(initial);
        assertThat(matrix.get("version").asLong()).isEqualTo(1);
        assertThat(matrix.get("conflicts")).isEmpty();

        declareRoute("R1");
        declareRoute("R2");
        MvcResult updated = mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matrixBody("REQ-M1", "dispatcher", 1,
                                pair("R1", "R2"), null)))
                .andExpect(status().isOk()).andReturn();
        JsonNode after = read(updated);
        assertThat(after.get("version").asLong()).isEqualTo(2);
        assertThat(after.get("conflicts")).hasSize(1);
        assertThat(after.get("conflicts").get(0).get("routeA").asText()).isEqualTo("R1");
        assertThat(after.get("conflicts").get(0).get("routeB").asText()).isEqualTo("R2");

        // 期望版本过期 → 409，矩阵不变
        mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matrixBody("REQ-M2", "dispatcher", 1,
                                null, pair("R1", "R2"))))
                .andExpect(status().isConflict());
        assertThat(read(mvc.perform(get("/api/v1/route-matrix")).andReturn())
                .get("version").asLong()).isEqualTo(2);

        // 逆序声明同一冲突对（对称）→ 规范化后视为已存在，仅版本递增
        MvcResult reversed = mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matrixBody("REQ-M3", "dispatcher", 2,
                                pair("R2", "R1"), null)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(reversed).get("conflicts")).hasSize(1);

        // 移除冲突对 → 版本 3，矩阵为空
        MvcResult removed = mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matrixBody("REQ-M4", "dispatcher", 3,
                                null, pair("R2", "R1"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode removedBody = read(removed);
        assertThat(removedBody.get("version").asLong()).isEqualTo(4);
        assertThat(removedBody.get("conflicts")).isEmpty();
    }

    @Test
    void updateMatrixRejectsUnknownRouteAndSelfPair() throws Exception {
        declareRoute("R1");
        // 引用未注册进路 → 422
        MvcResult unknown = mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matrixBody("REQ-MU", "dispatcher", 1,
                                pair("R1", "R-GHOST"), null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(unknown).get("code").asText()).isEqualTo("ROUTE_NOT_FOUND");

        // 同一进路自对 → 400（进路自身默认冲突，无需声明）
        mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matrixBody("REQ-MS", "dispatcher", 1,
                                pair("R1", "R1"), null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void matrixUpdateIdempotencyAndFailureDoesNotConsumeKey() throws Exception {
        declareRoute("R1");
        declareRoute("R2");
        declareRoute("R3");
        String body = matrixBody("REQ-MI", "dispatcher", 1, pair("R1", "R2"), null);
        MvcResult first = mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放首次完整响应，版本不再递增
        MvcResult replay = mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        assertThat(read(mvc.perform(get("/api/v1/route-matrix")).andReturn())
                .get("version").asLong()).isEqualTo(2);

        // 同键异参 → 409
        mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matrixBody("REQ-MI", "dispatcher", 2,
                                pair("R1", "R3"), null)))
                .andExpect(status().isConflict());

        // 失败不占键：版本校验失败的 requestId 修正参数后可重用
        mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matrixBody("REQ-MF", "dispatcher", 9,
                                pair("R1", "R3"), null)))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matrixBody("REQ-MF", "dispatcher", 2,
                                pair("R1", "R3"), null)))
                .andExpect(status().isOk());
    }
    // ---------- 发布 / 改签 / 取消的进路互斥 ----------

    @Test
    void publishRejectsUnknownRouteWith422() throws Exception {
        createPlan("SCH-UNK", DAY, occ("G1", "SEC-1", "R-GHOST", iso(8, 0), iso(9, 0)));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", "SCH-UNK")
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody("REQ-P1")))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("ROUTE_NOT_FOUND");
        assertThat(error.get("details").get(0).get("routeId").asText()).isEqualTo("R-GHOST");
        // 整个计划不占任何新时隙：仍为草稿
        assertThat(planStatus("SCH-UNK")).isEqualTo("DRAFT");
    }

    /**
     * 题干合成场景：R1 与 R2 互斥，R1 在 10:00-10:10 占用；
     * R2 的 10:09 申请失败（409），10:10 开始（端点相接）成功。
     */
    @Test
    void syntheticR1R2MutualExclusion() throws Exception {
        declareRoute("R1");
        declareRoute("R2");
        mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matrixBody("REQ-MX", "dispatcher", 1, pair("R1", "R2"), null)))
                .andExpect(status().isOk());

        // R1 在 10:00-10:10 占用（区段 SEC-A）
        createPlan("SCH-R1", DAY, occ("G1", "SEC-A", "R1", iso(10, 0), iso(10, 10)));
        publishPlan("SCH-R1", "REQ-PR1");

        // R2 在另一区段 10:09 申请 → 409，给出两条进路及两个计划号
        createPlan("SCH-R2-EARLY", DAY, occ("G2", "SEC-B", "R2", iso(10, 9), iso(10, 20)));
        MvcResult conflict = mvc.perform(post("/api/v1/plans/{key}/publish", "SCH-R2-EARLY")
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody("REQ-PR2")))
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(conflict);
        assertThat(error.get("code").asText()).isEqualTo("ROUTE_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("ROUTE_CONFLICT");
        assertThat(detail.get("routeId").asText()).isEqualTo("R2");
        assertThat(detail.get("conflictingRouteId").asText()).isEqualTo("R1");
        assertThat(detail.get("scheduleKey").asText()).isEqualTo("SCH-R2-EARLY");
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo("SCH-R1");
        // 整个计划不占任何新时隙
        assertThat(planStatus("SCH-R2-EARLY")).isEqualTo("DRAFT");
        assertThat(publishedSlots("SEC-B")).isEmpty();

        // R2 从 10:10 开始（端点相接）→ 发布成功
        createPlan("SCH-R2-OK", DAY, occ("G3", "SEC-B", "R2", iso(10, 10), iso(10, 20)));
        publishPlan("SCH-R2-OK", "REQ-PR3");
        assertThat(planStatus("SCH-R2-OK")).isEqualTo("PUBLISHED");
    }

    @Test
    void undeclaredRoutesCoexistButSameRouteDefaultsToConflict() throws Exception {
        declareRoute("R1");
        declareRoute("R2");
        // 未声明冲突对的不同进路可同时使用（同一时刻、不同区段）
        createPlan("SCH-FREE1", DAY, occ("G1", "SEC-A", "R1", iso(10, 0), iso(11, 0)));
        publishPlan("SCH-FREE1", "REQ-F1");
        createPlan("SCH-FREE2", DAY, occ("G2", "SEC-B", "R2", iso(10, 0), iso(11, 0)));
        publishPlan("SCH-FREE2", "REQ-F2");

        // 进路自身默认冲突：另一计划同进路重叠 → 409
        createPlan("SCH-SELF", DAY, occ("G3", "SEC-C", "R1", iso(10, 30), iso(11, 30)));
        MvcResult conflict = mvc.perform(post("/api/v1/plans/{key}/publish", "SCH-SELF")
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody("REQ-F3")))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(conflict).get("code").asText()).isEqualTo("ROUTE_CONFLICT");
        assertThat(planStatus("SCH-SELF")).isEqualTo("DRAFT");

        // 无进路编号的占用不参与进路互斥，仅受区段容量约束
        createPlan("SCH-NOROUTE", DAY, occ("G4", "SEC-D", null, iso(10, 0), iso(11, 0)));
        publishPlan("SCH-NOROUTE", "REQ-F4");
    }

    @Test
    void matrixUpdateHittingPublishedOccupancyRejected() throws Exception {
        declareRoute("R1");
        declareRoute("R2");
        // 无冲突对时两进路重叠占用合法发布
        createPlan("SCH-H1", DAY, occ("G1", "SEC-A", "R1", iso(10, 0), iso(10, 10)));
        publishPlan("SCH-H1", "REQ-H1");
        createPlan("SCH-H2", DAY, occ("G2", "SEC-B", "R2", iso(10, 5), iso(10, 15)));
        publishPlan("SCH-H2", "REQ-H2");

        // 新增冲突对撞上已发布占用 → 409，矩阵版本与冲突对不变
        MvcResult rejected = mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matrixBody("REQ-MH", "dispatcher", 1, pair("R1", "R2"), null)))
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("ROUTE_MATRIX_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("routeA").asText()).isEqualTo("R1");
        assertThat(detail.get("routeB").asText()).isEqualTo("R2");
        JsonNode matrix = read(mvc.perform(get("/api/v1/route-matrix")).andReturn());
        assertThat(matrix.get("version").asLong()).isEqualTo(1);
        assertThat(matrix.get("conflicts")).isEmpty();

        // 取消其中一个计划释放占用后，同样的更新成功
        cancelPlan("SCH-H2", "REQ-H3");
        mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matrixBody("REQ-MH2", "dispatcher", 1, pair("R1", "R2"), null)))
                .andExpect(status().isOk());
    }
    @Test
    void rescheduleRemovesOldPlanBeforeRouteCheck() throws Exception {
        declareRoute("R1");
        // 旧计划已发布 R1 10:00-10:10
        createPlan("SCH-OLD", DAY, occ("G1", "SEC-A", "R1", iso(10, 0), iso(10, 10)));
        publishPlan("SCH-OLD", "REQ-RO");
        // 新草稿复用旧计划的进路与区间（10:00-10:30 覆盖旧占用），改签须先移除旧占用
        createPlan("SCH-NEW", DAY, occ("G2", "SEC-B", "R1", iso(10, 0), iso(10, 30)));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", "SCH-OLD")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"REQ-RR\",\"newScheduleKey\":\"SCH-NEW\","
                                + "\"expectedOldVersion\":1,\"expectedNewVersion\":1}"))
                .andExpect(status().isOk());
        assertThat(planStatus("SCH-OLD")).isEqualTo("CANCELLED");
        assertThat(planStatus("SCH-NEW")).isEqualTo("PUBLISHED");

        // 改签冻结矩阵版本：新计划记录发布时的矩阵版本
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", "SCH-NEW"))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("matrixVersion").asLong()).isEqualTo(1);
    }

    @Test
    void rescheduleRouteConflictWithThirdPartyRollsBack() throws Exception {
        declareRoute("R1");
        declareRoute("R2");
        mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matrixBody("REQ-MT", "dispatcher", 1, pair("R1", "R2"), null)))
                .andExpect(status().isOk());
        // 第三方已发布 R2 10:00-10:10
        createPlan("SCH-3RD", DAY, occ("G9", "SEC-C", "R2", iso(10, 0), iso(10, 10)));
        publishPlan("SCH-3RD", "REQ-T0");
        // 旧计划 R1 09:00-09:30 已发布；新草稿 R1 10:05-10:20 与第三方 R2 冲突
        createPlan("SCH-TOLD", DAY, occ("G1", "SEC-A", "R1", iso(9, 0), iso(9, 30)));
        publishPlan("SCH-TOLD", "REQ-T1");
        createPlan("SCH-TNEW", DAY, occ("G2", "SEC-B", "R1", iso(10, 5), iso(10, 20)));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/reschedule", "SCH-TOLD")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"REQ-T2\",\"newScheduleKey\":\"SCH-TNEW\","
                                + "\"expectedOldVersion\":1,\"expectedNewVersion\":1}"))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("ROUTE_CONFLICT");
        // 整体回滚：旧计划仍发布，新计划仍草稿
        assertThat(planStatus("SCH-TOLD")).isEqualTo("PUBLISHED");
        assertThat(planStatus("SCH-TNEW")).isEqualTo("DRAFT");
    }

    @Test
    void cancelReleasesRouteOccupancyButKeepsHistory() throws Exception {
        declareRoute("R1");
        createPlan("SCH-C1", DAY, occ("G1", "SEC-A", "R1", iso(10, 0), iso(10, 10)));
        publishPlan("SCH-C1", "REQ-C1");
        cancelPlan("SCH-C1", "REQ-C2");
        // 历史占用保留
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", "SCH-C1"))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(detail);
        assertThat(body.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("occupancies")).hasSize(1);
        assertThat(body.get("occupancies").get(0).get("routeId").asText()).isEqualTo("R1");
        // 占用已释放：同进路重叠的新计划可发布
        createPlan("SCH-C2", DAY, occ("G2", "SEC-B", "R1", iso(10, 0), iso(10, 10)));
        publishPlan("SCH-C2", "REQ-C3");
    }

    @Test
    void publishFreezesMatrixVersionOnPlan() throws Exception {
        declareRoute("R1");
        declareRoute("R2");
        createPlan("SCH-MV", DAY, occ("G1", "SEC-A", "R1", iso(10, 0), iso(10, 10)));
        publishPlan("SCH-MV", "REQ-MV");
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", "SCH-MV"))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("matrixVersion").asLong()).isEqualTo(1);

        // 矩阵演进后，已发布计划保留发布时冻结的版本
        mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matrixBody("REQ-MV2", "dispatcher", 1, pair("R1", "R2"), null)))
                .andExpect(status().isOk());
        MvcResult after = mvc.perform(get("/api/v1/plans/{key}", "SCH-MV"))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(after).get("matrixVersion").asLong()).isEqualTo(1);
    }
    // ---------- 辅助 ----------

    private void declareRoute(String routeId) throws Exception {
        mvc.perform(put("/api/v1/routes/{id}", routeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declareBody("REQ-D-" + routeId, "dispatcher")))
                .andExpect(status().isOk());
    }

    private void createPlan(String scheduleKey, LocalDate opDate, String... occupancies)
            throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"REQ-C-" + scheduleKey
                                + "\",\"scheduleKey\":\"" + scheduleKey + "\",\"opDate\":\""
                                + opDate + "\",\"occupancies\":[" + String.join(",", occupancies)
                                + "]}"))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk());
    }

    private void cancelPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk());
    }

    private String planStatus(String scheduleKey) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(detail).get("status").asText();
    }

    private JsonNode publishedSlots(String sectionId) throws Exception {
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", sectionId))
                .andExpect(status().isOk()).andReturn();
        return read(slots);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute) {
        Instant instant = DAY.atTime(hour, minute).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String occ(String train, String section, String route,
                              String startIso, String endIso) {
        String routeField = route == null ? "" : "\"routeId\":\"" + route + "\",";
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section + "\","
                + routeField + "\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String pair(String routeA, String routeB) {
        return "{\"routeA\":\"" + routeA + "\",\"routeB\":\"" + routeB + "\"}";
    }

    private static String declareBody(String requestId, String operator) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator + "\"}";
    }

    private static String matrixBody(String requestId, String operator, long expectedVersion,
                                     String addPair, String removePair) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"expectedVersion\":" + expectedVersion
                + ",\"addPairs\":[" + (addPair == null ? "" : addPair)
                + "],\"removePairs\":[" + (removePair == null ? "" : removePair) + "]}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }
}