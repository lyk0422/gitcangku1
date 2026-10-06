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
 * 线路图替换与寻径发布的端到端测试（H2 内存库）：
 * 图版本校验、寻径选择规则、失败分支、幂等与换图隔离。
 * 各用例使用唯一站名/区段/业务键，共享库内互不干扰；图版本经 GET 现查现用。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoutePlanningApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 图替换 ----------

    @Test
    void replaceGraphIncrementsVersionAndRejectsStale() throws Exception {
        int before = graphVersion();
        String suffix = suffix();
        MvcResult replaced = mvc.perform(put("/api/v1/line-graph")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), before,
                                seg("SEC-A-" + suffix, "A-" + suffix, "B-" + suffix, 5))))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(replaced);
        assertThat(body.get("version").asInt()).isEqualTo(before + 1);
        assertThat(body.get("segments")).hasSize(1);
        assertThat(body.get("segments").get(0).get("sectionId").asText())
                .isEqualTo("SEC-A-" + suffix);

        // 过期图版本 → 409
        mvc.perform(put("/api/v1/line-graph").contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), before,
                                seg("SEC-B-" + suffix, "A-" + suffix, "B-" + suffix, 6))))
                .andExpect(status().isConflict());

        // 当前版本已推进
        assertThat(graphVersion()).isEqualTo(before + 1);
    }

    @Test
    void replaceGraphRejectsInvalidSegments() throws Exception {
        int version = graphVersion();
        String p = suffix();
        // 区段编号重复
        mvc.perform(put("/api/v1/line-graph").contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), version,
                                seg("SEC-D-" + p, "A-" + p, "B-" + p, 3),
                                seg("SEC-D-" + p, "B-" + p, "C-" + p, 3))))
                .andExpect(status().isBadRequest());
        // 起终站相同
        mvc.perform(put("/api/v1/line-graph").contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), version,
                                seg("SEC-E-" + p, "A-" + p, "A-" + p, 3))))
                .andExpect(status().isBadRequest());
        // 行驶分钟为 0
        mvc.perform(put("/api/v1/line-graph").contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), version,
                                seg("SEC-F-" + p, "A-" + p, "B-" + p, 0))))
                .andExpect(status().isBadRequest());
        // 21 条区段超上限
        String[] many = new String[21];
        for (int i = 0; i < 21; i++) {
            many[i] = seg("SEC-M" + i + "-" + p, "A-" + p, "B-" + p, 1);
        }
        mvc.perform(put("/api/v1/line-graph").contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), version, many)))
                .andExpect(status().isBadRequest());
        // 11 个站点超上限
        String[] stations = new String[10];
        for (int i = 0; i < 10; i++) {
            stations[i] = seg("SEC-S" + i + "-" + p, "N" + i + "-" + p, "N" + (i + 1) + "-" + p, 1);
        }
        mvc.perform(put("/api/v1/line-graph").contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), version, stations)))
                .andExpect(status().isBadRequest());
        // 图版本未被失败请求推进
        assertThat(graphVersion()).isEqualTo(version);
    }

    @Test
    void replaceGraphIdempotentReplayAndParamConflict() throws Exception {
        int version = graphVersion();
        String p = suffix();
        String requestId = key("REQ");
        String body = graphBody(requestId, version, seg("SEC-I-" + p, "A-" + p, "B-" + p, 4));

        MvcResult first = mvc.perform(put("/api/v1/line-graph")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放返回首次结果，版本不再推进
        MvcResult replay = mvc.perform(put("/api/v1/line-graph")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        assertThat(graphVersion()).isEqualTo(version + 1);

        // 同键异参 → 409
        mvc.perform(put("/api/v1/line-graph").contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(requestId, version + 1,
                                seg("SEC-J-" + p, "A-" + p, "B-" + p, 5))))
                .andExpect(status().isConflict());
    }

    // ---------- 寻径失败分支 ----------

    @Test
    void publishRouteNoFeasiblePathLeavesNothing() throws Exception {
        String p = suffix();
        String s = "S-" + p;
        String t = "T-" + p;
        String direct = "SEC-ST-" + p;
        int version = replaceGraph(seg(direct, s, t, 5));

        // 直达被占满 08:00-08:10，截止 08:08：直达与唯一路径均不可行
        String blocker = key("SCH");
        createPlan(blocker, occ("G-BLK", direct, iso(8, 0), iso(8, 10)));
        publishPlan(blocker);

        String scheduleKey = key("SCH");
        String requestId = key("REQ");
        MvcResult result = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(requestId, scheduleKey, version, "G-5", s, t,
                                iso(8, 0), iso(8, 8))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("NO_FEASIBLE_ROUTE");

        // 不建计划、不留占用；失败不占幂等键，修正截止后同键成功
        mvc.perform(get("/api/v1/plans/{key}", scheduleKey)).andExpect(status().isNotFound());
        JsonNode slots = read(mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", direct))
                .andExpect(status().isOk()).andReturn());
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).get("scheduleKey").asText()).isEqualTo(blocker);

        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(requestId, scheduleKey, version, "G-5", s, t,
                                iso(8, 10), iso(8, 30))))
                .andExpect(status().isCreated());
    }

    @Test
    void publishRouteCrossOperationDayRejected() throws Exception {
        String p = suffix();
        String s = "S-" + p;
        String t = "T-" + p;
        int version = replaceGraph(seg("SEC-ST-" + p, s, t, 10));

        // 23:55 出发行驶 10 分钟跨 Asia/Shanghai 运营日 → 无可行路径
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), key("SCH"), version, "G-6", s, t,
                                iso(23, 55), iso(0, 10, 1))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void publishRouteUnknownStationReturns404() throws Exception {
        String p = suffix();
        String s = "S-" + p;
        String t = "T-" + p;
        int version = replaceGraph(seg("SEC-ST-" + p, s, t, 5));

        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), key("SCH"), version, "G-7", "NOPE-" + p, t,
                                iso(12, 0), iso(12, 30))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), key("SCH"), version, "G-7", s, "NOPE-" + p,
                                iso(12, 0), iso(12, 30))))
                .andExpect(status().isNotFound());
    }

    @Test
    void publishRouteStaleGraphVersionReturns409() throws Exception {
        String p = suffix();
        String s = "S-" + p;
        String t = "T-" + p;
        int version = replaceGraph(seg("SEC-ST-" + p, s, t, 5));

        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), key("SCH"), version + 1, "G-8", s, t,
                                iso(13, 0), iso(13, 30))))
                .andExpect(status().isConflict());
    }

    @Test
    void publishRouteRejectsInvalidParams() throws Exception {
        String p = suffix();
        String s = "S-" + p;
        String t = "T-" + p;
        int version = replaceGraph(seg("SEC-ST-" + p, s, t, 5));

        // 出发非 UTC 整分钟
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), key("SCH"), version, "G-9", s, t,
                                "2026-09-22T05:00:30Z", iso(13, 30))))
                .andExpect(status().isBadRequest());
        // 最晚到达不晚于出发
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), key("SCH"), version, "G-9", s, t,
                                iso(13, 0), iso(13, 0))))
                .andExpect(status().isBadRequest());
        // 起终站相同
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), key("SCH"), version, "G-9", s, s,
                                iso(13, 0), iso(13, 30))))
                .andExpect(status().isBadRequest());
    }

    // ---------- 幂等 / 换图隔离 / 取消 ----------

    @Test
    void publishRouteIdempotentReplayAndParamConflict() throws Exception {
        String p = suffix();
        String s = "S-" + p;
        String t = "T-" + p;
        int version = replaceGraph(seg("SEC-ST-" + p, s, t, 5));

        String scheduleKey = key("SCH");
        String requestId = key("REQ");
        String body = routeBody(requestId, scheduleKey, version, "G-10", s, t,
                iso(14, 0), iso(14, 30));
        MvcResult first = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键异参 → 409；库里仍只有一张计划
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(requestId, key("SCH"), version, "G-10", s, t,
                                iso(14, 0), iso(14, 30))))
                .andExpect(status().isConflict());
        JsonNode slots = read(mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", "SEC-ST-" + p))
                .andExpect(status().isOk()).andReturn());
        assertThat(slots).hasSize(1);
    }

    @Test
    void graphReplacementDoesNotRewritePublishedRoute() throws Exception {
        String p = suffix();
        String s = "S-" + p;
        String t = "T-" + p;
        String direct = "SEC-ST-" + p;
        int version = replaceGraph(seg(direct, s, t, 5));

        String scheduleKey = key("SCH");
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), scheduleKey, version, "G-11", s, t,
                                iso(15, 0), iso(15, 30))))
                .andExpect(status().isCreated());

        // 换图：删除直达区段，图版本推进；旧计划占用与状态不变
        int next = replaceGraph(seg("SEC-NEW-" + p, "X-" + p, "Y-" + p, 3));
        assertThat(next).isEqualTo(version + 1);
        JsonNode plan = read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(plan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(plan.get("occupancies")).hasSize(1);
        assertThat(plan.get("occupancies").get(0).get("sectionId").asText()).isEqualTo(direct);

        // 旧占用仍生效：同时段另一计划冲突 422
        String rival = key("SCH");
        createPlan(rival, occ("G-RV", direct, iso(15, 0), iso(15, 5)));
        mvc.perform(post("/api/v1/plans/{key}/publish", rival)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void cancelRoutePlanReleasesSlots() throws Exception {
        String p = suffix();
        String s = "S-" + p;
        String t = "T-" + p;
        String direct = "SEC-ST-" + p;
        int version = replaceGraph(seg(direct, s, t, 5));

        String scheduleKey = key("SCH");
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), scheduleKey, version, "G-12", s, t,
                                iso(16, 0), iso(16, 30))))
                .andExpect(status().isCreated());

        // 取消走原流程：时隙立即释放，历史保留
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        JsonNode plan = read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(plan.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(plan.get("occupancies")).hasSize(1);

        // 释放后同图同时段可再寻径发布
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), key("SCH"), version, "G-13", s, t,
                                iso(16, 0), iso(16, 30))))
                .andExpect(status().isCreated());
    }

    // ---------- 辅助 ----------

    private int graphVersion() throws Exception {
        JsonNode graph = read(mvc.perform(get("/api/v1/line-graph"))
                .andExpect(status().isOk()).andReturn());
        return graph.get("version").asInt();
    }

    /** 以当前版本整体替换线路图，返回新版本号。 */
    private int replaceGraph(String... segments) throws Exception {
        int version = graphVersion();
        MvcResult result = mvc.perform(put("/api/v1/line-graph")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), version, segments)))
                .andExpect(status().isOk()).andReturn();
        return read(result).get("version").asInt();
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":["
                                + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute) {
        return iso(hour, minute, 0);
    }

    /** 运营日加 dayOffset 天后 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute, int dayOffset) {
        Instant instant = DAY.plusDays(dayOffset).atTime(hour, minute).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String seg(String sectionId, String from, String to, int minutes) {
        return "{\"sectionId\":\"" + sectionId + "\",\"fromStation\":\"" + from
                + "\",\"toStation\":\"" + to + "\",\"travelMinutes\":" + minutes + "}";
    }

    private static String graphBody(String requestId, int expectedVersion, String... segments) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"dispatcher-test\""
                + ",\"expectedVersion\":" + expectedVersion + ",\"segments\":["
                + String.join(",", segments) + "]}";
    }

    private static String routeBody(String requestId, String scheduleKey, int graphVersion,
                                    String trainNo, String from, String to,
                                    String departIso, String latestIso) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"dispatcher-test\""
                + ",\"scheduleKey\":\"" + scheduleKey + "\",\"graphVersion\":" + graphVersion
                + ",\"trainNo\":\"" + trainNo + "\",\"fromStation\":\"" + from
                + "\",\"toStation\":\"" + to + "\",\"departUtc\":\"" + departIso
                + "\",\"latestArrivalUtc\":\"" + latestIso + "\"}";
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    // ---------- 寻径发布主流程 ----------

    /**
     * 题干合成场景：S→T 直达 5 分钟与经 U 共 8 分钟两路；直达已被占用、
     * 截止允许 8 分钟时，应选择经 U 并一次发布两段。
     */
    @Test
    void publishRouteFallsBackToViaPathWhenDirectOccupied() throws Exception {
        String p = suffix();
        String s = "S-" + p;
        String t = "T-" + p;
        String u = "U-" + p;
        String direct = "SEC-ST-" + p;
        String su = "SEC-SU-" + p;
        String ut = "SEC-UT-" + p;
        int version = replaceGraph(
                seg(direct, s, t, 5), seg(su, s, u, 4), seg(ut, u, t, 4));

        // 直达区段 08:00-08:05 已被另一已发布计划占用
        String blocker = key("SCH");
        createPlan(blocker, occ("G-BLK", direct, iso(8, 0), iso(8, 5)));
        publishPlan(blocker);

        String scheduleKey = key("SCH");
        MvcResult result = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), scheduleKey, version, "G-1", s, t,
                                iso(8, 0), iso(8, 8))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(body.get("graphVersion").asInt()).isEqualTo(version);
        assertThat(body.get("totalMinutes").asInt()).isEqualTo(8);
        assertThat(body.get("arrivalUtc").asText()).isEqualTo(iso(8, 8));
        assertThat(body.get("legs")).hasSize(2);
        assertThat(body.get("legs").get(0).get("sectionId").asText()).isEqualTo(su);
        assertThat(body.get("legs").get(0).get("startUtc").asText()).isEqualTo(iso(8, 0));
        assertThat(body.get("legs").get(0).get("endUtc").asText()).isEqualTo(iso(8, 4));
        assertThat(body.get("legs").get(1).get("sectionId").asText()).isEqualTo(ut);
        assertThat(body.get("legs").get(1).get("startUtc").asText()).isEqualTo(iso(8, 4));
        assertThat(body.get("legs").get(1).get("endUtc").asText()).isEqualTo(iso(8, 8));

        // 一次创建并发布：明细可见，两段占用生效
        JsonNode plan = read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(plan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(plan.get("occupancies")).hasSize(2);
        JsonNode slots = read(mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", su))
                .andExpect(status().isOk()).andReturn());
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).get("scheduleKey").asText()).isEqualTo(scheduleKey);
    }

    @Test
    void publishRoutePicksFastestWhenFree() throws Exception {
        String p = suffix();
        String s = "S-" + p;
        String t = "T-" + p;
        String u = "U-" + p;
        String direct = "SEC-ST-" + p;
        int version = replaceGraph(
                seg(direct, s, t, 5), seg("SEC-SU-" + p, s, u, 4), seg("SEC-UT-" + p, u, t, 4));

        String scheduleKey = key("SCH");
        MvcResult result = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), scheduleKey, version, "G-2", s, t,
                                iso(9, 0), iso(9, 30))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("totalMinutes").asInt()).isEqualTo(5);
        assertThat(body.get("legs")).hasSize(1);
        assertThat(body.get("legs").get(0).get("sectionId").asText()).isEqualTo(direct);
    }

    @Test
    void publishRouteBreaksTieByLexicographicSectionSequence() throws Exception {
        String p = suffix();
        String a = "A-" + p;
        String d = "D-" + p;
        // 两条同耗时路径：A→B→D 与 A→C→D，均 6 分钟；字典序 SEC-AB < SEC-AC
        int version = replaceGraph(
                seg("SEC-AB-" + p, a, "B-" + p, 3), seg("SEC-BD-" + p, "B-" + p, d, 3),
                seg("SEC-AC-" + p, a, "C-" + p, 3), seg("SEC-CD-" + p, "C-" + p, d, 3));

        MvcResult result = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), key("SCH"), version, "G-3", a, d,
                                iso(10, 0), iso(10, 30))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("totalMinutes").asInt()).isEqualTo(6);
        assertThat(body.get("legs").get(0).get("sectionId").asText()).isEqualTo("SEC-AB-" + p);
        assertThat(body.get("legs").get(1).get("sectionId").asText()).isEqualTo("SEC-BD-" + p);
    }

    @Test
    void publishRouteAllowsAdjacentSlots() throws Exception {
        String p = suffix();
        String s = "S-" + p;
        String t = "T-" + p;
        String direct = "SEC-ST-" + p;
        int version = replaceGraph(seg(direct, s, t, 5));

        String blocker = key("SCH");
        createPlan(blocker, occ("G-BLK", direct, iso(11, 0), iso(11, 5)));
        publishPlan(blocker);

        // 08:05 紧接占用结束（左闭右开相邻）合法
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), key("SCH"), version, "G-4", s, t,
                                iso(11, 5), iso(11, 30))))
                .andExpect(status().isCreated());
    }
}

