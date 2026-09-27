package com.example.starter.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 证物位置封签（库位迁移）API 测试：双人确认主流程、422 逐项失败、409 状态冲突、
 * 撤销语义、集合原子迁移、封签快照与幂等边界。全部基于真实 H2 内存库。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LocationMoveApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    /** 借出期限测试基准时刻（UTC）。 */
    private static final Instant BASE = Instant.parse("2026-09-22T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EvidenceClock evidenceClock;

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    /** 将 422 错误体的逐项失败说明按证物键聚合（一件证物可带多条原因）。 */
    private Map<String, List<String>> collectReasons(JsonNode error) {
        Map<String, List<String>> reasons = new LinkedHashMap<>();
        error.get("items").forEach(item ->
                reasons.computeIfAbsent(item.get("evidenceKey").asText(), k -> new java.util.ArrayList<>())
                        .add(item.get("reason").asText()));
        return reasons;
    }

    /** 固定时钟后借出（应还时刻 BASE+1h，满足不超过 72 小时约束）。 */
    private void borrowWithFixedClock(String actor, String evidenceKey, String borrower)
            throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", borrower);
        body.put("purpose", "鉴定");
        body.put("dueAt", LocalDateTime.ofInstant(BASE.plusSeconds(3600), ZoneOffset.UTC).toString());
        mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());
    }

    private MvcResult createLocation(String locationCode, String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("locationCode", locationCode);
        body.put("locationName", name);
        return mockMvc.perform(post("/api/locations")
                        .header(ACTOR_HEADER, "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult disableLocation(String actor, String locationCode, String commandKey)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return mockMvc.perform(post("/api/locations/{code}/disable", locationCode)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    /** 入库到指定库位（初始版本 1）。 */
    private MvcResult intakeAt(String actor, String evidenceKey, String locationCode)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-1");
        body.put("locationCode", locationCode);
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    /** 入库但不分配库位（不在库，版本 0）。 */
    private MvcResult intakeWithoutLocation(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-1");
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult applyMove(String actor, String commandKey, List<String> evidenceKeys,
                                String source, String target, long expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("evidenceKeys", evidenceKeys);
        body.put("sourceLocationCode", source);
        body.put("targetLocationCode", target);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/moves")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult confirmMove(String actor, String commandKey, String moveKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("moveKey", moveKey);
        return mockMvc.perform(post("/api/moves/confirm")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult cancelMove(String actor, String commandKey, String moveKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("moveKey", moveKey);
        return mockMvc.perform(post("/api/moves/cancel")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult inspect(String actor, String evidenceKey, boolean passed) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", passed);
        body.put("note", "routine");
        return mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult initiate(String actor, String evidenceKey, String toCustodian)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", toCustodian);
        return mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode inventory(String locationCode) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/locations/{code}/inventory", locationCode))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode moveDetail(String moveKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/moves/{key}", moveKey)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode chain(String evidenceKey) throws Exception {
        MvcResult result = mockMvc
                .perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void createLocationAndIntakeWithLocationStartsAtVersionOne() throws Exception {
        String loc = uniqueKey("LOC");
        MvcResult created = createLocation(loc, "一号库");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode location = objectMapper.readTree(created.getResponse().getContentAsString());
        assertThat(location.get("locationCode").asText()).isEqualTo(loc);
        assertThat(location.get("status").asText()).isEqualTo("ACTIVE");

        String ev = uniqueKey("EV");
        MvcResult intake = intakeAt("alice", ev, loc);
        assertThat(intake.getResponse().getStatus()).isEqualTo(201);
        JsonNode evidence = objectMapper.readTree(intake.getResponse().getContentAsString());
        assertThat(evidence.get("locationCode").asText()).isEqualTo(loc);
        assertThat(evidence.get("locationVersion").asLong()).isEqualTo(1L);

        JsonNode stock = inventory(loc);
        assertThat(stock.get("items")).hasSize(1);
        assertThat(stock.get("items").get(0).get("evidenceKey").asText()).isEqualTo(ev);
    }

    @Test
    void duplicateLocationCodeReturns409() throws Exception {
        String loc = uniqueKey("LOC");
        assertThat(createLocation(loc, "一号库").getResponse().getStatus()).isEqualTo(201);
        assertThat(createLocation(loc, "二号库").getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void intakeAtUnknownLocationReturns404() throws Exception {
        assertThat(intakeAt("alice", uniqueKey("EV"), uniqueKey("NOPE")).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void fullTwoPersonMoveUpdatesLocationsAndSnapshotsAtomically() throws Exception {
        String source = uniqueKey("SRC");
        String target = uniqueKey("TGT");
        createLocation(source, "源库位");
        createLocation(target, "目标库位");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        String ev3 = uniqueKey("EV");
        intakeAt("alice", ev1, source);
        intakeAt("alice", ev2, source);
        intakeAt("alice", ev3, source);

        String moveKey = uniqueKey("MOVE");
        MvcResult applied = applyMove("alice", moveKey, List.of(ev1, ev2, ev3),
                source, target, 1);
        assertThat(applied.getResponse().getStatus()).isEqualTo(200);
        JsonNode order = objectMapper.readTree(applied.getResponse().getContentAsString());
        assertThat(order.get("status").asText()).isEqualTo("AWAITING_SECOND");
        assertThat(order.get("firstConfirmedBy").asText()).isEqualTo("alice");
        assertThat(order.get("secondConfirmedBy").isNull()).isTrue();
        assertThat(order.get("evidenceKeys")).hasSize(3);

        // 待第二人确认期间证物仍在源库位、版本不变
        assertThat(chain(ev1).get("evidence").get("locationCode").asText()).isEqualTo(source);

        MvcResult confirmed = confirmMove("bob", uniqueKey("CMD"), moveKey);
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        JsonNode result = objectMapper.readTree(confirmed.getResponse().getContentAsString());
        assertThat(result.get("move").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.get("move").get("secondConfirmedBy").asText()).isEqualTo("bob");
        assertThat(result.get("snapshots")).hasSize(3);

        // 全部证物原子切换到目标库位且版本 +1
        for (String ev : List.of(ev1, ev2, ev3)) {
            JsonNode evidence = chain(ev).get("evidence");
            assertThat(evidence.get("locationCode").asText()).isEqualTo(target);
            assertThat(evidence.get("locationVersion").asLong()).isEqualTo(2L);
            assertThat(evidence.get("status").asText()).isEqualTo("SEALED");
        }
        assertThat(inventory(source).get("items")).isEmpty();
        assertThat(inventory(target).get("items")).hasSize(3);

        // 逐件封签快照内容核对
        JsonNode detail = moveDetail(moveKey);
        assertThat(detail.get("snapshots")).hasSize(3);
        for (JsonNode snapshot : detail.get("snapshots")) {
            assertThat(snapshot.get("moveKey").asText()).isEqualTo(moveKey);
            assertThat(snapshot.get("sealIntact").asBoolean()).isTrue();
            assertThat(snapshot.get("sealNo").asText()).isEqualTo("SEAL-1");
            assertThat(snapshot.get("locationCode").asText()).isEqualTo(source);
            assertThat(snapshot.get("locationVersion").asLong()).isEqualTo(1L);
            assertThat(snapshot.get("checkedBy").asText()).isEqualTo("bob");
        }
        assertThat(detail.get("snapshots").findValuesAsText("evidenceKey"))
                .containsExactlyInAnyOrder(ev1, ev2, ev3);
    }

    @Test
    void sameSetDifferentOrderIsSameParameterAndReplays() throws Exception {
        String source = uniqueKey("SRC");
        String target = uniqueKey("TGT");
        createLocation(source, "源库位");
        createLocation(target, "目标库位");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intakeAt("alice", ev1, source);
        intakeAt("alice", ev2, source);

        String moveKey = uniqueKey("MOVE");
        MvcResult first = applyMove("alice", moveKey, List.of(ev1, ev2), source, target, 1);
        // 集合换序 + 同键：视为同参重放，返回首次结果
        MvcResult replay = applyMove("alice", moveKey, List.of(ev2, ev1), source, target, 1);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 规范化后集合升序存储
        JsonNode order = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(order.get("evidenceKeys").get(0).asText()).isLessThan(
                order.get("evidenceKeys").get(1).asText());

        // 完成后同键重放返回完整迁移结果（含逐件快照）
        confirmMove("bob", uniqueKey("CMD"), moveKey);
        MvcResult replayAfterComplete =
                applyMove("alice", moveKey, List.of(ev2, ev1), source, target, 1);
        assertThat(replayAfterComplete.getResponse().getStatus()).isEqualTo(200);
        JsonNode result = objectMapper.readTree(replayAfterComplete.getResponse().getContentAsString());
        assertThat(result.get("move").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.get("snapshots")).hasSize(2);
    }

    @Test
    void applyWithItemFailuresReturns422WithPerItemReasons() throws Exception {
        String source = uniqueKey("SRC");
        String target = uniqueKey("TGT");
        createLocation(source, "源库位");
        createLocation(target, "目标库位");

        String missing = uniqueKey("MISSING");
        String notInStorage = uniqueKey("EV-NOLOC");
        String broken = uniqueKey("EV-BROKEN");
        String wrongLocation = uniqueKey("EV-WRONG");
        String versionMismatch = uniqueKey("EV-VER");
        String other = uniqueKey("OTHER");
        createLocation(other, "其他库位");
        intakeWithoutLocation("alice", notInStorage);
        intakeAt("alice", broken, source);
        intakeAt("alice", wrongLocation, other);
        intakeAt("alice", versionMismatch, source);
        inspect("alice", broken, false);

        MvcResult result = applyMove("alice", uniqueKey("MOVE"),
                List.of(missing, notInStorage, broken, wrongLocation, versionMismatch),
                source, target, 99);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(error.get("status").asInt()).isEqualTo(422);
        Map<String, List<String>> reasons = collectReasons(error);
        assertThat(reasons).containsOnlyKeys(missing, notInStorage, broken, wrongLocation,
                versionMismatch);
        assertThat(reasons.get(missing)).anyMatch(r -> r.contains("不存在"));
        assertThat(reasons.get(notInStorage)).anyMatch(r -> r.contains("不在库"));
        assertThat(reasons.get(broken)).anyMatch(r -> r.contains("封条"));
        assertThat(reasons.get(wrongLocation)).anyMatch(r -> r.contains("源库位不符"));
        assertThat(reasons.get(versionMismatch)).anyMatch(r -> r.contains("版本"));
    }

    @Test
    void applyWhileBorrowedOrPendingReturns422PerItem() throws Exception {
        String source = uniqueKey("SRC");
        String target = uniqueKey("TGT");
        createLocation(source, "源库位");
        createLocation(target, "目标库位");
        String borrowed = uniqueKey("EV-BORROW");
        String pending = uniqueKey("EV-PEND");
        intakeAt("alice", borrowed, source);
        intakeAt("alice", pending, source);

        // 借出
        borrowWithFixedClock("alice", borrowed, "bob");
        // 待核验（交接待接收）
        assertThat(initiate("alice", pending, "carol").getResponse().getStatus()).isEqualTo(200);

        MvcResult result = applyMove("alice", uniqueKey("MOVE"),
                List.of(borrowed, pending), source, target, 1);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        Map<String, List<String>> reasons = collectReasons(error);
        assertThat(reasons.get(borrowed)).anyMatch(r -> r.contains("借出"));
        assertThat(reasons.get(pending)).anyMatch(r -> r.contains("待核验"));
    }

    @Test
    void applyWithSameSourceAndTargetReturns400() throws Exception {
        String loc = uniqueKey("LOC");
        createLocation(loc, "库位");
        String ev = uniqueKey("EV");
        intakeAt("alice", ev, loc);
        assertThat(applyMove("alice", uniqueKey("MOVE"), List.of(ev), loc, loc, 1)
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void failedApplyDoesNotOccupyCommandKey() throws Exception {
        String source = uniqueKey("SRC");
        String target = uniqueKey("TGT");
        createLocation(source, "源库位");
        createLocation(target, "目标库位");
        String ev = uniqueKey("EV");
        intakeAt("alice", ev, source);

        String commandKey = uniqueKey("MOVE");
        // 首次版本不符失败（422），不占用命令键
        assertThat(applyMove("alice", commandKey, List.of(ev), source, target, 99)
                .getResponse().getStatus()).isEqualTo(422);
        // 同键同合法参数重试成功
        assertThat(applyMove("alice", commandKey, List.of(ev), source, target, 1)
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void sameCommandKeyWithChangedParamsReturns409() throws Exception {
        String source = uniqueKey("SRC");
        String target1 = uniqueKey("TGT1");
        String target2 = uniqueKey("TGT2");
        createLocation(source, "源库位");
        createLocation(target1, "目标一");
        createLocation(target2, "目标二");
        String ev = uniqueKey("EV");
        intakeAt("alice", ev, source);

        String commandKey = uniqueKey("MOVE");
        assertThat(applyMove("alice", commandKey, List.of(ev), source, target1, 1)
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(applyMove("alice", commandKey, List.of(ev), source, target2, 1)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void secondConfirmerSameAsFirstReturns409AndRollsBack() throws Exception {
        String source = uniqueKey("SRC");
        String target = uniqueKey("TGT");
        createLocation(source, "源库位");
        createLocation(target, "目标库位");
        String ev = uniqueKey("EV");
        intakeAt("alice", ev, source);
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, List.of(ev), source, target, 1);

        assertThat(confirmMove("alice", uniqueKey("CMD"), moveKey).getResponse().getStatus())
                .isEqualTo(409);
        // 整单回滚：仍待第二人、库位未变、无快照
        JsonNode detail = moveDetail(moveKey);
        assertThat(detail.get("move").get("status").asText()).isEqualTo("AWAITING_SECOND");
        assertThat(detail.get("snapshots")).isEmpty();
        assertThat(chain(ev).get("evidence").get("locationCode").asText()).isEqualTo(source);
    }

    @Test
    void confirmWhenTargetDisabledReturns409AndKeepsHistory() throws Exception {
        String source = uniqueKey("SRC");
        String target = uniqueKey("TGT");
        createLocation(source, "源库位");
        createLocation(target, "目标库位");
        String ev = uniqueKey("EV");
        intakeAt("alice", ev, source);
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, List.of(ev), source, target, 1);

        assertThat(disableLocation("admin", target, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(confirmMove("bob", uniqueKey("CMD"), moveKey).getResponse().getStatus())
                .isEqualTo(409);

        // 目标停用期间申请也被拒绝
        assertThat(applyMove("carol", uniqueKey("MOVE"), List.of(ev), source, target, 1)
                .getResponse().getStatus()).isEqualTo(409);

        JsonNode detail = moveDetail(moveKey);
        assertThat(detail.get("move").get("status").asText()).isEqualTo("AWAITING_SECOND");
        assertThat(detail.get("snapshots")).isEmpty();
        assertThat(chain(ev).get("evidence").get("locationCode").asText()).isEqualTo(source);
    }

    @Test
    void confirmWhenEvidenceBorrowedOrPendingReturns409AndRollsBackWholeSet() throws Exception {
        String source = uniqueKey("SRC");
        String target = uniqueKey("TGT");
        createLocation(source, "源库位");
        createLocation(target, "目标库位");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intakeAt("alice", ev1, source);
        intakeAt("alice", ev2, source);
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, List.of(ev1, ev2), source, target, 1);

        // 申请后、确认前其中一件被借出
        borrowWithFixedClock("alice", ev1, "bob");

        assertThat(confirmMove("bob", uniqueKey("CMD"), moveKey).getResponse().getStatus())
                .isEqualTo(409);
        // 整单回滚：两件证物库位与版本均未变、无快照、单据未完成
        for (String ev : List.of(ev1, ev2)) {
            JsonNode evidence = chain(ev).get("evidence");
            assertThat(evidence.get("locationCode").asText()).isEqualTo(source);
            assertThat(evidence.get("locationVersion").asLong()).isEqualTo(1L);
        }
        assertThat(moveDetail(moveKey).get("snapshots")).isEmpty();
    }

    @Test
    void confirmWithChangedVersionMidFlightReturns422AndRollsBackWholeSet() throws Exception {
        String source = uniqueKey("SRC");
        String target = uniqueKey("TGT");
        String third = uniqueKey("THIRD");
        createLocation(source, "源库位");
        createLocation(target, "目标库位");
        createLocation(third, "第三库位");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intakeAt("alice", ev1, source);
        intakeAt("alice", ev2, source);
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, List.of(ev1, ev2), source, target, 1);

        // 申请后 ev1 先被另一笔双人迁移挪走（版本变为 2）
        String otherMove = uniqueKey("MOVE");
        applyMove("carol", otherMove, List.of(ev1), source, third, 1);
        // carol 自己不能确认；用 dave 作为第二人（保管人不要求是库位操作人）
        assertThat(confirmMove("dave", uniqueKey("CMD"), otherMove).getResponse().getStatus())
                .isEqualTo(200);

        // 原单确认：ev1 库位/版本已变，整单 422 回滚，ev2 也不得迁移
        MvcResult failed = confirmMove("bob", uniqueKey("CMD"), moveKey);
        assertThat(failed.getResponse().getStatus()).isEqualTo(422);
        JsonNode error = objectMapper.readTree(failed.getResponse().getContentAsString());
        assertThat(error.get("items").findValuesAsText("evidenceKey")).contains(ev1);

        assertThat(chain(ev1).get("evidence").get("locationCode").asText()).isEqualTo(third);
        JsonNode ev2State = chain(ev2).get("evidence");
        assertThat(ev2State.get("locationCode").asText()).isEqualTo(source);
        assertThat(ev2State.get("locationVersion").asLong()).isEqualTo(1L);
        assertThat(moveDetail(moveKey).get("snapshots")).isEmpty();
        // 已完成的历史迁移与快照不得被改写
        JsonNode completedHistory = moveDetail(otherMove);
        assertThat(completedHistory.get("move").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(completedHistory.get("snapshots")).hasSize(1);
    }

    @Test
    void cancelByFirstConfirmerBlocksFurtherConfirmation() throws Exception {
        String source = uniqueKey("SRC");
        String target = uniqueKey("TGT");
        createLocation(source, "源库位");
        createLocation(target, "目标库位");
        String ev = uniqueKey("EV");
        intakeAt("alice", ev, source);
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, List.of(ev), source, target, 1);

        MvcResult cancelled = cancelMove("alice", uniqueKey("CMD"), moveKey);
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
        JsonNode order = objectMapper.readTree(cancelled.getResponse().getContentAsString());
        assertThat(order.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(order.get("cancelledBy").asText()).isEqualTo("alice");

        // 撤销后不可再确认、不可重复撤销
        assertThat(confirmMove("bob", uniqueKey("CMD"), moveKey).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(cancelMove("alice", uniqueKey("CMD"), moveKey).getResponse().getStatus())
                .isEqualTo(409);
        // 完成后不可撤销（先准备一笔完成单）
        String ev2 = uniqueKey("EV");
        intakeAt("alice", ev2, source);
        String moveKey2 = uniqueKey("MOVE");
        applyMove("alice", moveKey2, List.of(ev2), source, target, 1);
        confirmMove("bob", uniqueKey("CMD"), moveKey2);
        assertThat(cancelMove("alice", uniqueKey("CMD"), moveKey2).getResponse().getStatus())
                .isEqualTo(409);
        // 非首人确认人不可撤销
        String ev3 = uniqueKey("EV");
        intakeAt("alice", ev3, source);
        String moveKey3 = uniqueKey("MOVE");
        applyMove("alice", moveKey3, List.of(ev3), source, target, 1);
        assertThat(cancelMove("bob", uniqueKey("CMD"), moveKey3).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void confirmIdempotentReplayReturnsSameResult() throws Exception {
        String source = uniqueKey("SRC");
        String target = uniqueKey("TGT");
        createLocation(source, "源库位");
        createLocation(target, "目标库位");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intakeAt("alice", ev1, source);
        intakeAt("alice", ev2, source);
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, List.of(ev1, ev2), source, target, 1);

        String confirmCommand = uniqueKey("CMD");
        MvcResult first = confirmMove("bob", confirmCommand, moveKey);
        MvcResult replay = confirmMove("bob", confirmCommand, moveKey);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 重放不产生重复快照
        assertThat(moveDetail(moveKey).get("snapshots")).hasSize(2);
        for (String ev : List.of(ev1, ev2)) {
            assertThat(chain(ev).get("evidence").get("locationVersion").asLong()).isEqualTo(2L);
        }
    }

    @Test
    void repeatedMovesIncrementVersionAndAppendSnapshots() throws Exception {
        String locA = uniqueKey("A");
        String locB = uniqueKey("B");
        String locC = uniqueKey("C");
        createLocation(locA, "甲");
        createLocation(locB, "乙");
        createLocation(locC, "丙");
        String ev = uniqueKey("EV");
        intakeAt("alice", ev, locA);

        String move1 = uniqueKey("MOVE");
        applyMove("alice", move1, List.of(ev), locA, locB, 1);
        confirmMove("bob", uniqueKey("CMD"), move1);
        assertThat(chain(ev).get("evidence").get("locationVersion").asLong()).isEqualTo(2L);

        String move2 = uniqueKey("MOVE");
        applyMove("bob", move2, List.of(ev), locB, locC, 2);
        confirmMove("carol", uniqueKey("CMD"), move2);
        JsonNode evidence = chain(ev).get("evidence");
        assertThat(evidence.get("locationCode").asText()).isEqualTo(locC);
        assertThat(evidence.get("locationVersion").asLong()).isEqualTo(3L);

        // 两次迁移的历史快照均保留且不可改写
        MvcResult result = mockMvc.perform(get("/api/moves/by-evidence/{key}", ev)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode snapshots = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0).get("locationCode").asText()).isEqualTo(locA);
        assertThat(snapshots.get(0).get("locationVersion").asLong()).isEqualTo(1L);
        assertThat(snapshots.get(1).get("locationCode").asText()).isEqualTo(locB);
        assertThat(snapshots.get(1).get("locationVersion").asLong()).isEqualTo(2L);

        // 迁移单列表包含两笔 COMPLETED
        MvcResult listResult = mockMvc.perform(get("/api/moves")).andReturn();
        JsonNode allMoves = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(allMoves.findValuesAsText("moveKey")).contains(move1, move2);
    }

    @Test
    void operateOnUnknownMoveOrLocationReturns404() throws Exception {
        assertThat(confirmMove("bob", uniqueKey("CMD"), uniqueKey("NOPE")).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(cancelMove("alice", uniqueKey("CMD"), uniqueKey("NOPE")).getResponse().getStatus())
                .isEqualTo(404);
        MvcResult inventory = mockMvc
                .perform(get("/api/locations/{code}/inventory", uniqueKey("NOPE")))
                .andReturn();
        assertThat(inventory.getResponse().getStatus()).isEqualTo(404);
    }
}
