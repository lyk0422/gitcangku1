package com.example.starter.station;

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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 铁路站停能力端到端测试（H2 内存库）：站台配置与不可变版本、发布期到发/停留/容量校验、
 * 发布快照固化与规则修订不改写历史、失败整批回滚、取消释放容量、改签联动、
 * 幂等（操作者指纹、同参重放、异参 409、失败不占键）与诊断查询稳定排序。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StationDwellApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 27);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 站台配置主流程 ----------

    @Test
    void createAndReviseStationConfigKeepsImmutableHistory() throws Exception {
        String station = key("ST");

        MvcResult created = mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), 5, 2, "alice")))
                .andExpect(status().isOk()).andReturn();
        JsonNode first = read(created);
        assertThat(first.get("version").asInt()).isEqualTo(1);
        assertThat(first.get("minDwellMinutes").asInt()).isEqualTo(5);
        assertThat(first.get("platformCapacity").asInt()).isEqualTo(2);
        assertThat(first.get("operatorId").asText()).isEqualTo("alice");

        // 修订：版本加一
        MvcResult revised = mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), 8, 3, "bob")))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(revised).get("version").asInt()).isEqualTo(2);

        MvcResult current = mvc.perform(get("/api/v1/stations/{id}/config", station))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(current).get("version").asInt()).isEqualTo(2);
        assertThat(read(current).get("platformCapacity").asInt()).isEqualTo(3);

        // 不可变历史按版本升序
        MvcResult history = mvc.perform(get("/api/v1/stations/{id}/history", station))
                .andExpect(status().isOk()).andReturn();
        JsonNode versions = read(history).get("versions");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("version").asInt()).isEqualTo(1);
        assertThat(versions.get(0).get("minDwellMinutes").asInt()).isEqualTo(5);
        assertThat(versions.get(0).get("operatorId").asText()).isEqualTo("alice");
        assertThat(versions.get(1).get("version").asInt()).isEqualTo(2);
        assertThat(versions.get(1).get("platformCapacity").asInt()).isEqualTo(3);
        assertThat(versions.get(1).get("operatorId").asText()).isEqualTo("bob");
    }

    @Test
    void stationConfigMissingReturns404AndInvalidParams400() throws Exception {
        mvc.perform(get("/api/v1/stations/{id}/config", key("ST")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/stations/{id}/history", key("ST")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/stations/{id}/diagnostics", key("ST"))
                        .param("date", DAY.toString()))
                .andExpect(status().isNotFound());

        // 容量 0 非法
        mvc.perform(put("/api/v1/stations/{id}/config", key("ST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), 0, 0, null)))
                .andExpect(status().isBadRequest());
        // 停站下限 -1 非法
        mvc.perform(put("/api/v1/stations/{id}/config", key("ST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), -1, 1, null)))
                .andExpect(status().isBadRequest());
        // 缺 requestKey 非法
        mvc.perform(put("/api/v1/stations/{id}/config", key("ST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minDwellMinutes\":1,\"platformCapacity\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void stationConfigIdempotentReplayDifferentParamsAndOperatorConflict() throws Exception {
        String station = key("ST");
        String requestKey = key("REQ");
        String body = configBody(requestKey, 5, 2, null);
        MvcResult first = mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        // 空白操作者归一 anonymous 并固化
        assertThat(read(first).get("operatorId").asText()).isEqualTo("anonymous");

        // 同键同参重放返回首次完整响应
        MvcResult replay = mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同规则 → 409
        mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(requestKey, 6, 2, null)))
                .andExpect(status().isConflict());
        // 同键不同操作者 → 409（操作者参与指纹）
        mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(requestKey, 5, 2, "carol")))
                .andExpect(status().isConflict());
    }

    @Test
    void stationConfigFailureDoesNotConsumeRequestKey() throws Exception {
        String station = key("ST");
        String requestKey = key("REQ");
        // 首次非法（容量 0）→ 400，不占键
        mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(requestKey, 1, 0, null)))
                .andExpect(status().isBadRequest());
        // 同键合法请求成功（版本 1）
        MvcResult ok = mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(requestKey, 1, 1, null)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(ok).get("version").asInt()).isEqualTo(1);
    }

    // ---------- 发布主流程与快照 ----------

    @Test
    void publishFreezesStopSnapshotAndRuleRevisionDoesNotRewriteHistory() throws Exception {
        String station = key("ST");
        configure(station, 5, 2, "alice");

        String scheduleKey = key("SCH");
        createPlanWithStops(scheduleKey,
                List.of(new OccDef("G1", key("SEC"), 8, 0, 10, 0)),
                List.of(new StopDef("G1", station, 8, 10, 8, 20)));
        publishPlan(scheduleKey, key("REQ"), null);

        // 明细含登记站停，UTC 时刻原样
        JsonNode detail = read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        JsonNode stop = detail.get("stationStops").get(0);
        assertThat(stop.get("stationId").asText()).isEqualTo(station);
        assertThat(stop.get("arrivalUtc").asText()).isEqualTo(iso(8, 10));
        assertThat(stop.get("departureUtc").asText()).isEqualTo(iso(8, 20));

        // 发布快照固化版本 1 与当时规则
        JsonNode snapshots = read(mvc.perform(get("/api/v1/plans/{key}/published-stops", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).get("stationVersion").asInt()).isEqualTo(1);
        assertThat(snapshots.get(0).get("minDwellMinutes").asInt()).isEqualTo(5);
        assertThat(snapshots.get(0).get("platformCapacity").asInt()).isEqualTo(2);
        assertThat(snapshots.get(0).get("publishedAt").asLong()).isPositive();

        // 规则修订为版本 2（下限 15、容量 1）
        configure(station, 15, 1, "bob");

        // 历史快照不被改写
        JsonNode snapshotsAfter = read(mvc.perform(get("/api/v1/plans/{key}/published-stops", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(snapshotsAfter.get(0).get("stationVersion").asInt()).isEqualTo(1);
        assertThat(snapshotsAfter.get(0).get("minDwellMinutes").asInt()).isEqualTo(5);
        assertThat(snapshotsAfter.get(0).get("platformCapacity").asInt()).isEqualTo(2);

        // 诊断反映当前规则与实际占用
        JsonNode diag = read(mvc.perform(get("/api/v1/stations/{id}/diagnostics", station)
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(diag.get("stationVersion").asInt()).isEqualTo(2);
        assertThat(diag.get("platformCapacity").asInt()).isEqualTo(1);
        assertThat(diag.get("peakConcurrent").asInt()).isEqualTo(1);
        assertThat(diag.get("remaining").asInt()).isEqualTo(0);
        assertThat(diag.get("stops")).hasSize(1);
        assertThat(diag.get("stops").get(0).get("scheduleKey").asText()).isEqualTo(scheduleKey);
    }

    @Test
    void singleEndpointStopsKeepNullSemanticsAndOccupyPlatformToDayEnd() throws Exception {
        String station = key("ST");
        configure(station, 0, 1, null);

        // 始发：arrival 为 null；终到：departure 为 null
        String scheduleKey = key("SCH");
        createPlanWithStops(scheduleKey,
                List.of(new OccDef("G1", key("SEC"), 8, 0, 12, 0)),
                List.of(
                        new StopDef("G1", station, null, null, 8, 30),
                        new StopDef("G1", station, 11, 0, null, null)));
        publishPlan(scheduleKey, key("REQ"), null);

        JsonNode snapshots = read(mvc.perform(get("/api/v1/plans/{key}/published-stops", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0).get("arrivalUtc").isNull()).isTrue();
        assertThat(snapshots.get(0).get("departureUtc").asText()).isEqualTo(iso(8, 30));
        assertThat(snapshots.get(1).get("arrivalUtc").asText()).isEqualTo(iso(11, 0));
        assertThat(snapshots.get(1).get("departureUtc").isNull()).isTrue();

        // 容量 1：终到区间延伸至日末，另一列车下午停靠仍超卖
        String second = key("SCH");
        createPlanWithStops(second,
                List.of(new OccDef("G2", key("SEC"), 13, 0, 15, 0)),
                List.of(new StopDef("G2", station, 14, 0, 14, 20)));
        mvc.perform(post("/api/v1/plans/{key}/publish", second)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"), null)))
                .andExpect(status().isTooManyRequests());
    }

    // ---------- 发布失败分支 422 ----------

    @Test
    void publishRejectsInvertedArrivalDepartureWithActualMinutes() throws Exception {
        String station = key("ST");
        configure(station, 5, 5, null);
        String scheduleKey = key("SCH");
        createPlanWithStops(scheduleKey,
                List.of(new OccDef("G1", key("SEC"), 8, 0, 12, 0)),
                List.of(new StopDef("G1", station, 9, 0, 8, 0)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"), null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("STATION_DWELL_VIOLATION");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("ARRIVAL_DEPARTURE_INVERTED");
        assertThat(detail.get("stationId").asText()).isEqualTo(station);
        assertThat(detail.get("trainNo").asText()).isEqualTo("G1");
        assertThat(detail.get("actualMinutes").asLong()).isEqualTo(-60);
        assertThat(detail.get("requiredMinutes").asLong()).isEqualTo(5);

        // 计划保持草稿且无快照
        assertThat(planStatus(scheduleKey)).isEqualTo("DRAFT");
        assertThat(publishedStopCount(scheduleKey)).isZero();
    }

    @Test
    void publishRejectsUnknownStation() throws Exception {
        String station = key("ST");
        String scheduleKey = key("SCH");
        createPlanWithStops(scheduleKey,
                List.of(new OccDef("G1", key("SEC"), 8, 0, 12, 0)),
                List.of(new StopDef("G1", station, 9, 0, 9, 20)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"), null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode detail = read(result).get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("STATION_NOT_FOUND");
        assertThat(detail.get("stationId").asText()).isEqualTo(station);
        assertThat(planStatus(scheduleKey)).isEqualTo("DRAFT");
    }

    @Test
    void publishRejectsDwellTooShortWithRequiredAndShortfall() throws Exception {
        String station = key("ST");
        configure(station, 10, 5, null);
        String scheduleKey = key("SCH");
        createPlanWithStops(scheduleKey,
                List.of(new OccDef("G1", key("SEC"), 8, 0, 12, 0)),
                List.of(new StopDef("G1", station, 9, 0, 9, 5)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"), null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode detail = read(result).get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("DWELL_TOO_SHORT");
        assertThat(detail.get("actualMinutes").asLong()).isEqualTo(5);
        assertThat(detail.get("requiredMinutes").asLong()).isEqualTo(10);
        assertThat(detail.get("shortfallMinutes").asLong()).isEqualTo(5);
        assertThat(planStatus(scheduleKey)).isEqualTo("DRAFT");
    }

    @Test
    void draftAllowsInvertedStopAndPublishFailsThenFixAndRepublish() throws Exception {
        String station = key("ST");
        configure(station, 5, 5, null);
        String scheduleKey = key("SCH");
        String section = key("SEC");
        // 草稿允许登记到发倒置，创建本身成功
        createPlanWithStops(scheduleKey,
                List.of(new OccDef("G1", section, 8, 0, 12, 0)),
                List.of(new StopDef("G1", station, 9, 0, 8, 0)));
        String publishKey = key("REQ");
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey, null)))
                .andExpect(status().isUnprocessableEntity());

        // 失败不占键：修正草稿（版本升至 2）后同键发布成功
        replaceWithStops(scheduleKey, 1,
                List.of(new OccDef("G1", section, 8, 0, 12, 0)),
                List.of(new StopDef("G1", station, 9, 0, 9, 10)));
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey, null)))
                .andExpect(status().isOk());
        assertThat(planStatus(scheduleKey)).isEqualTo("PUBLISHED");
    }

    @Test
    void rejectMalformedStopDraft() throws Exception {
        String station = key("ST");
        configure(station, 5, 5, null);
        // 到发同时为空 → 400
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(key("REQ"), key("SCH"),
                                List.of(new OccDef("G1", key("SEC"), 8, 0, 12, 0)),
                                List.of(new StopDef("G1", station, null, null, null, null)),
                                null)))
                .andExpect(status().isBadRequest());
        // 站停列车不在占用清单 → 400
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(key("REQ"), key("SCH"),
                                List.of(new OccDef("G1", key("SEC"), 8, 0, 12, 0)),
                                List.of(new StopDef("G9", station, 9, 0, 9, 10)),
                                null)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 容量 429、左闭右开与回滚 ----------

    @Test
    void publishRejectsCapacityOversell429ButAllowsAdjacentIntervals() throws Exception {
        String station = key("ST");
        configure(station, 0, 1, null);

        String first = key("SCH");
        createPlanWithStops(first,
                List.of(new OccDef("G1", key("SEC"), 8, 0, 12, 0)),
                List.of(new StopDef("G1", station, 8, 10, 8, 20)));
        publishPlan(first, key("REQ"), null);

        // 重叠 → 429，返回实际/要求/差额
        String overlapping = key("SCH");
        createPlanWithStops(overlapping,
                List.of(new OccDef("G2", key("SEC"), 8, 0, 12, 0)),
                List.of(new StopDef("G2", station, 8, 15, 8, 25)));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", overlapping)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"), null)))
                .andExpect(status().isTooManyRequests()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("PLATFORM_CAPACITY_EXCEEDED");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("stationId").asText()).isEqualTo(station);
        assertThat(detail.get("actualConcurrent").asInt()).isEqualTo(2);
        assertThat(detail.get("requiredCapacity").asInt()).isEqualTo(1);
        assertThat(detail.get("shortfall").asInt()).isEqualTo(1);
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(first);
        assertThat(detail.has("atUtc")).isTrue();
        assertThat(planStatus(overlapping)).isEqualTo("DRAFT");
        assertThat(publishedStopCount(overlapping)).isZero();

        // 相邻（前一发=后一到，左闭右开）合法
        String adjacent = key("SCH");
        createPlanWithStops(adjacent,
                List.of(new OccDef("G3", key("SEC"), 8, 0, 12, 0)),
                List.of(new StopDef("G3", station, 8, 20, 8, 30)));
        publishPlan(adjacent, key("REQ"), null);
    }

    @Test
    void batchPublishRollsBackWhenAnyStationFails() throws Exception {
        String goodStation = key("ST");
        configure(goodStation, 0, 5, null);
        // missingStation 不配置 → 站点不存在；shortStation 停留不足
        String shortStation = key("ST");
        configure(shortStation, 30, 5, null);
        String missingStation = key("ST");

        String scheduleKey = key("SCH");
        createPlanWithStops(scheduleKey,
                List.of(new OccDef("G1", key("SEC"), 8, 0, 13, 0)),
                List.of(
                        new StopDef("G1", goodStation, 8, 10, 8, 20),
                        new StopDef("G1", missingStation, 9, 0, 9, 10),
                        new StopDef("G1", shortStation, 12, 0, 12, 10)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"), null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        // 可区分原因：站点不存在与停留不足同时返回
        List<String> types = toStringList(read(result).get("details"), "type");
        assertThat(types).contains("STATION_NOT_FOUND", "DWELL_TOO_SHORT");

        // 整次回滚：计划仍草稿，无任何站停快照，三站停登记仍可查
        assertThat(planStatus(scheduleKey)).isEqualTo("DRAFT");
        assertThat(publishedStopCount(scheduleKey)).isZero();
        JsonNode detail = read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(detail.get("stationStops")).hasSize(3);
    }

    @Test
    void sameBatchOverlappingStopsMustNotOversellCapacity() throws Exception {
        String station = key("ST");
        configure(station, 0, 1, null);

        // 同一计划内同一站台两条重叠站停 → 429 整批回滚
        String scheduleKey = key("SCH");
        createPlanWithStops(scheduleKey,
                List.of(new OccDef("G1", key("SEC"), 8, 0, 12, 0)),
                List.of(
                        new StopDef("G1", station, 9, 0, 9, 30),
                        new StopDef("G1", station, 9, 10, 9, 20)));
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"), null)))
                .andExpect(status().isTooManyRequests());
        assertThat(planStatus(scheduleKey)).isEqualTo("DRAFT");
        assertThat(publishedStopCount(scheduleKey)).isZero();
    }

    @Test
    void otherTrainStartingInsideCandidateWindowMustNotOversell() throws Exception {
        String station = key("ST");
        configure(station, 0, 1, null);

        // 已发布列车在该站台仅 09:30-09:45 停靠
        String first = key("SCH");
        createPlanWithStops(first,
                List.of(new OccDef("G1", key("SEC"), 8, 0, 12, 0)),
                List.of(new StopDef("G1", station, 9, 30, 9, 45)));
        publishPlan(first, key("REQ"), null);

        // 新列车 09:00-10:00 停靠：其起点 09:00 空闲，但他车在区间内 09:30 进站 → 必须 429
        String second = key("SCH");
        createPlanWithStops(second,
                List.of(new OccDef("G2", key("SEC"), 8, 0, 12, 0)),
                List.of(new StopDef("G2", station, 9, 0, 10, 0)));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", second)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"), null)))
                .andExpect(status().isTooManyRequests()).andReturn();
        JsonNode detail = read(result).get("details").get(0);
        // 超卖观测点为他车进站时刻 09:30
        assertThat(detail.get("atUtc").asText()).isEqualTo(iso(9, 30));
        assertThat(detail.get("actualConcurrent").asInt()).isEqualTo(2);
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(first);

        // 缩短为 09:45-10:00（左闭右开，恰在他车发车时进站）即可发布
        String third = key("SCH");
        createPlanWithStops(third,
                List.of(new OccDef("G3", key("SEC"), 8, 0, 12, 0)),
                List.of(new StopDef("G3", station, 9, 45, 10, 0)));
        publishPlan(third, key("REQ"), null);
    }

    @Test
    void cancelReleasesPlatformCapacityAndHistoryRemains() throws Exception {
        String station = key("ST");
        configure(station, 0, 1, null);

        String first = key("SCH");
        createPlanWithStops(first,
                List.of(new OccDef("G1", key("SEC"), 8, 0, 12, 0)),
                List.of(new StopDef("G1", station, 10, 0, 10, 30)));
        publishPlan(first, key("REQ"), null);

        String follower = key("SCH");
        createPlanWithStops(follower,
                List.of(new OccDef("G2", key("SEC"), 8, 0, 12, 0)),
                List.of(new StopDef("G2", station, 10, 0, 10, 30)));
        mvc.perform(post("/api/v1/plans/{key}/publish", follower)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"), null)))
                .andExpect(status().isTooManyRequests());

        cancelPlan(first, key("REQ"), null);

        // 容量释放，重叠计划可发布；被取消计划的历史快照保留
        publishPlan(follower, key("REQ2"), null);
        assertThat(publishedStopCount(first)).isEqualTo(1);
        JsonNode diag = read(mvc.perform(get("/api/v1/stations/{id}/diagnostics", station)
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(diag.get("stops")).hasSize(1);
        assertThat(diag.get("stops").get(0).get("scheduleKey").asText()).isEqualTo(follower);
    }

    // ---------- 改签联动 ----------

    @Test
    void rescheduleFreezesNewStopsAndKeepsOldSnapshot() throws Exception {
        String station = key("ST");
        configure(station, 5, 2, null);
        String section = key("SEC");

        String oldKey = key("SCH");
        createPlanWithStops(oldKey,
                List.of(new OccDef("G1", section, 8, 0, 10, 0)),
                List.of(new StopDef("G1", station, 8, 10, 8, 20)));
        publishPlan(oldKey, key("REQ"), null);

        String newKey = key("SCH");
        createPlanWithStops(newKey,
                List.of(new OccDef("G2", section, 8, 0, 10, 0)),
                List.of(new StopDef("G2", station, 8, 10, 8, 30)));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1, null)))
                .andExpect(status().isOk());

        // 新快照固化，旧快照保留
        assertThat(publishedStopCount(oldKey)).isEqualTo(1);
        JsonNode newSnapshots = read(mvc.perform(get("/api/v1/plans/{key}/published-stops", newKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(newSnapshots).hasSize(1);
        assertThat(newSnapshots.get(0).get("trainNo").asText()).isEqualTo("G2");
        assertThat(newSnapshots.get(0).get("departureUtc").asText()).isEqualTo(iso(8, 30));

        // 站台容量为 2：另一列车走不同区段但同站重叠停靠，可再发一列车
        String third = key("SCH");
        createPlanWithStops(third,
                List.of(new OccDef("G3", key("SEC"), 8, 0, 10, 0)),
                List.of(new StopDef("G3", station, 8, 10, 8, 20)));
        publishPlan(third, key("REQ"), null);
    }

    @Test
    void rescheduleRejectsCapacityAndRollsBackWithoutSnapshot() throws Exception {
        String station = key("ST");
        configure(station, 0, 1, null);
        String thirdPartySection = key("SEC");

        String thirdParty = key("SCH");
        createPlanWithStops(thirdParty,
                List.of(new OccDef("G9", thirdPartySection, 8, 0, 12, 0)),
                List.of(new StopDef("G9", station, 9, 0, 10, 0)));
        publishPlan(thirdParty, key("REQ"), null);

        // 旧计划走不相交的区段且无站停
        String oldKey = key("SCH");
        createPlanWithStops(oldKey,
                List.of(new OccDef("G1", key("SEC"), 13, 0, 15, 0)),
                List.of());
        publishPlan(oldKey, key("REQ"), null);

        // 新草稿走第三条区段，但站停与第三方同站台重叠 → 仅容量冲突（429）
        String newKey = key("SCH");
        createPlanWithStops(newKey,
                List.of(new OccDef("G2", key("SEC"), 8, 0, 12, 0)),
                List.of(new StopDef("G2", station, 9, 30, 10, 30)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1, null)))
                .andExpect(status().isTooManyRequests()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("PLATFORM_CAPACITY_EXCEEDED");

        // 回滚：旧仍发布、新仍草稿、无新快照、链不变
        assertThat(planStatus(oldKey)).isEqualTo("PUBLISHED");
        assertThat(planStatus(newKey)).isEqualTo("DRAFT");
        assertThat(publishedStopCount(newKey)).isZero();
    }

    // ---------- 幂等：操作者指纹与规范化集合 ----------

    @Test
    void publishIdempotentReplayOperatorConflictAndSnapshotStable() throws Exception {
        String station = key("ST");
        configure(station, 5, 2, null);
        String scheduleKey = key("SCH");
        createPlanWithStops(scheduleKey,
                List.of(new OccDef("G1", key("SEC"), 8, 0, 10, 0)),
                List.of(new StopDef("G1", station, 8, 10, 8, 20)));
        String publishKey = key("REQ");

        MvcResult first = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey, "alice")))
                .andExpect(status().isOk()).andReturn();

        // 同键同操作者重放返回首次完整响应快照
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey, "alice")))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同操作者 → 409
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey, "bob")))
                .andExpect(status().isConflict());

        // 规则修订后重放仍返回首次快照（读取不改变状态）
        configure(station, 40, 9, "carol");
        MvcResult replayAfter = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey, "alice")))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replayAfter)).isEqualTo(read(first));
    }

    @Test
    void createFingerprintNormalizesCollectionOrder() throws Exception {
        String station = key("ST");
        configure(station, 0, 5, null);
        String scheduleKey = key("SCH");
        String section = key("SEC");
        String requestKey = key("REQ");

        List<OccDef> ordered = List.of(
                new OccDef("G1", section, 8, 0, 9, 0),
                new OccDef("G2", section, 9, 0, 10, 0));
        List<OccDef> reversed = List.of(
                new OccDef("G2", section, 9, 0, 10, 0),
                new OccDef("G1", section, 8, 0, 9, 0));

        MvcResult first = mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(requestKey, scheduleKey, ordered,
                                List.of(new StopDef("G1", station, 8, 10, 8, 20)), null)))
                .andExpect(status().isCreated()).andReturn();
        // 仅集合顺序不同、同键 → 视为同参重放，返回首次完整响应快照
        MvcResult replay = mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(requestKey, scheduleKey, reversed,
                                List.of(new StopDef("G1", station, 8, 10, 8, 20)), null)))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同一集合中任一字段变化（停站分钟数）→ 同键异参 409
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(requestKey, key("SCH"), ordered,
                                List.of(new StopDef("G1", station, 8, 10, 8, 21)), null)))
                .andExpect(status().isConflict());
        // 操作者变化同样 409
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(requestKey, key("SCH"), ordered,
                                List.of(new StopDef("G1", station, 8, 10, 8, 20)), "dave")))
                .andExpect(status().isConflict());
    }

    @Test
    void failedPublishDoesNotConsumeKeyAndLeavesNoHalfState() throws Exception {
        String station = key("ST");
        configure(station, 10, 5, null);
        String scheduleKey = key("SCH");
        String publishKey = key("REQ");
        createPlanWithStops(scheduleKey,
                List.of(new OccDef("G1", key("SEC"), 8, 0, 12, 0)),
                List.of(new StopDef("G1", station, 9, 0, 9, 5)));

        // 停留不足 422
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey, null)))
                .andExpect(status().isUnprocessableEntity());
        // 失败不占键：放宽站点规则后同键发布成功
        configure(station, 5, 5, null);
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey, null)))
                .andExpect(status().isOk());
    }

    // ---------- 辅助 ----------

    private void configure(String station, int minDwell, int capacity, String operator)
            throws Exception {
        mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), minDwell, capacity, operator)))
                .andExpect(status().is2xxSuccessful());
    }

    private void createPlanWithStops(String scheduleKey, List<OccDef> occupancies,
                                     List<StopDef> stops) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(key("REQ"), scheduleKey, occupancies, stops, null)))
                .andExpect(status().isCreated());
    }

    private void replaceWithStops(String scheduleKey, int expectedVersion,
                                  List<OccDef> occupancies, List<StopDef> stops) throws Exception {
        mvc.perform(put("/api/v1/plans/{key}/occupancies", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), expectedVersion, occupancies, stops)))
                .andExpect(status().isOk());
    }

    private void publishPlan(String scheduleKey, String requestKey, String operator)
            throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey, operator)))
                .andExpect(status().isOk());
    }

    private void cancelPlan(String scheduleKey, String requestKey, String operator)
            throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey, operator)))
                .andExpect(status().isOk());
    }

    private String planStatus(String scheduleKey) throws Exception {
        return read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn()).get("status").asText();
    }

    private int publishedStopCount(String scheduleKey) throws Exception {
        return read(mvc.perform(get("/api/v1/plans/{key}/published-stops", scheduleKey))
                .andExpect(status().isOk()).andReturn()).size();
    }

    private static List<String> toStringList(JsonNode array, String field) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (JsonNode node : array) {
            values.add(node.get(field).asText());
        }
        return values;
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour, int minute) {
        Instant instant = DAY.atTime(hour, minute).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String configBody(String requestKey, int minDwell, int capacity,
                                     String operator) {
        StringBuilder sb = new StringBuilder("{\"requestKey\":\"").append(requestKey)
                .append("\",\"minDwellMinutes\":").append(minDwell)
                .append(",\"platformCapacity\":").append(capacity);
        if (operator != null) {
            sb.append(",\"operatorId\":\"").append(operator).append('"');
        }
        return sb.append('}').toString();
    }

    private static String actionBody(String requestKey, String operator) {
        StringBuilder sb = new StringBuilder("{\"requestKey\":\"").append(requestKey).append('"');
        if (operator != null) {
            sb.append(",\"operatorId\":\"").append(operator).append('"');
        }
        return sb.append('}').toString();
    }

    private static String rescheduleBody(String requestKey, String newKey, int oldVersion,
                                         int newVersion, String operator) {
        StringBuilder sb = new StringBuilder("{\"requestKey\":\"").append(requestKey)
                .append("\",\"newScheduleKey\":\"").append(newKey)
                .append("\",\"expectedOldVersion\":").append(oldVersion)
                .append(",\"expectedNewVersion\":").append(newVersion);
        if (operator != null) {
            sb.append(",\"operatorId\":\"").append(operator).append('"');
        }
        return sb.append('}').toString();
    }

    private static String planBody(String requestKey, String scheduleKey,
                                   List<OccDef> occupancies, List<StopDef> stops,
                                   String operator) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"requestKey\":\"").append(requestKey)
                .append("\",\"scheduleKey\":\"").append(scheduleKey)
                .append("\",\"opDate\":\"").append(DAY).append("\",\"occupancies\":[");
        appendOccs(sb, occupancies);
        sb.append("],\"stationStops\":[");
        appendStops(sb, stops);
        sb.append(']');
        if (operator != null) {
            sb.append(",\"operatorId\":\"").append(operator).append('"');
        }
        return sb.append('}').toString();
    }

    private static String updateBody(String requestKey, int expectedVersion,
                                     List<OccDef> occupancies, List<StopDef> stops) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"requestKey\":\"").append(requestKey)
                .append("\",\"expectedVersion\":").append(expectedVersion)
                .append(",\"occupancies\":[");
        appendOccs(sb, occupancies);
        sb.append("],\"stationStops\":[");
        appendStops(sb, stops);
        return sb.append("]}").toString();
    }

    private static void appendOccs(StringBuilder sb, List<OccDef> occupancies) {
        for (int i = 0; i < occupancies.size(); i++) {
            OccDef o = occupancies.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"trainNo\":\"").append(o.trainNo).append("\",\"sectionId\":\"")
                    .append(o.sectionId).append("\",\"startUtc\":\"").append(iso(o.startH, o.startM))
                    .append("\",\"endUtc\":\"").append(iso(o.endH, o.endM)).append("\"}");
        }
    }

    private static void appendStops(StringBuilder sb, List<StopDef> stops) {
        for (int i = 0; i < stops.size(); i++) {
            StopDef s = stops.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"trainNo\":\"").append(s.trainNo).append("\",\"stationId\":\"")
                    .append(s.stationId).append("\",");
            if (s.arrivalH == null) {
                sb.append("\"arrivalUtc\":null,");
            } else {
                sb.append("\"arrivalUtc\":\"").append(iso(s.arrivalH, s.arrivalM)).append("\",");
            }
            if (s.departureH == null) {
                sb.append("\"departureUtc\":null}");
            } else {
                sb.append("\"departureUtc\":\"").append(iso(s.departureH, s.departureM))
                        .append("\"}");
            }
        }
    }

    private record OccDef(String trainNo, String sectionId, int startH, int startM,
                          int endH, int endM) {
    }

    private record StopDef(String trainNo, String stationId, Integer arrivalH, Integer arrivalM,
                           Integer departureH, Integer departureM) {
        StopDef(String trainNo, String stationId, int ah, int am, int dh, int dm) {
            this(trainNo, stationId, Integer.valueOf(ah), Integer.valueOf(am),
                    Integer.valueOf(dh), Integer.valueOf(dm));
        }
    }
}
