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
 * 铁路站停时长与站台容量端到端测试（真实 H2 内存库，MODE=MySQL）：
 * 主流程发布固化、站点缺失/到发倒置/停留不足 422、容量满 429、批量发布整次回滚、
 * 规则修订不改写历史、null 到发语义、明细/历史/诊断只读查询与操作者幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StationDwellApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 23);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 站点配置 ----------

    @Test
    void registerStationThenReviseWithVersionArbitration() throws Exception {
        String station = key("ST");

        MvcResult created = mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "dispatcher", 5, 2, null)))
                .andExpect(status().isOk()).andReturn();
        JsonNode first = read(created);
        assertThat(first.get("version").asInt()).isEqualTo(1);
        assertThat(first.get("minDwellMinutes").asInt()).isEqualTo(5);
        assertThat(first.get("platformCapacity").asInt()).isEqualTo(2);

        // 版本不匹配 → 409
        mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "dispatcher", 6, 2, 3)))
                .andExpect(status().isConflict());
        // 已存在但不带 expectedVersion → 409
        mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "dispatcher", 6, 2, null)))
                .andExpect(status().isConflict());

        MvcResult revised = mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "dispatcher", 6, 3, 1)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(revised).get("version").asInt()).isEqualTo(2);
        assertThat(read(revised).get("minDwellMinutes").asInt()).isEqualTo(6);

        MvcResult detail = mvc.perform(get("/api/v1/stations/{id}/config", station))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("version").asInt()).isEqualTo(2);

        mvc.perform(get("/api/v1/stations/{id}/config", key("ST")))
                .andExpect(status().isNotFound());
    }

    @Test
    void stationConfigIdempotentReplayAndParamConflict() throws Exception {
        String station = key("ST");
        String requestKey = key("REQ");
        String body = configBody(requestKey, "dispatcher", 5, 2, null);

        MvcResult first = mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同参 → 409
        mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(requestKey, "dispatcher", 8, 2, null)))
                .andExpect(status().isConflict());
        // 同键不同操作者 → 409
        mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(requestKey, "someone-else", 5, 2, null)))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidStationConfigReturns400() throws Exception {
        String station = key("ST");
        // 容量必须为正
        mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "dispatcher", 0, 0, null)))
                .andExpect(status().isBadRequest());
        // 最小停站不可为负
        mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"operator\":\"d\","
                                + "\"minDwellMinutes\":-1,\"platformCapacity\":2}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 发布主流程与快照固化 ----------

    @Test
    void publishFreezesStationRulesAndTimes() throws Exception {
        String station = key("ST");
        registerStation(station, 5, 2);
        String scheduleKey = key("SCH");
        createPlanWithStops(scheduleKey,
                stop("G1", station, iso(10, 0), iso(10, 5)));
        publishOk(scheduleKey, key("REQ"), "dispatcher");

        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode stop = read(detail).get("stationStops").get(0);
        assertThat(stop.get("stationId").asText()).isEqualTo(station);
        assertThat(stop.get("arrivalUtc").asText()).isEqualTo(iso(10, 0));
        assertThat(stop.get("departureUtc").asText()).isEqualTo(iso(10, 5));

        // 修订规则到 v2（更严），历史快照仍固化 v1 与旧值
        reviseStation(station, 10, 2, 1);
        MvcResult history = mvc.perform(get("/api/v1/stations/{id}/snapshots", station)
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode snapshots = read(history);
        assertThat(snapshots).hasSize(1);
        JsonNode snap = snapshots.get(0);
        assertThat(snap.get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(snap.get("stationVersion").asInt()).isEqualTo(1);
        assertThat(snap.get("minDwellMinutes").asInt()).isEqualTo(5);
        assertThat(snap.get("platformCapacity").asInt()).isEqualTo(2);
        assertThat(snap.get("arrivalUtc").asText()).isEqualTo(iso(10, 0));
        assertThat(snap.get("departureUtc").asText()).isEqualTo(iso(10, 5));
    }

    @Test
    void nullArrivalAndNullDepartureSemanticsSurviveSnapshot() throws Exception {
        String station = key("ST");
        registerStation(station, 5, 2);
        // 始发仅发车、终到仅到达：两条站停（容量 2，同一列车不同站点语义）
        String scheduleKey = key("SCH");
        String body = createBody(key("REQ"), "dispatcher", scheduleKey,
                new String[]{occ("G1", key("SEC"), iso(8), iso(12))},
                stop("G1", station, null, iso(9, 0)),
                stop("G1", station, iso(11, 0), null));
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        publishOk(scheduleKey, key("REQ"), "dispatcher");

        MvcResult history = mvc.perform(get("/api/v1/stations/{id}/snapshots", station)
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode snapshots = read(history);
        assertThat(snapshots).hasSize(2);
        // 历史按到达时刻升序、NULL 最后：终到（11:00 到达，无发车）在前
        assertThat(snapshots.get(0).get("arrivalUtc").asText()).isEqualTo(iso(11, 0));
        assertThat(snapshots.get(0).get("departureUtc").isNull()).isTrue();
        // 始发（无到达，09:00 发车）在后
        assertThat(snapshots.get(1).get("arrivalUtc").isNull()).isTrue();
        assertThat(snapshots.get(1).get("departureUtc").asText()).isEqualTo(iso(9, 0));
    }

    // ---------- 422 失败分支 ----------

    @Test
    void publishRejectsUnknownStationWithStationInDetail() throws Exception {
        String missing = key("ST");
        String scheduleKey = key("SCH");
        createPlanWithStops(scheduleKey, stop("G1", missing, iso(10, 0), iso(10, 5)));

        MvcResult result = publishExpect(scheduleKey, 422);
        JsonNode detail = read(result).get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("STATION_NOT_FOUND");
        assertThat(detail.get("stationId").asText()).isEqualTo(missing);
        assertThat(read(result).get("code").asText()).isEqualTo("SLOT_CONFLICT");
        assertPlanDraftAndNoSnapshot(scheduleKey);
    }

    @Test
    void publishRejectsReversedArrivalDepartureWithActualMinutes() throws Exception {
        String station = key("ST");
        registerStation(station, 0, 5);
        String scheduleKey = key("SCH");
        createPlanWithStops(scheduleKey, stop("G1", station, iso(10, 5), iso(10, 0)));

        JsonNode detail = read(publishExpect(scheduleKey, 422)).get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("ARRIVAL_DEPARTURE_REVERSED");
        assertThat(detail.get("stationId").asText()).isEqualTo(station);
        assertThat(detail.get("actualMinutes").asLong()).isEqualTo(-5);
        assertPlanDraftAndNoSnapshot(scheduleKey);
    }

    @Test
    void publishRejectsDwellTooShortWithActualRequiredAndShortfall() throws Exception {
        String station = key("ST");
        registerStation(station, 5, 2);
        String scheduleKey = key("SCH");
        // 实际停 4 分钟 < 要求 5 分钟
        createPlanWithStops(scheduleKey, stop("G1", station, iso(10, 0), iso(10, 4)));

        String requestKey = key("REQ");
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(requestKey, "dispatcher")))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode detail = read(result).get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("DWELL_TOO_SHORT");
        assertThat(detail.get("stationId").asText()).isEqualTo(station);
        assertThat(detail.get("actualMinutes").asLong()).isEqualTo(4);
        assertThat(detail.get("requiredMinutes").asLong()).isEqualTo(5);
        assertThat(detail.get("shortfallMinutes").asLong()).isEqualTo(1);
        assertPlanDraftAndNoSnapshot(scheduleKey);

        // 失败不占键：修正为 5 分钟后同键成功
        replaceStops(scheduleKey, 1, stop("G1", station, iso(10, 0), iso(10, 5)));
        publishOk(scheduleKey, requestKey, "dispatcher");
    }

    // ---------- 429 容量 ----------

    @Test
    void publishRejectsWhenPlatformFullAndAdjacentAllowed() throws Exception {
        String station = key("ST");
        registerStation(station, 0, 1);
        String first = key("SCH");
        createPlanWithStops(first, stop("G1", station, iso(10, 0), iso(10, 10)));
        publishOk(first, key("REQ"), "dispatcher");

        // 重叠到发 → 429，明细含实际并发数、容量与差额
        String second = key("SCH");
        createPlanWithStops(second, stop("G2", station, iso(10, 5), iso(10, 15)));
        MvcResult blocked = mvc.perform(post("/api/v1/plans/{key}/publish", second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), "dispatcher")))
                .andExpect(status().isTooManyRequests()).andReturn();
        JsonNode error = read(blocked);
        assertThat(error.get("code").asText()).isEqualTo("PLATFORM_CAPACITY_EXCEEDED");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("stationId").asText()).isEqualTo(station);
        assertThat(detail.get("actualConcurrent").asInt()).isEqualTo(2);
        assertThat(detail.get("requiredCapacity").asInt()).isEqualTo(1);
        assertThat(detail.get("overflow").asInt()).isEqualTo(1);
        assertThat(detail.get("timeUtc").asText()).isEqualTo(iso(10, 5));
        assertPlanDraftAndNoSnapshot(second);

        // 左闭右开相邻：10:10 到达合法
        String adjacent = key("SCH");
        createPlanWithStops(adjacent, stop("G3", station, iso(10, 10), iso(10, 20)));
        publishOk(adjacent, key("REQ"), "dispatcher");
    }

    @Test
    void cancelReleasesPlatformOccupancy() throws Exception {
        String station = key("ST");
        registerStation(station, 0, 1);
        String first = key("SCH");
        createPlanWithStops(first, stop("G1", station, iso(10, 0), iso(10, 10)));
        publishOk(first, key("REQ"), "dispatcher");

        String second = key("SCH");
        createPlanWithStops(second, stop("G2", station, iso(10, 0), iso(10, 10)));
        mvc.perform(post("/api/v1/plans/{key}/publish", second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), "dispatcher")))
                .andExpect(status().isTooManyRequests());

        cancelOk(first, key("REQ"), "dispatcher");
        // 站台占用随取消释放，second 可发布，历史快照仍保留 first
        publishOk(second, key("REQ"), "dispatcher");
        MvcResult diag = mvc.perform(get("/api/v1/stations/{id}/diagnostics", station)
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(diag);
        assertThat(body.get("publishedCount").asInt()).isEqualTo(1);
        assertThat(body.get("peakConcurrent").asInt()).isEqualTo(1);
        assertThat(body.get("remainingSlots").asInt()).isZero();

        MvcResult history = mvc.perform(get("/api/v1/stations/{id}/snapshots", station)
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(history)).hasSize(2);
    }

    // ---------- 批量发布整次回滚 ----------

    @Test
    void batchPublishRollsBackWhenAnyStationFails() throws Exception {
        String goodStation = key("ST");
        String badStation = key("ST");
        registerStation(goodStation, 2, 2);
        registerStation(badStation, 10, 2);
        String scheduleKey = key("SCH");
        // 同一计划两条站停：good 合法，bad 只停 3 分钟不足 10
        createPlanWithStops(scheduleKey,
                stop("G1", goodStation, iso(9, 0), iso(9, 5)),
                stop("G1", badStation, iso(11, 0), iso(11, 3)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), "dispatcher")))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode details = read(result).get("details");
        assertThat(details).hasSize(1);
        assertThat(details.get(0).get("type").asText()).isEqualTo("DWELL_TOO_SHORT");
        assertThat(details.get(0).get("stationId").asText()).isEqualTo(badStation);

        // 整次回滚：计划仍草稿，两站均无快照，无半成品
        assertPlanDraftAndNoSnapshot(scheduleKey);
        Integer goodSnapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_published_station_stop s"
                        + " JOIN rail_station_config c ON c.station_id = s.station_id"
                        + " WHERE c.station_id = ?", Integer.class, goodStation);
        assertThat(goodSnapshots).isZero();
    }

    // ---------- 诊断 ----------

    @Test
    void diagnosticsReportsActualPeakRequiredAndRemaining() throws Exception {
        String station = key("ST");
        registerStation(station, 0, 2);
        String planA = key("SCH");
        createPlanWithStops(planA, stop("G1", station, iso(8, 0), iso(9, 0)));
        publishOk(planA, key("REQ"), "dispatcher");
        String planB = key("SCH");
        createPlanWithStops(planB, stop("G2", station, iso(8, 30), iso(9, 30)));
        publishOk(planB, key("REQ"), "dispatcher");

        MvcResult diag = mvc.perform(get("/api/v1/stations/{id}/diagnostics", station)
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(diag);
        assertThat(body.get("publishedCount").asInt()).isEqualTo(2);
        assertThat(body.get("peakConcurrent").asInt()).isEqualTo(2);
        assertThat(body.get("platformCapacity").asInt()).isEqualTo(2);
        assertThat(body.get("remainingSlots").asInt()).isZero();
        assertThat(body.get("snapshots")).hasSize(2);

        // 只读：重复查询结果一致
        MvcResult diag2 = mvc.perform(get("/api/v1/stations/{id}/diagnostics", station)
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(diag2)).isEqualTo(body);
    }

    // ---------- 幂等操作者边界 ----------

    @Test
    void rescheduleValidatesAndFreezesNewPlanStationStops() throws Exception {
        String station = key("ST");
        registerStation(station, 5, 1);
        String oldKey = key("SCH");
        createPlanWithStops(oldKey, stop("G1", station, iso(8, 0), iso(8, 10)));
        publishOk(oldKey, key("REQ"), "dispatcher");

        // 新草稿与旧计划同时刻占用站台，改签校验必须排除旧计划自身快照
        String newKey = key("SCH");
        String newBody = createBody(key("REQ"), "dispatcher", newKey,
                new String[]{occ("G2", key("SEC"), iso(8), iso(12))},
                stop("G2", station, iso(8, 0), iso(8, 10)));
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(newBody))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), "dispatcher", newKey, 1, 1)))
                .andExpect(status().isOk());

        // 新计划发布并固化快照；旧计划取消但历史快照保留，站点历史共两条
        MvcResult history = mvc.perform(get("/api/v1/stations/{id}/snapshots", station)
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode snapshots = read(history);
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots).extracting(s -> s.get("scheduleKey").asText())
                .containsExactlyInAnyOrder(oldKey, newKey);

        // 站台容量诊断只统计仍生效的新计划
        MvcResult diag = mvc.perform(get("/api/v1/stations/{id}/diagnostics", station)
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(diag);
        assertThat(body.get("publishedCount").asInt()).isEqualTo(1);
        assertThat(body.get("peakConcurrent").asInt()).isEqualTo(1);
        assertThat(body.get("snapshots").get(0).get("scheduleKey").asText()).isEqualTo(newKey);
    }

    @Test
    void rescheduleRejectsCapacityOverflowAgainstThirdPartyAndRollsBack() throws Exception {
        String station = key("ST");
        registerStation(station, 0, 1);
        // 第三方已占据 10:00-11:00 站台
        String thirdParty = key("SCH");
        createPlanWithStops(thirdParty, stop("G9", station, iso(10, 0), iso(11, 0)));
        publishOk(thirdParty, key("REQ"), "dispatcher");

        String oldKey = key("SCH");
        createPlanWithStops(oldKey, stop("G1", station, iso(7, 0), iso(7, 10)));
        publishOk(oldKey, key("REQ"), "dispatcher");
        // 新草稿与第三方（非旧计划）站台冲突
        String newKey = key("SCH");
        String newBody = createBody(key("REQ"), "dispatcher", newKey,
                new String[]{occ("G2", key("SEC"), iso(8), iso(12))},
                stop("G2", station, iso(10, 30), iso(11, 30)));
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(newBody))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), "dispatcher", newKey, 1, 1)))
                .andExpect(status().isTooManyRequests());

        // 整体回滚：旧仍发布、新仍草稿、无新快照
        MvcResult oldDetail = mvc.perform(get("/api/v1/plans/{key}", oldKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(oldDetail).get("status").asText()).isEqualTo("PUBLISHED");
        MvcResult newDetail = mvc.perform(get("/api/v1/plans/{key}", newKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(newDetail).get("status").asText()).isEqualTo("DRAFT");
        assertPlanDraftAndNoSnapshot(newKey);
    }

    // ---------- 幂等操作者边界 ----------

    @Test
    void publishIdempotentReplayMatchesOperator() throws Exception {
        String station = key("ST");
        registerStation(station, 5, 2);
        String scheduleKey = key("SCH");
        createPlanWithStops(scheduleKey, stop("G1", station, iso(10, 0), iso(10, 5)));
        String requestKey = key("REQ");
        publishOk(scheduleKey, requestKey, "alice");

        // 同操作者同键重放返回首次快照
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(requestKey, "alice")))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay).get("status").asText()).isEqualTo("PUBLISHED");

        // 不同操作者同键 → 409
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(requestKey, "bob")))
                .andExpect(status().isConflict());
    }

    // ---------- 辅助 ----------

    private void assertPlanDraftAndNoSnapshot(String scheduleKey) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("status").asText()).isEqualTo("DRAFT");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_published_station_stop s"
                        + " JOIN rail_day_plan p ON p.id = s.plan_id"
                        + " WHERE p.schedule_key = ?", Integer.class, scheduleKey);
        assertThat(count).isZero();
    }

    private void registerStation(String station, int minDwell, int capacity) throws Exception {
        mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "dispatcher", minDwell, capacity, null)))
                .andExpect(status().isOk());
    }

    private void reviseStation(String station, int minDwell, int capacity, int expectedVersion)
            throws Exception {
        mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "dispatcher", minDwell, capacity,
                                expectedVersion)))
                .andExpect(status().isOk());
    }

    private void createPlanWithStops(String scheduleKey, String... stops) throws Exception {
        String body = createBody(key("REQ"), "dispatcher", scheduleKey,
                new String[]{occ("G1", key("SEC"), iso(8), iso(12))}, stops);
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void replaceStops(String scheduleKey, int expectedVersion, String... stops)
            throws Exception {
        String body = "{\"requestKey\":\"" + key("REQ") + "\",\"operator\":\"dispatcher\","
                + "\"expectedVersion\":" + expectedVersion
                + ",\"occupancies\":[" + occ("G1", key("SEC"), iso(8), iso(12))
                + "],\"stationStops\":[" + String.join(",", stops) + "]}";
        mvc.perform(put("/api/v1/plans/{key}/occupancies", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private MvcResult publishExpect(String scheduleKey, int expectedStatus) throws Exception {
        return mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), "dispatcher")))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private void publishOk(String scheduleKey, String requestKey, String operator) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(requestKey, operator)))
                .andExpect(status().isOk());
    }

    private void cancelOk(String scheduleKey, String requestKey, String operator) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(requestKey, operator)))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour) {
        return iso(hour, 0);
    }

    private static String iso(int hour, int minute) {
        Instant instant = DAY.atTime(hour, minute).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String stop(String train, String station, String arrivalIso, String departureIso) {
        return "{\"trainNo\":\"" + train + "\",\"stationId\":\"" + station
                + "\",\"arrivalUtc\":" + (arrivalIso == null ? "null" : "\"" + arrivalIso + "\"")
                + ",\"departureUtc\":" + (departureIso == null ? "null" : "\"" + departureIso + "\"")
                + "}";
    }

    private static String createBody(String requestKey, String operator, String scheduleKey,
                                     String[] occupancies, String... stops) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator
                + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":[" + String.join(",", occupancies)
                + "],\"stationStops\":[" + String.join(",", stops) + "]}";
    }

    private static String configBody(String requestKey, String operator, int minDwell,
                                     int capacity, Integer expectedVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator
                + "\",\"minDwellMinutes\":" + minDwell
                + ",\"platformCapacity\":" + capacity
                + (expectedVersion == null ? "" : ",\"expectedVersion\":" + expectedVersion) + "}";
    }

    private static String actionBody(String requestKey, String operator) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator + "\"}";
    }

    private static String rescheduleBody(String requestKey, String operator,
                                         String newScheduleKey, int expectedOldVersion,
                                         int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator
                + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }
}
