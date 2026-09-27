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
 * 铁路站停时长端到端测试（真实 H2 内存库）：站台配置版本化、到发登记发布、
 * 最小停站/到发倒置/站点缺失 422、容量满 429、批量失败整次回滚、
 * 发布快照固化且规则修订不改写历史、幂等边界与只读诊断查询。
 * 各用例使用唯一站点/计划/区段/幂等键，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StationDwellApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 站台配置 ----------

    @Test
    void registerStationConfigThenGet() throws Exception {
        String station = key("ST");
        MvcResult result = mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), 5, 2, null, "alice")))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("stationId").asText()).isEqualTo(station);
        assertThat(body.get("minDwellMinutes").asInt()).isEqualTo(5);
        assertThat(body.get("platformCapacity").asInt()).isEqualTo(2);
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("operator").asText()).isEqualTo("alice");

        MvcResult got = mvc.perform(get("/api/v1/stations/{id}/config", station))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(got)).isEqualTo(body);
    }

    @Test
    void stationConfigRevisionVersioningAndConflicts() throws Exception {
        String station = key("ST");
        registerConfig(station, 5, 2, null, "alice");

        // 已登记但仍按首次登记（expectedVersion=null）→ 409
        mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), 6, 2, null, "bob")))
                .andExpect(status().isConflict());

        // 版本落后 → 409
        mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), 6, 2, 9, "bob")))
                .andExpect(status().isConflict());

        // 正确版本修订 → 版本 2
        MvcResult revised = mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), 8, 3, 1, "bob")))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(revised);
        assertThat(body.get("version").asInt()).isEqualTo(2);
        assertThat(body.get("minDwellMinutes").asInt()).isEqualTo(8);
        assertThat(body.get("platformCapacity").asInt()).isEqualTo(3);
        assertThat(body.get("operator").asText()).isEqualTo("bob");
    }

    @Test
    void registerRejectsExpectedVersionForUnknownStation() throws Exception {
        mvc.perform(put("/api/v1/stations/{id}/config", key("ST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), 5, 2, 0, "alice")))
                .andExpect(status().isConflict());
    }

    @Test
    void missingStationConfigReturns404() throws Exception {
        mvc.perform(get("/api/v1/stations/{id}/config", key("ST")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/stations/{id}/diagnostics", key("ST"))
                        .param("date", DAY.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void stationConfigIdempotentReplayAndParamConflict() throws Exception {
        String station = key("ST");
        String requestKey = key("REQ");
        String body = configBody(requestKey, 5, 2, null, "alice");

        MvcResult first = mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键改分钟数 → 409
        mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(requestKey, 6, 2, null, "alice")))
                .andExpect(status().isConflict());
        // 同键仅改操作者 → 操作者属于指纹，同样 409
        mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(requestKey, 5, 2, null, "eve")))
                .andExpect(status().isConflict());
    }

    // ---------- 主流程：登记、发布、快照、诊断、取消 ----------

    @Test
    void publishFreezesDwellSnapshotAndDiagnosticsReflectsIt() throws Exception {
        String station = key("ST");
        registerConfig(station, 5, 2, null, "alice");
        String section = key("SEC");
        String scheduleKey = key("SCH");
        // G1 08:10 到、08:20 发，实际停 10 分钟，达到下限 5
        createPlan(scheduleKey, section, "G1", station, iso(8, 10), iso(8, 20), "dispatcher-1");

        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode planBody = read(detail);
        assertThat(planBody.get("stationDwells")).hasSize(1);
        assertThat(planBody.get("stationDwells").get(0).get("stationId").asText())
                .isEqualTo(station);
        assertThat(planBody.get("stationDwells").get(0).get("arrivalUtc").asText())
                .isEqualTo(iso(8, 10));

        publish(scheduleKey, key("REQ"), "dispatcher-1");

        MvcResult snapshots = mvc.perform(get("/api/v1/plans/{key}/snapshots", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode list = read(snapshots);
        assertThat(list).hasSize(1);
        JsonNode snap = list.get(0);
        assertThat(snap.get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(snap.get("publishedBy").asText()).isEqualTo("dispatcher-1");
        assertThat(snap.get("dwells")).hasSize(1);
        JsonNode dwell = snap.get("dwells").get(0);
        assertThat(dwell.get("stationId").asText()).isEqualTo(station);
        assertThat(dwell.get("stationVersion").asInt()).isEqualTo(1);
        assertThat(dwell.get("minDwellMinutes").asInt()).isEqualTo(5);
        assertThat(dwell.get("platformCapacity").asInt()).isEqualTo(2);
        assertThat(dwell.get("actualDwellMinutes").asInt()).isEqualTo(10);

        MvcResult diag = mvc.perform(get("/api/v1/stations/{id}/diagnostics", station)
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode diagnostics = read(diag);
        assertThat(diagnostics.get("configured").asBoolean()).isTrue();
        assertThat(diagnostics.get("version").asInt()).isEqualTo(1);
        assertThat(diagnostics.get("activeDwells")).hasSize(1);
        assertThat(diagnostics.get("activeDwells").get(0).get("scheduleKey").asText())
                .isEqualTo(scheduleKey);
        assertThat(diagnostics.get("activeDwells").get(0).get("actualDwellMinutes").asInt())
                .isEqualTo(10);
        assertThat(diagnostics.get("snapshots")).hasSize(1);
        assertThat(diagnostics.get("snapshots").get(0).get("snapshotId").asLong())
                .isEqualTo(snap.get("snapshotId").asLong());
    }

    @Test
    void cancelReleasesPlatformButKeepsSnapshot() throws Exception {
        String station = key("ST");
        registerConfig(station, 0, 1, null, "alice");
        String sectionA = key("SEC");
        String planA = key("SCH");
        createPlan(planA, sectionA, "G1", station, iso(8, 10), iso(8, 20), null);
        publish(planA, key("REQ"), null);

        cancel(planA, key("REQ"), null);

        // 容量已释放：重叠站停的新计划可发布
        String sectionB = key("SEC");
        String planB = key("SCH");
        createPlan(planB, sectionB, "G2", station, iso(8, 15), iso(8, 25), null);
        publish(planB, key("REQ"), null);

        MvcResult diag = mvc.perform(get("/api/v1/stations/{id}/diagnostics", station)
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode diagnostics = read(diag);
        assertThat(diagnostics.get("activeDwells")).hasSize(1);
        assertThat(diagnostics.get("activeDwells").get(0).get("scheduleKey").asText())
                .isEqualTo(planB);
        // 旧快照仍保留
        JsonNode oldSnaps = read(mvc.perform(get("/api/v1/plans/{key}/snapshots", planA))
                .andExpect(status().isOk()).andReturn());
        assertThat(oldSnaps).hasSize(1);
        assertThat(oldSnaps.get(0).get("dwells").get(0).get("trainNo").asText()).isEqualTo("G1");
        // 诊断中可见两份历史快照，按 id 升序
        assertThat(diagnostics.get("snapshots")).hasSize(2);
        long firstId = diagnostics.get("snapshots").get(0).get("snapshotId").asLong();
        long secondId = diagnostics.get("snapshots").get(1).get("snapshotId").asLong();
        assertThat(secondId).isGreaterThan(firstId);
    }

    @Test
    void ruleRevisionDoesNotRewriteHistorySnapshot() throws Exception {
        String station = key("ST");
        registerConfig(station, 5, 5, null, "alice");
        String planA = key("SCH");
        createPlan(planA, key("SEC"), "G1", station, iso(8, 10), iso(8, 15), null);
        publish(planA, key("REQ"), null);

        // 修订为下限 10 分钟、容量 3，版本 2
        mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), 10, 3, 1, "bob")))
                .andExpect(status().isOk());

        // 历史快照仍固化版本 1 与旧规则
        JsonNode oldSnaps = read(mvc.perform(get("/api/v1/plans/{key}/snapshots", planA))
                .andExpect(status().isOk()).andReturn());
        JsonNode oldDwell = oldSnaps.get(0).get("dwells").get(0);
        assertThat(oldDwell.get("stationVersion").asInt()).isEqualTo(1);
        assertThat(oldDwell.get("minDwellMinutes").asInt()).isEqualTo(5);
        assertThat(oldDwell.get("platformCapacity").asInt()).isEqualTo(5);
        assertThat(oldDwell.get("actualDwellMinutes").asInt()).isEqualTo(5);

        // 旧计划明细与登记也不改写
        JsonNode oldPlan = read(mvc.perform(get("/api/v1/plans/{key}", planA))
                .andExpect(status().isOk()).andReturn());
        assertThat(oldPlan.get("stationDwells").get(0).get("departureUtc").asText())
                .isEqualTo(iso(8, 15));

        // 新发布按新版本固化
        String planB = key("SCH");
        createPlan(planB, key("SEC"), "G2", station, iso(9, 10), iso(9, 20), null);
        publish(planB, key("REQ"), null);
        JsonNode newSnaps = read(mvc.perform(get("/api/v1/plans/{key}/snapshots", planB))
                .andExpect(status().isOk()).andReturn());
        JsonNode newDwell = newSnaps.get(0).get("dwells").get(0);
        assertThat(newDwell.get("stationVersion").asInt()).isEqualTo(2);
        assertThat(newDwell.get("minDwellMinutes").asInt()).isEqualTo(10);
        assertThat(newDwell.get("platformCapacity").asInt()).isEqualTo(3);
        assertThat(newDwell.get("actualDwellMinutes").asInt()).isEqualTo(10);
    }

    // ---------- 422 失败分支 ----------

    @Test
    void publishRejectsUnknownStation() throws Exception {
        String station = key("ST");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, key("SEC"), "G1", station, iso(8, 10), iso(8, 20), null);

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("STATION_NOT_FOUND");
        assertThat(detail.get("stationId").asText()).isEqualTo(station);
        assertThat(read(getPlan(scheduleKey)).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void publishRejectsReversedArrivalDepartureWithActualMinutes() throws Exception {
        String station = key("ST");
        registerConfig(station, 0, 5, null, null);
        String scheduleKey = key("SCH");
        // 发车早于到达
        createPlan(scheduleKey, key("SEC"), "G1", station, iso(8, 20), iso(8, 10), null);

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode detail = read(result).get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("ARRIVAL_DEPARTURE_REVERSED");
        assertThat(detail.get("stationId").asText()).isEqualTo(station);
        assertThat(detail.get("actualDwellMinutes").asInt()).isEqualTo(-10);
    }

    @Test
    void publishRejectsEqualArrivalDeparture() throws Exception {
        String station = key("ST");
        registerConfig(station, 0, 5, null, null);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, key("SEC"), "G1", station, iso(8, 10), iso(8, 10), null);

        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void publishRejectsDwellTooShortWithRequiredAndShortfall() throws Exception {
        String station = key("ST");
        registerConfig(station, 5, 5, null, null);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, key("SEC"), "G1", station, iso(8, 10), iso(8, 13), null);

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode detail = read(result).get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("DWELL_TOO_SHORT");
        assertThat(detail.get("stationId").asText()).isEqualTo(station);
        assertThat(detail.get("actualDwellMinutes").asInt()).isEqualTo(3);
        assertThat(detail.get("requiredDwellMinutes").asInt()).isEqualTo(5);
        assertThat(detail.get("shortfallMinutes").asInt()).isEqualTo(2);
    }

    @Test
    void batchPublishFailsAtomicallyAndLeavesNoSnapshot() throws Exception {
        String station1 = key("ST");
        String station2 = key("ST");
        // 两个站点都未登记：两条 422 明细一次性返回
        String scheduleKey = key("SCH");
        String body = createBody(key("REQ"), scheduleKey,
                new String[] {occ("G1", key("SEC"), iso(8), iso(9))},
                new String[] {
                        dwell("G1", station1, iso(8, 10), iso(8, 20)),
                        dwell("G2", station2, iso(8, 10), iso(8, 20))},
                null);
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("details")).hasSize(2);
        assertThat(error.get("details")).extracting(d -> d.get("type").asText())
                .containsExactly("STATION_NOT_FOUND", "STATION_NOT_FOUND");

        // 整次回滚：仍为草稿、无快照
        assertThat(read(getPlan(scheduleKey)).get("status").asText()).isEqualTo("DRAFT");
        JsonNode snaps = read(mvc.perform(get("/api/v1/plans/{key}/snapshots", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(snaps).isEmpty();
    }

    // ---------- 429 容量分支 ----------

    @Test
    void publishRejectsPlatformCapacityFullWithActualAndOverBy() throws Exception {
        String station = key("ST");
        registerConfig(station, 0, 1, null, null);
        String planA = key("SCH");
        createPlan(planA, key("SEC"), "G1", station, iso(8, 10), iso(8, 20), null);
        publish(planA, key("REQ"), null);

        String planB = key("SCH");
        createPlan(planB, key("SEC"), "G2", station, iso(8, 15), iso(8, 25), null);
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", planB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isTooManyRequests()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("PLATFORM_CAPACITY_FULL");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("PLATFORM_CAPACITY_EXCEEDED");
        assertThat(detail.get("stationId").asText()).isEqualTo(station);
        assertThat(detail.get("actualConcurrent").asInt()).isEqualTo(2);
        assertThat(detail.get("platformCapacity").asInt()).isEqualTo(1);
        assertThat(detail.get("overBy").asInt()).isEqualTo(1);
        assertThat(detail.get("peakStartUtc").asText()).isEqualTo(iso(8, 15));
        assertThat(detail.get("peakEndUtc").asText()).isEqualTo(iso(8, 20));

        // 失败计划保持草稿，容量未被占用
        assertThat(read(getPlan(planB)).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void halfOpenAdjacentDwellsDoNotExceedCapacity() throws Exception {
        String station = key("ST");
        registerConfig(station, 0, 1, null, null);
        String planA = key("SCH");
        createPlan(planA, key("SEC"), "G1", station, iso(8, 10), iso(8, 20), null);
        publish(planA, key("REQ"), null);

        // 08:20 到达与 G1 08:20 发车相邻，左闭右开不叠加
        String planB = key("SCH");
        createPlan(planB, key("SEC"), "G2", station, iso(8, 20), iso(8, 30), null);
        publish(planB, key("REQ"), null);

        JsonNode diag = read(mvc.perform(get("/api/v1/stations/{id}/diagnostics", station)
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(diag.get("activeDwells")).hasSize(2);
        assertThat(diag.get("activeDwells").get(0).get("arrivalUtc").asText())
                .isEqualTo(iso(8, 10));
        assertThat(diag.get("activeDwells").get(1).get("arrivalUtc").asText())
                .isEqualTo(iso(8, 20));
    }

    @Test
    void ownPlanDwellsExceedingCapacityReturn429() throws Exception {
        String station = key("ST");
        registerConfig(station, 0, 1, null, null);
        // 同一计划内两列车在站重叠，最终状态本身就超容量
        String scheduleKey = key("SCH");
        String body = createBody(key("REQ"), scheduleKey,
                occ("G1", key("SEC"), iso(8), iso(9)),
                occ("G2", key("SEC"), iso(9), iso(10)),
                dwell("G1", station, iso(8, 10), iso(8, 20)),
                dwell("G2", station, iso(8, 10), iso(8, 20)), null);
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isTooManyRequests()).andReturn();
        JsonNode detail = read(result).get("details").get(0);
        assertThat(detail.get("actualConcurrent").asInt()).isEqualTo(2);
        assertThat(detail.get("overBy").asInt()).isEqualTo(1);
    }

    // ---------- 草稿登记参数校验 ----------

    @Test
    void rejectsMalformedDwellRegistrations() throws Exception {
        String station = key("ST");
        registerConfig(station, 5, 2, null, null);

        // 到发时刻未对齐 UTC 整分钟
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"),
                                occ("G1", key("SEC"), iso(8), iso(9)),
                                dwell("G1", station, isoSeconds(8, 10, 30), iso(8, 20)), null)))
                .andExpect(status().isBadRequest());

        // 不在运营日内
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"),
                                occ("G1", key("SEC"), iso(8), iso(9)),
                                dwell("G1", station, iso(23, 50, 1), iso(0, 5, 1)), null)))
                .andExpect(status().isBadRequest());

        // 同一列车同一站重复登记
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"),
                                new String[] {occ("G1", key("SEC"), iso(8), iso(10))},
                                new String[] {
                                        dwell("G1", station, iso(8, 10), iso(8, 20)),
                                        dwell("G1", station, iso(9, 10), iso(9, 20))},
                                null)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 幂等 ----------

    @Test
    void publishIdempotentReplayIncludesOperatorAndFailuresDoNotOccupyKey() throws Exception {
        String station = key("ST");
        registerConfig(station, 5, 2, null, null);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, key("SEC"), "G1", station, iso(8, 10), iso(8, 13), null);

        // 首次发布失败：停站不足 422
        String publishKey = key("REQ");
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(publishKey, "alice")))
                .andExpect(status().isUnprocessableEntity());
        // 失败不占键：此时库里无快照、无幂等记录
        JsonNode snapsBefore = read(mvc.perform(get("/api/v1/plans/{key}/snapshots", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(snapsBefore).isEmpty();

        // 修正登记（停 6 分钟）后用同一发布键成功
        String updateBody = updateBody(key("REQ"), 1,
                occ("G1", key("SEC"), iso(8), iso(9)),
                dwell("G1", station, iso(8, 10), iso(8, 16)));
        mvc.perform(put("/api/v1/plans/{key}/occupancies", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk());
        MvcResult first = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(publishKey, "alice")))
                .andExpect(status().isOk()).andReturn();

        // 同键同参重放返回首次完整响应快照
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(publishKey, "alice")))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键异参（操作者不同）→ 409
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(publishKey, "bob")))
                .andExpect(status().isConflict());

        // 只成功过一次：仅一份快照
        JsonNode snapsAfter = read(mvc.perform(get("/api/v1/plans/{key}/snapshots", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(snapsAfter).hasSize(1);
        assertThat(snapsAfter.get(0).get("publishedBy").asText()).isEqualTo("alice");
    }

    // ---------- 改签联动站台规则 ----------

    @Test
    void rescheduleValidatesPlatformAndWritesSnapshotOrRollsBack() throws Exception {
        String station = key("ST");
        registerConfig(station, 0, 1, null, null);
        String oldKey = key("SCH");
        createPlan(oldKey, key("SEC"), "G1", station, iso(8, 10), iso(8, 20), null);
        publish(oldKey, key("REQ"), null);

        // 第三方已发布计划占用 08:40-08:50
        String thirdParty = key("SCH");
        createPlan(thirdParty, key("SEC"), "G9", station, iso(8, 40), iso(8, 50), null);
        publish(thirdParty, key("REQ"), null);

        // 新草稿同样占用 08:40-08:50：改签须 429 整体回滚
        String newKey = key("SCH");
        createPlan(newKey, key("SEC"), "G2", station, iso(8, 40), iso(8, 50), null);
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1, null)))
                .andExpect(status().isTooManyRequests());
        assertThat(read(getPlan(oldKey)).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(read(getPlan(newKey)).get("status").asText()).isEqualTo("DRAFT");
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}/snapshots", newKey))
                .andExpect(status().isOk()).andReturn())).isEmpty();

        // 调整到 08:20-08:30（与旧计划相邻且旧占用被排除，与第三方不重叠）后改签成功
        mvc.perform(put("/api/v1/plans/{key}/occupancies", newKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1,
                                occ("G2", key("SEC"), iso(8), iso(9)),
                                dwell("G2", station, iso(8, 20), iso(8, 30)))))
                .andExpect(status().isOk());
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 2, "clerk")))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("oldPlan").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("newPlan").get("status").asText()).isEqualTo("PUBLISHED");

        // 新计划快照固化；旧计划快照保留
        JsonNode newSnaps = read(mvc.perform(get("/api/v1/plans/{key}/snapshots", newKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(newSnaps).hasSize(1);
        assertThat(newSnaps.get(0).get("publishedBy").asText()).isEqualTo("clerk");
        JsonNode oldSnaps = read(mvc.perform(get("/api/v1/plans/{key}/snapshots", oldKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(oldSnaps).hasSize(1);

        // 诊断：生效站停为第三方与新计划，旧计划已释放
        JsonNode diag = read(mvc.perform(get("/api/v1/stations/{id}/diagnostics", station)
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(diag.get("activeDwells")).hasSize(2);
        assertThat(diag.get("snapshots")).hasSize(3);
    }

    @Test
    void planWithoutDwellsPublishesEmptySnapshotWithNullOperator() throws Exception {
        String scheduleKey = key("SCH");
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey,
                                occ("G1", key("SEC"), iso(8), iso(9)), null, null)))
                .andExpect(status().isCreated());
        publish(scheduleKey, key("REQ"), null);

        JsonNode snaps = read(mvc.perform(get("/api/v1/plans/{key}/snapshots", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(snaps).hasSize(1);
        assertThat(snaps.get(0).get("dwells")).isEmpty();
        assertThat(snaps.get(0).get("publishedBy").isNull());

        JsonNode plan = read(getPlan(scheduleKey));
        assertThat(plan.get("stationDwells")).isEmpty();
    }

    // ---------- 辅助 ----------

    private MvcResult getPlan(String scheduleKey) throws Exception {
        return mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
    }

    private void registerConfig(String station, int minDwell, int capacity, Integer expectedVersion,
                                String operator) throws Exception {
        mvc.perform(put("/api/v1/stations/{id}/config", station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), minDwell, capacity, expectedVersion, operator)))
                .andExpect(status().isOk());
    }

    private void createPlan(String scheduleKey, String section, String train, String station,
                            String arrivalIso, String departureIso, String operator) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey,
                                occ(train, section, iso(8), iso(9)),
                                dwell(train, station, arrivalIso, departureIso), operator)))
                .andExpect(status().isCreated());
    }

    private void publish(String scheduleKey, String requestKey, String operator) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(requestKey, operator)))
                .andExpect(status().isOk());
    }

    private void cancel(String scheduleKey, String requestKey, String operator) throws Exception {
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

    /** 运营日 hour 点（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour) {
        return iso(hour, 0);
    }

    /** 运营日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute) {
        Instant instant = DAY.atTime(hour, minute).atZone(SH).toInstant();
        return instant.toString();
    }

    /** 运营日加 dayOffset 天 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute, int dayOffset) {
        Instant instant = DAY.plusDays(dayOffset).atTime(hour, minute).atZone(SH).toInstant();
        return instant.toString();
    }

    /** 运营日 hour:minute:second（Asia/Shanghai）的 UTC 时刻，用于构造非整分钟输入。 */
    private static String isoSeconds(int hour, int minute, int second) {
        Instant instant = DAY.atTime(hour, minute, second).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String dwell(String train, String station, String arrivalIso, String departureIso) {
        return "{\"trainNo\":\"" + train + "\",\"stationId\":\"" + station
                + "\",\"arrivalUtc\":\"" + arrivalIso + "\",\"departureUtc\":\"" + departureIso
                + "\"}";
    }

    private static String configBody(String requestKey, int minDwell, int capacity,
                                     Integer expectedVersion, String operator) {
        StringBuilder sb = new StringBuilder("{\"requestKey\":\"").append(requestKey)
                .append("\",\"minDwellMinutes\":").append(minDwell)
                .append(",\"platformCapacity\":").append(capacity)
                .append(",\"expectedVersion\":");
        if (expectedVersion == null) {
            sb.append("null");
        } else {
            sb.append(expectedVersion);
        }
        sb.append(",\"operator\":");
        if (operator == null) {
            sb.append("null");
        } else {
            sb.append('"').append(operator).append('"');
        }
        return sb.append('}').toString();
    }

    private static String actionBody(String requestKey, String operator) {
        String op = operator == null ? "null" : "\"" + operator + "\"";
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":" + op + "}";
    }

    private static String rescheduleBody(String requestKey, String newKey, int oldVersion,
                                         int newVersion, String operator) {
        String op = operator == null ? "null" : "\"" + operator + "\"";
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newKey
                + "\",\"expectedOldVersion\":" + oldVersion
                + ",\"expectedNewVersion\":" + newVersion
                + ",\"operator\":" + op + "}";
    }

    /**
     * 构造创建/更新请求体。occupancyArgs 前 1～n 个为区段占用 JSON，
     * 其后的 dwellArgs 为站停登记 JSON；dwellArgs 可为空数组。
     */
    private static String createBody(String requestKey, String scheduleKey, String occupancy,
                                     String dwell, String operator) {
        return createBody(requestKey, scheduleKey, new String[] {occupancy},
                dwell == null ? new String[0] : new String[] {dwell}, operator);
    }

    private static String createBody(String requestKey, String scheduleKey, String occupancy1,
                                     String occupancy2, String dwell1, String dwell2,
                                     String operator) {
        return createBody(requestKey, scheduleKey, new String[] {occupancy1, occupancy2},
                new String[] {dwell1, dwell2}, operator);
    }

    private static String createBody(String requestKey, String scheduleKey, String[] occupancies,
                                     String[] dwells, String operator) {
        String op = operator == null ? "null" : "\"" + operator + "\"";
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":[" + String.join(",", occupancies)
                + "],\"stationDwells\":[" + String.join(",", dwells) + "],\"operator\":" + op + "}";
    }

    private static String updateBody(String requestKey, int expectedVersion, String occupancy,
                                     String... dwells) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"occupancies\":[" + occupancy + "],\"stationDwells\":["
                + String.join(",", dwells) + "]}";
    }
}
