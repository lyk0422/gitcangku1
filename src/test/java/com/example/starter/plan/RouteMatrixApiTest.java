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
 * 进路注册与冲突矩阵 API 测试：主流程、上限、版本/参数失败分支与幂等边界（H2 内存库）。
 * 每个用例前清空进路相关表并将矩阵版本重置为 1，用例间互不干扰。
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
    void cleanRouteTables() {
        jdbc.update("DELETE FROM rail_route_conflict");
        jdbc.update("DELETE FROM rail_route");
        jdbc.update("UPDATE rail_route_matrix SET version = 1 WHERE id = 1");
    }

    // ---------- 主流程 ----------

    @Test
    void createRouteAndQueryMatrix() throws Exception {
        String r1 = key("R");
        String r2 = key("R");

        JsonNode empty = matrix();
        assertThat(empty.get("version").asInt()).isEqualTo(1);
        assertThat(empty.get("routes")).isEmpty();
        assertThat(empty.get("conflicts")).isEmpty();

        createRoute(r2);
        createRoute(r1);

        JsonNode matrix = matrix();
        assertThat(matrix.get("version").asInt()).isEqualTo(1);
        assertThat(matrix.get("routes")).hasSize(2);
        // 按字典序输出
        String first = r1.compareTo(r2) < 0 ? r1 : r2;
        assertThat(matrix.get("routes").get(0).asText()).isEqualTo(first);
    }

    @Test
    void updateMatrixAddsAndRemovesPairsAndIncrementsVersion() throws Exception {
        String r1 = key("R");
        String r2 = key("R");
        createRoute(r1);
        createRoute(r2);

        // 声明方向无关：(r2, r1) 与 (r1, r2) 等价，规范化存储
        MvcResult added = mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, "[" + pair(r2, r1) + "]", "[]")))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(added);
        assertThat(body.get("version").asInt()).isEqualTo(2);
        assertThat(body.get("conflicts")).hasSize(1);
        String min = r1.compareTo(r2) < 0 ? r1 : r2;
        String max = min.equals(r1) ? r2 : r1;
        assertThat(body.get("conflicts").get(0).get("routeA").asText()).isEqualTo(min);
        assertThat(body.get("conflicts").get(0).get("routeB").asText()).isEqualTo(max);

        // 重复声明同一对为无操作，版本仍加一
        mvc.perform(put("/api/v1/route-matrix").contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 2, "[" + pair(r1, r2) + "]", "[]")))
                .andExpect(status().isOk());
        assertThat(matrix().get("conflicts")).hasSize(1);

        // 移除冲突对
        MvcResult removed = mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 3, "[]", "[" + pair(r1, r2) + "]")))
                .andExpect(status().isOk()).andReturn();
        JsonNode afterRemove = read(removed);
        assertThat(afterRemove.get("version").asInt()).isEqualTo(4);
        assertThat(afterRemove.get("conflicts")).isEmpty();
    }

    // ---------- 失败分支 ----------

    @Test
    void duplicateRouteAndLimitRejected() throws Exception {
        String r1 = key("R");
        createRoute(r1);
        // 重复进路 → 409
        MvcResult dup = mvc.perform(post("/api/v1/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), r1)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(dup).get("code").asText()).isEqualTo("ROUTE_EXISTS");

        // 上限 20 条：再补 19 条后第 21 条被拒
        for (int i = 0; i < 19; i++) {
            createRoute(key("R"));
        }
        MvcResult overflow = mvc.perform(post("/api/v1/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), key("R"))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(overflow).get("code").asText()).isEqualTo("ROUTE_LIMIT_EXCEEDED");
        assertThat(matrix().get("routes")).hasSize(20);
    }

    @Test
    void updateMatrixRejectsVersionConflictUnknownRouteAndSelfPair() throws Exception {
        String r1 = key("R");
        createRoute(r1);

        // 版本不匹配 → 409，矩阵不变
        MvcResult versionConflict = mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 7, "[]", "[]")))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(versionConflict).get("code").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(matrix().get("version").asInt()).isEqualTo(1);

        // 引用不存在的进路 → 422
        MvcResult unknown = mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1,
                                "[" + pair(r1, key("R")) + "]", "[]")))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(unknown).get("code").asText()).isEqualTo("ROUTE_NOT_FOUND");

        // 自身冲突对（自身默认冲突，无需声明）→ 400
        mvc.perform(put("/api/v1/route-matrix").contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, "[" + pair(r1, r1) + "]", "[]")))
                .andExpect(status().isBadRequest());
        assertThat(matrix().get("version").asInt()).isEqualTo(1);
    }
    @Test
    void addConflictCollidingWithPublishedOccupancyRejected() throws Exception {
        String r1 = key("R");
        String r2 = key("R");
        createRoute(r1);
        createRoute(r2);
        // 未声明冲突时两进路可并行：两条已发布占用区间重叠
        String planA = key("SCH");
        String planB = key("SCH");
        createPlan(planA, occ("G1", key("SEC"), r1, iso(10, 0), iso(10, 10)));
        publishPlan(planA);
        createPlan(planB, occ("G2", key("SEC"), r2, iso(10, 5), iso(10, 15)));
        publishPlan(planB);

        // 新增冲突对撞上已发布占用 → 409，矩阵版本不变
        MvcResult rejected = mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, "[" + pair(r1, r2) + "]", "[]")))
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("ROUTE_MATRIX_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("ROUTE_MATRIX_CONFLICT");
        assertThat(detail.get("scheduleKeyA").asText()).isIn(planA, planB);
        assertThat(detail.get("scheduleKeyB").asText()).isIn(planA, planB);
        assertThat(matrix().get("version").asInt()).isEqualTo(1);
        assertThat(matrix().get("conflicts")).isEmpty();

        // 取消 B 释放占用后，同一新增可成功
        cancelPlan(planB);
        mvc.perform(put("/api/v1/route-matrix").contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, "[" + pair(r1, r2) + "]", "[]")))
                .andExpect(status().isOk());
        assertThat(matrix().get("version").asInt()).isEqualTo(2);
    }

    // ---------- 幂等 ----------

    @Test
    void routeIdempotentReplayAndParamConflict() throws Exception {
        String r1 = key("R");
        String requestId = key("REQ");
        String body = routeBody(requestId, r1);

        MvcResult first = mvc.perform(post("/api/v1/routes")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        // 同键同参重放首次完整响应
        MvcResult replay = mvc.perform(post("/api/v1/routes")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键异参 → 409
        mvc.perform(post("/api/v1/routes").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(requestId, key("R"))))
                .andExpect(status().isConflict());
    }

    @Test
    void matrixFailureDoesNotConsumeRequestId() throws Exception {
        String r1 = key("R");
        String r2 = key("R");
        createRoute(r1);
        createRoute(r2);
        String requestId = key("REQ");

        // 版本不匹配失败不占键
        mvc.perform(put("/api/v1/route-matrix").contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(requestId, 9, "[" + pair(r1, r2) + "]", "[]")))
                .andExpect(status().isConflict());
        // 同键修正参数后成功
        mvc.perform(put("/api/v1/route-matrix").contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(requestId, 1, "[" + pair(r1, r2) + "]", "[]")))
                .andExpect(status().isOk());
        // 同键同参重放返回首次结果
        MvcResult replay = mvc.perform(put("/api/v1/route-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(requestId, 1, "[" + pair(r1, r2) + "]", "[]")))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay).get("version").asInt()).isEqualTo(2);
        // 矩阵只更新一次
        assertThat(matrix().get("version").asInt()).isEqualTo(2);
    }

    @Test
    void structuredIdentityAvoidsSeparatorCollision() throws Exception {
        // 拼接式身份会把 ("a", "b|c") 与 ("a|b", "c") 混为一谈；结构化比较必须区分
        String requestId = key("REQ");
        mvc.perform(post("/api/v1/routes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"operator\":\"a\","
                                + "\"routeId\":\"b|c\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/routes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"operator\":\"a|b\","
                                + "\"routeId\":\"c\"}"))
                .andExpect(status().isConflict());
    }

    // ---------- 辅助 ----------

    private JsonNode matrix() throws Exception {
        return read(mvc.perform(get("/api/v1/route-matrix"))
                .andExpect(status().isOk()).andReturn());
    }

    private void createRoute(String routeId) throws Exception {
        mvc.perform(post("/api/v1/routes").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), routeId)))
                .andExpect(status().isCreated());
    }

    private void createPlan(String scheduleKey, String occupancy) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":["
                                + occupancy + "]}"))
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

    private static String pair(String a, String b) {
        return "{\"routeA\":\"" + a + "\",\"routeB\":\"" + b + "\"}";
    }

    private static String routeBody(String requestId, String routeId) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"tester\","
                + "\"routeId\":\"" + routeId + "\"}";
    }

    private static String updateBody(String requestId, int expectedVersion,
                                     String addsJson, String removesJson) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"tester\","
                + "\"expectedVersion\":" + expectedVersion
                + ",\"addConflicts\":" + addsJson + ",\"removeConflicts\":" + removesJson + "}";
    }
}