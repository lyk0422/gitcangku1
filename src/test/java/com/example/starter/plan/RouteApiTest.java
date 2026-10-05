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
 * 线路图替换与寻径发布的端到端测试（H2 内存库）：图版本管理、最优可行路径选择、
 * 404/409/422 分支、幂等边界、换图冻结与取消释放。
 * 各用例先读取当前图版本再整体替换，区段 ID 唯一，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RouteApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 图替换 ----------

    @Test
    void replaceGraphIncrementsVersionAndRejectsStale() throws Exception {
        int current = currentGraphVersion();
        String segId = key("SG");
        MvcResult replaced = mvc.perform(put("/api/v1/graph")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), current, seg(segId, "S", "T", 5))))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(replaced);
        assertThat(body.get("version").asInt()).isEqualTo(current + 1);
        assertThat(body.get("stations")).hasSize(2);
        assertThat(body.get("segments")).hasSize(1);
        assertThat(body.get("segments").get(0).get("segmentId").asText()).isEqualTo(segId);

        // 过期 expectedVersion → 409
        MvcResult stale = mvc.perform(put("/api/v1/graph")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), current, seg(key("SG"), "S", "T", 6))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(stale).get("code").asText()).isEqualTo("GRAPH_VERSION_CONFLICT");

        // 匹配当前版本可继续替换，版本再加一
        MvcResult next = mvc.perform(put("/api/v1/graph")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), current + 1, seg(key("SG"), "S", "T", 7))))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(next).get("version").asInt()).isEqualTo(current + 2);
    }

    @Test
    void replaceGraphIdempotentReplayAndParamConflict() throws Exception {
        int current = currentGraphVersion();
        String requestId = key("REQ");
        String body = graphBody(requestId, current, seg(key("SG"), "S", "T", 5));

        MvcResult first = mvc.perform(put("/api/v1/graph")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(put("/api/v1/graph")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同参 → 409，图版本不被再次推进
        mvc.perform(put("/api/v1/graph").contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(requestId, current, seg(key("SG"), "S", "T", 9))))
                .andExpect(status().isConflict());
        assertThat(currentGraphVersion()).isEqualTo(current + 1);
    }

    @Test
    void replaceGraphValidatesSegmentLimits() throws Exception {
        int current = currentGraphVersion();
        // 21 段超过上限
        mvc.perform(put("/api/v1/graph").contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), current, manySegs(21))))
                .andExpect(status().isBadRequest());
        // 空区段清单
        mvc.perform(put("/api/v1/graph").contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), current)))
                .andExpect(status().isBadRequest());
        // 行驶分钟非正整数
        mvc.perform(put("/api/v1/graph").contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), current, seg(key("SG"), "S", "T", 0))))
                .andExpect(status().isBadRequest());
        // 区段编号重复
        String dup = key("SG");
        mvc.perform(put("/api/v1/graph").contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), current,
                                seg(dup, "S", "T", 5), seg(dup, "S", "T", 6))))
                .andExpect(status().isBadRequest());
        // 区段起终站相同
        mvc.perform(put("/api/v1/graph").contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), current, seg(key("SG"), "S", "S", 5))))
                .andExpect(status().isBadRequest());
        // 12 个站点超过 10 站上限
        String[] segs = new String[6];
        for (int i = 0; i < segs.length; i++) {
            segs[i] = seg(key("SG"), "PA" + i, "PB" + i, 1);
        }
        mvc.perform(put("/api/v1/graph").contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), current, segs)))
                .andExpect(status().isBadRequest());
        // 全部失败，图版本不变
        assertThat(currentGraphVersion()).isEqualTo(current);
    }

    // ---------- 寻径发布 ----------

    /**
     * 题干合成场景：S→T 直达 5 分钟与经 U 共 8 分钟两路；直达 08:00-08:05 已被占用、
     * 截止允许 8 分钟时选择经 U，并一次发布两段占用。
     */
    @Test
    void routePublishChoosesViaWhenDirectOccupied() throws Exception {
        String segST = key("ST");
        String segSU = key("SU");
        String segUT = key("UT");
        int version = replaceGraph(seg(segST, "S", "T", 5),
                seg(segSU, "S", "U", 3), seg(segUT, "U", "T", 5));
        String blocker = key("SCH");
        createPlan(blocker, occ("G1", segST, iso(8), isoMin(8, 5)));
        publishPlan(blocker, key("REQ"));

        MvcResult result = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), version, "G100", "S", "T",
                                iso(8), isoMin(8, 8))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(body.get("graphVersion").asInt()).isEqualTo(version);
        assertThat(body.get("totalMinutes").asInt()).isEqualTo(8);
        assertThat(body.get("departUtc").asText()).isEqualTo(iso(8));
        assertThat(body.get("arriveUtc").asText()).isEqualTo(isoMin(8, 8));
        JsonNode segments = body.get("segments");
        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).get("segmentId").asText()).isEqualTo(segSU);
        assertThat(segments.get(0).get("startUtc").asText()).isEqualTo(iso(8));
        assertThat(segments.get(0).get("endUtc").asText()).isEqualTo(isoMin(8, 3));
        assertThat(segments.get(1).get("segmentId").asText()).isEqualTo(segUT);
        assertThat(segments.get(1).get("startUtc").asText()).isEqualTo(isoMin(8, 3));
        assertThat(segments.get(1).get("endUtc").asText()).isEqualTo(isoMin(8, 8));

        // 一次创建的 PUBLISHED 日计划：明细含两段占用
        String scheduleKey = body.get("scheduleKey").asText();
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode plan = read(detail);
        assertThat(plan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(plan.get("occupancies")).hasSize(2);
        assertThat(plan.get("occupancies").get(0).get("sectionId").asText()).isEqualTo(segSU);
        assertThat(plan.get("occupancies").get(1).get("sectionId").asText()).isEqualTo(segUT);

        // 已发布时隙可查询
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", segSU))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).hasSize(1);
    }

    @Test
    void routePublishPicksFastestDirect() throws Exception {
        String segST = key("ST");
        String segSU = key("SU");
        String segUT = key("UT");
        int version = replaceGraph(seg(segST, "S", "T", 5),
                seg(segSU, "S", "U", 3), seg(segUT, "U", "T", 5));

        MvcResult result = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), version, "G110", "S", "T",
                                iso(8), isoMin(8, 10))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("totalMinutes").asInt()).isEqualTo(5);
        assertThat(body.get("segments")).hasSize(1);
        assertThat(body.get("segments").get(0).get("segmentId").asText()).isEqualTo(segST);
    }

    @Test
    void routePublishTieBreaksBySegmentIdSequence() throws Exception {
        String segZ = "Z" + UUID.randomUUID();
        String segA = "A" + UUID.randomUUID();
        String segB = "B" + UUID.randomUUID();
        // 直达 [Z..] 与经 V [A.., B..] 同为 5 分钟，字典序 [A,B] < [Z]，应选经 V
        int version = replaceGraph(seg(segZ, "S", "T", 5),
                seg(segA, "S", "V", 2), seg(segB, "V", "T", 3));

        MvcResult result = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), version, "G120", "S", "T",
                                iso(8), isoMin(8, 10))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("totalMinutes").asInt()).isEqualTo(5);
        assertThat(body.get("segments")).hasSize(2);
        assertThat(body.get("segments").get(0).get("segmentId").asText()).isEqualTo(segA);
        assertThat(body.get("segments").get(1).get("segmentId").asText()).isEqualTo(segB);
    }

    @Test
    void unknownStationReturns404() throws Exception {
        int version = replaceGraph(seg(key("SG"), "S", "T", 5));
        MvcResult fromMissing = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), version, "G130", "NOPE", "T",
                                iso(8), isoMin(8, 10))))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(read(fromMissing).get("code").asText()).isEqualTo("STATION_NOT_FOUND");
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), version, "G130", "S", "NOPE",
                                iso(8), isoMin(8, 10))))
                .andExpect(status().isNotFound());
    }

    @Test
    void staleGraphVersionReturns409() throws Exception {
        int version = replaceGraph(seg(key("SG"), "S", "T", 5));
        MvcResult result = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), version + 1, "G140", "S", "T",
                                iso(8), isoMin(8, 10))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("GRAPH_VERSION_CONFLICT");
    }

    @Test
    void noFeasiblePathReturns422AndCreatesNothing() throws Exception {
        String segST = key("ST");
        int version = replaceGraph(seg(segST, "S", "T", 5));
        String blocker = key("SCH");
        createPlan(blocker, occ("G1", segST, iso(8), isoMin(8, 5)));
        publishPlan(blocker, key("REQ"));

        String requestId = key("REQ");
        String train = "G" + UUID.randomUUID();
        String body = routeBody(requestId, version, train, "S", "T", iso(8), isoMin(8, 10));
        MvcResult result = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("ROUTE_INFEASIBLE");

        // 不建计划、不建占用、不留快照
        Integer details = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_route_plan_detail WHERE train_no = ?",
                Integer.class, train);
        assertThat(details).isZero();
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", segST))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).hasSize(1); // 仅有 blocker 的占用

        // 失败不占键：取消占用后同键同参重发成功
        cancelPlan(blocker, key("REQ"));
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void deadlineTooTightReturns422() throws Exception {
        String segST = key("ST");
        int version = replaceGraph(seg(segST, "S", "T", 5));
        // 最晚到达 08:04 早于最快路径 5 分钟
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), version, "G150", "S", "T",
                                iso(8), isoMin(8, 4))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void routeCrossingOperationDayReturns422() throws Exception {
        String segST = key("ST");
        int version = replaceGraph(seg(key("SG"), "X", "Y", 3),
                seg(segST, "S", "T", 5));
        // 23:58 出发 5 分钟到达次日 00:03，跨 Asia/Shanghai 运营日，即使截止放宽也 422
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), version, "G160", "S", "T",
                                isoMin(23, 58), isoMin(1, 0, 1))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void departValidationReturns400() throws Exception {
        int version = replaceGraph(seg(key("SG"), "S", "T", 5));
        // 出发非 UTC 整分钟
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), version, "G170", "S", "T",
                                iso(8).replace(":00Z", ":30Z"), isoMin(8, 10))))
                .andExpect(status().isBadRequest());
        // 最晚到达不晚于出发
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), version, "G170", "S", "T",
                                iso(8), iso(8))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void routePublishIdempotentReplayAndParamConflict() throws Exception {
        String segST = key("ST");
        int version = replaceGraph(seg(segST, "S", "T", 5));
        String requestId = key("REQ");
        String body = routeBody(requestId, version, "G180", "S", "T", iso(8), isoMin(8, 10));

        MvcResult first = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同参 → 409
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(requestId, version, "G181", "S", "T",
                                iso(8), isoMin(8, 10))))
                .andExpect(status().isConflict());
    }

    @Test
    void graphReplaceDoesNotChangePublishedRoutePlan() throws Exception {
        String segST = key("ST");
        int version = replaceGraph(seg(segST, "S", "T", 5));
        MvcResult published = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), version, "G190", "S", "T",
                                iso(8), isoMin(8, 10))))
                .andExpect(status().isCreated()).andReturn();
        String scheduleKey = read(published).get("scheduleKey").asText();

        // 换图后旧计划占用与快照不变
        int newVersion = replaceGraph(seg(key("SG"), "A", "B", 9));
        assertThat(newVersion).isEqualTo(version + 1);
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode plan = read(detail);
        assertThat(plan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(plan.get("occupancies")).hasSize(1);
        assertThat(plan.get("occupancies").get(0).get("sectionId").asText()).isEqualTo(segST);
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", segST))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).hasSize(1);
    }

    @Test
    void cancelRoutePlanReleasesSlots() throws Exception {
        String segST = key("ST");
        int version = replaceGraph(seg(segST, "S", "T", 5));
        MvcResult published = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), version, "G200", "S", "T",
                                iso(8), isoMin(8, 10))))
                .andExpect(status().isCreated()).andReturn();
        String scheduleKey = read(published).get("scheduleKey").asText();

        // 取消走原流程，时隙立即释放
        cancelPlan(scheduleKey, key("REQ"));
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("status").asText()).isEqualTo("CANCELLED");

        // 同时段同路径可再次寻径发布（不自动重寻径，需显式发起）
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), version, "G201", "S", "T",
                                iso(8), isoMin(8, 10))))
                .andExpect(status().isCreated());
    }

    // ---------- 辅助 ----------

    private int replaceGraph(String... segments) throws Exception {
        int current = currentGraphVersion();
        MvcResult result = mvc.perform(put("/api/v1/graph")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), current, segments)))
                .andExpect(status().isOk()).andReturn();
        return read(result).get("version").asInt();
    }

    private int currentGraphVersion() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/graph"))
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

    private void publishPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + requestKey + "\"}"))
                .andExpect(status().isOk());
    }

    private void cancelPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + requestKey + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 运营日当日 hour 点（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour) {
        return isoMin(hour, 0, 0);
    }

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String isoMin(int hour, int minute) {
        return isoMin(hour, minute, 0);
    }

    /** 运营日加 dayOffset 天后 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String isoMin(int hour, int minute, int dayOffset) {
        Instant instant = DAY.plusDays(dayOffset).atTime(hour, minute).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String seg(String id, String from, String to, int minutes) {
        return "{\"segmentId\":\"" + id + "\",\"fromStation\":\"" + from
                + "\",\"toStation\":\"" + to + "\",\"minutes\":" + minutes + "}";
    }

    private static String[] manySegs(int count) {
        String[] segs = new String[count];
        for (int i = 0; i < count; i++) {
            segs[i] = seg("M" + i + "-" + UUID.randomUUID(), "MA", "MB", 1);
        }
        return segs;
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String graphBody(String requestId, int expectedVersion, String... segments) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"dispatcher-1\""
                + ",\"expectedVersion\":" + expectedVersion
                + ",\"segments\":[" + String.join(",", segments) + "]}";
    }

    private static String routeBody(String requestId, int graphVersion, String train,
                                    String from, String to, String departIso, String latestIso) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"dispatcher-1\""
                + ",\"graphVersion\":" + graphVersion + ",\"trainNo\":\"" + train
                + "\",\"fromStation\":\"" + from + "\",\"toStation\":\"" + to
                + "\",\"departUtc\":\"" + departIso + "\",\"latestArrivalUtc\":\"" + latestIso
                + "\"}";
    }
}
