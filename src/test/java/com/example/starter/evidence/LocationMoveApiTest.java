package com.example.starter.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 库位迁移与双人封签核验 API 主流程与失败分支测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LocationMoveApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private MvcResult postJson(String url, String actor, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(url)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult createLocation(String actor, String commandKey, String locationCode)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("locationCode", locationCode);
        body.put("label", "货架 " + locationCode);
        return postJson("/api/locations", actor, body);
    }

    private MvcResult disableLocation(String actor, String locationCode, String commandKey)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return postJson("/api/locations/" + locationCode + "/disable", actor, body);
    }

    private MvcResult intake(String actor, String commandKey, String evidenceKey,
                             String locationCode) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-" + evidenceKey);
        if (locationCode != null) {
            body.put("locationCode", locationCode);
        }
        return postJson("/api/evidence", actor, body);
    }

    private JsonNode inventory(String locationCode) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/locations/{code}/inventory", locationCode))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return body(result);
    }

    private int locationVersion(String locationCode) throws Exception {
        return inventory(locationCode).get("version").asInt();
    }

    private MvcResult applyMove(String actor, String moveKey, List<String> evidenceKeys,
                                String source, String target, int expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("moveKey", moveKey);
        body.put("evidenceKeys", evidenceKeys);
        body.put("sourceLocation", source);
        body.put("targetLocation", target);
        body.put("expectedVersion", expectedVersion);
        return postJson("/api/evidence-moves", actor, body);
    }

    private MvcResult confirm(String actor, String moveKey, String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return postJson("/api/evidence-moves/" + moveKey + "/confirm", actor, body);
    }

    private MvcResult cancel(String actor, String moveKey, String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return postJson("/api/evidence-moves/" + moveKey + "/cancel", actor, body);
    }

    private JsonNode getMove(String moveKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence-moves/{key}", moveKey)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return body(result);
    }

    private JsonNode getSnapshots(String moveKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence-moves/{key}/seal-snapshots", moveKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return body(result);
    }

    private MvcResult borrow(String actor, String evidenceKey, String commandKey, String loanKey)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("loanKey", loanKey);
        body.put("borrowerId", "borrower-1");
        body.put("purpose", "court");
        body.put("dueAt", LocalDateTime.now().plusHours(1).withNano(0).toString());
        return postJson("/api/evidence/" + evidenceKey + "/loans", actor, body);
    }

    private MvcResult returnLoan(String actor, String evidenceKey, String commandKey,
                                 String loanKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("loanKey", loanKey);
        body.put("sealIntact", true);
        body.put("note", "returned");
        return postJson("/api/evidence/" + evidenceKey + "/loans/return", actor, body);
    }

    private MvcResult inspect(String actor, String evidenceKey, String commandKey, boolean passed)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("passed", passed);
        body.put("note", "routine");
        return postJson("/api/evidence/" + evidenceKey + "/seal-inspections", actor, body);
    }

    /**
     * 准备源/目标库位与两件位于源库位的证物，返回 [source, target, ev1, ev2]。
     */
    private String[] prepareMovablePair() throws Exception {
        String source = uniqueKey("LOC-S");
        String target = uniqueKey("LOC-T");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        assertThat(createLocation("ops", uniqueKey("CMD"), source).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(createLocation("ops", uniqueKey("CMD"), target).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(intake("alice", uniqueKey("CMD"), ev1, source).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(intake("alice", uniqueKey("CMD"), ev2, source).getResponse().getStatus())
                .isEqualTo(201);
        return new String[]{source, target, ev1, ev2};
    }

    @Test
    void createLocationAndIntakePlacementShowsInInventory() throws Exception {
        String location = uniqueKey("LOC");
        MvcResult created = createLocation("ops", uniqueKey("CMD"), location);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(created).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body(created).get("version").asInt()).isEqualTo(0);

        String ev = uniqueKey("EV");
        MvcResult intake = intake("alice", uniqueKey("CMD"), ev, location);
        assertThat(intake.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(intake).get("locationCode").asText()).isEqualTo(location);

        JsonNode inventory = inventory(location);
        assertThat(inventory.get("version").asInt()).isEqualTo(1);
        assertThat(inventory.get("evidence")).hasSize(1);
        assertThat(inventory.get("evidence").get(0).get("evidenceKey").asText()).isEqualTo(ev);
    }

    @Test
    void intakeWithoutLocationKeepsNullLocation() throws Exception {
        String ev = uniqueKey("EV");
        MvcResult intake = intake("alice", uniqueKey("CMD"), ev, null);
        assertThat(intake.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(intake).get("locationCode").isNull()).isTrue();
    }

    @Test
    void createLocationDuplicateReturns409AndSameKeyReplays() throws Exception {
        String location = uniqueKey("LOC");
        String commandKey = uniqueKey("CMD");
        MvcResult first = createLocation("ops", commandKey, location);
        MvcResult replay = createLocation("ops", commandKey, location);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(createLocation("ops", uniqueKey("CMD"), location).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void disableLocationBlocksIntakeAndIsIdempotent() throws Exception {
        String location = uniqueKey("LOC");
        createLocation("ops", uniqueKey("CMD"), location);

        MvcResult disabled = disableLocation("ops", location, uniqueKey("CMD"));
        assertThat(disabled.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(disabled).get("status").asText()).isEqualTo("DISABLED");

        // 重复停用 409；停用后禁止放入证物 409
        assertThat(disableLocation("ops", location, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(intake("alice", uniqueKey("CMD"), uniqueKey("EV"), location)
                .getResponse().getStatus()).isEqualTo(409);
        // 停用不改写历史库存
        assertThat(inventory(location).get("status").asText()).isEqualTo("DISABLED");
    }

    @Test
    void intakeWithUnknownLocationReturns404() throws Exception {
        assertThat(intake("alice", uniqueKey("CMD"), uniqueKey("EV"), uniqueKey("LOC"))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void inventoryOfUnknownLocationReturns404() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/locations/{code}/inventory", uniqueKey("LOC")))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void applyCreatesPendingMoveWithNormalizedSet() throws Exception {
        String[] ctx = prepareMovablePair();
        String moveKey = uniqueKey("MOVE");

        MvcResult applied = applyMove("alice", moveKey,
                List.of(ctx[3], ctx[2], ctx[3]), ctx[0], ctx[1], 2);
        assertThat(applied.getResponse().getStatus()).isEqualTo(201);
        JsonNode move = body(applied);
        assertThat(move.get("status").asText()).isEqualTo("PENDING");
        assertThat(move.get("applicantId").asText()).isEqualTo("alice");
        // 集合规范化：去重并升序
        List<String> keys = new ArrayList<>();
        move.get("evidenceKeys").forEach(node -> keys.add(node.asText()));
        assertThat(keys).containsExactlyInAnyOrder(ctx[2], ctx[3]);
        assertThat(keys).isSorted();
        assertThat(move.get("snapshots")).isEmpty();
    }

    @Test
    void applyReorderedSetWithSameMoveKeyReplaysAndDoesNotOccupyOnFailure() throws Exception {
        String[] ctx = prepareMovablePair();
        String moveKey = uniqueKey("MOVE");

        // 失败不占键：版本不匹配 409 后同键可继续正确使用
        assertThat(applyMove("alice", moveKey, List.of(ctx[2], ctx[3]), ctx[0], ctx[1], 99)
                .getResponse().getStatus()).isEqualTo(409);
        MvcResult applied = applyMove("alice", moveKey, List.of(ctx[3], ctx[2]), ctx[0], ctx[1], 2);
        assertThat(applied.getResponse().getStatus()).isEqualTo(201);

        // 集合换序视为同参：重放完整迁移结果
        MvcResult replay = applyMove("alice", moveKey, List.of(ctx[2], ctx[3]), ctx[0], ctx[1], 2);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(replay).get("moveKey").asText()).isEqualTo(moveKey);
        assertThat(body(replay).get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void applySameMoveKeyWithDifferentParamsReturns409() throws Exception {
        String[] ctx = prepareMovablePair();
        String moveKey = uniqueKey("MOVE");
        assertThat(applyMove("alice", moveKey, List.of(ctx[2]), ctx[0], ctx[1], 2)
                .getResponse().getStatus()).isEqualTo(201);
        // 同键改参（证物集合不同）
        assertThat(applyMove("alice", moveKey, List.of(ctx[2], ctx[3]), ctx[0], ctx[1], 2)
                .getResponse().getStatus()).isEqualTo(409);
        // 同键改参（操作者不同）
        assertThat(applyMove("bob", moveKey, List.of(ctx[2]), ctx[0], ctx[1], 2)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void applyToDisabledOrUnknownTargetFails() throws Exception {
        String[] ctx = prepareMovablePair();
        disableLocation("ops", ctx[1], uniqueKey("CMD"));
        assertThat(applyMove("alice", uniqueKey("MOVE"), List.of(ctx[2]), ctx[0], ctx[1], 2)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(applyMove("alice", uniqueKey("MOVE"), List.of(ctx[2]), ctx[0],
                uniqueKey("LOC"), 2).getResponse().getStatus()).isEqualTo(404);
        assertThat(applyMove("alice", uniqueKey("MOVE"), List.of(ctx[2]), uniqueKey("LOC"),
                ctx[1], 0).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void applyWithSameSourceAndTargetReturns400() throws Exception {
        String[] ctx = prepareMovablePair();
        assertThat(applyMove("alice", uniqueKey("MOVE"), List.of(ctx[2]), ctx[0], ctx[0], 2)
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void applyValidationReturns422WithPerItemReasons() throws Exception {
        String source = uniqueKey("LOC-S");
        String target = uniqueKey("LOC-T");
        String other = uniqueKey("LOC-O");
        createLocation("ops", uniqueKey("CMD"), source);
        createLocation("ops", uniqueKey("CMD"), target);
        createLocation("ops", uniqueKey("CMD"), other);
        String borrowed = uniqueKey("EV");
        String broken = uniqueKey("EV");
        String misplaced = uniqueKey("EV");
        String ok = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), borrowed, source);
        intake("alice", uniqueKey("CMD"), broken, source);
        intake("alice", uniqueKey("CMD"), misplaced, other);
        intake("alice", uniqueKey("CMD"), ok, source);
        borrow("alice", borrowed, uniqueKey("CMD"), uniqueKey("LOAN"));
        inspect("alice", broken, uniqueKey("CMD"), false);
        String missing = uniqueKey("EV");

        String moveKey = uniqueKey("MOVE");
        // 源库位放入 3 件（misplaced 放在 other 库位），库存版本为 3
        MvcResult result = applyMove("alice", moveKey,
                List.of(borrowed, broken, misplaced, missing, ok), source, target, 3);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        JsonNode error = body(result);
        assertThat(error.get("items")).hasSize(4);
        Map<String, String> reasons = new LinkedHashMap<>();
        error.get("items").forEach(item ->
                reasons.put(item.get("evidenceKey").asText(), item.get("reason").asText()));
        assertThat(reasons.keySet()).containsExactlyInAnyOrder(borrowed, broken, misplaced, missing);
        assertThat(reasons.get(borrowed)).contains("借出");
        assertThat(reasons.get(broken)).contains("封条异常");
        assertThat(reasons.get(misplaced)).contains("库位");
        assertThat(reasons.get(missing)).contains("不存在");

        // 失败不占键：修正集合后同键申请成功
        MvcResult applied = applyMove("alice", moveKey, List.of(ok), source, target, 3);
        assertThat(applied.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void dualConfirmMigratesAtomicallyAndWritesSnapshots() throws Exception {
        String[] ctx = prepareMovablePair();
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, List.of(ctx[2], ctx[3]), ctx[0], ctx[1], 2);

        MvcResult first = confirm("carol", moveKey, uniqueKey("CMD"));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(first).get("status").asText()).isEqualTo("FIRST_CONFIRMED");
        assertThat(body(first).get("firstConfirmer").asText()).isEqualTo("carol");
        // 首人确认后证物仍未迁移
        assertThat(inventory(ctx[0]).get("evidence")).hasSize(2);

        MvcResult second = confirm("dave", moveKey, uniqueKey("CMD"));
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        JsonNode move = body(second);
        assertThat(move.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(move.get("secondConfirmer").asText()).isEqualTo("dave");
        assertThat(move.get("decidedAt").isNull()).isFalse();
        assertThat(move.get("snapshots")).hasSize(2);

        // 全部证物原子迁移，源/目标库位库存版本各 +1
        JsonNode sourceInventory = inventory(ctx[0]);
        assertThat(sourceInventory.get("evidence")).isEmpty();
        assertThat(sourceInventory.get("version").asInt()).isEqualTo(3);
        JsonNode targetInventory = inventory(ctx[1]);
        assertThat(targetInventory.get("evidence")).hasSize(2);
        assertThat(targetInventory.get("version").asInt()).isEqualTo(1);

        // 逐件封签快照：封条编号、源目标库位与双人确认人
        JsonNode snapshots = getSnapshots(moveKey);
        assertThat(snapshots).hasSize(2);
        List<String> snapKeys = new ArrayList<>();
        for (JsonNode snapshot : snapshots) {
            assertThat(snapshot.get("fromLocation").asText()).isEqualTo(ctx[0]);
            assertThat(snapshot.get("toLocation").asText()).isEqualTo(ctx[1]);
            assertThat(snapshot.get("firstConfirmer").asText()).isEqualTo("carol");
            assertThat(snapshot.get("secondConfirmer").asText()).isEqualTo("dave");
            snapKeys.add(snapshot.get("evidenceKey").asText());
        }
        assertThat(snapKeys).containsExactlyInAnyOrder(ctx[2], ctx[3]);
    }

    @Test
    void sameCustodianCannotConfirmTwice() throws Exception {
        String[] ctx = prepareMovablePair();
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, List.of(ctx[2]), ctx[0], ctx[1], 2);
        confirm("carol", moveKey, uniqueKey("CMD"));
        assertThat(confirm("carol", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(getMove(moveKey).get("status").asText()).isEqualTo("FIRST_CONFIRMED");
    }

    @Test
    void confirmReplayReturnsFirstResultWithoutDoubleProcessing() throws Exception {
        String[] ctx = prepareMovablePair();
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, List.of(ctx[2]), ctx[0], ctx[1], 2);
        String commandKey = uniqueKey("CMD");
        MvcResult first = confirm("carol", moveKey, commandKey);
        MvcResult replay = confirm("carol", moveKey, commandKey);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(getMove(moveKey).get("status").asText()).isEqualTo("FIRST_CONFIRMED");
    }

    @Test
    void confirmOnMissingMoveReturns404() throws Exception {
        assertThat(confirm("carol", uniqueKey("MOVE"), uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(cancel("carol", uniqueKey("MOVE"), uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(404);
        MvcResult get = mockMvc.perform(get("/api/evidence-moves/{key}", uniqueKey("MOVE")))
                .andReturn();
        assertThat(get.getResponse().getStatus()).isEqualTo(404);
        MvcResult snapshots = mockMvc.perform(
                get("/api/evidence-moves/{key}/seal-snapshots", uniqueKey("MOVE"))).andReturn();
        assertThat(snapshots.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void cancelOnlyAfterFirstConfirmAndBlocksFurtherConfirm() throws Exception {
        String[] ctx = prepareMovablePair();
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, List.of(ctx[2], ctx[3]), ctx[0], ctx[1], 2);

        // 首人确认前不可撤销
        assertThat(cancel("alice", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        confirm("carol", moveKey, uniqueKey("CMD"));
        // 第三人（非申请人、非首确认人）不可撤销
        assertThat(cancel("mallory", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);

        MvcResult cancelled = cancel("alice", moveKey, uniqueKey("CMD"));
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(cancelled).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(body(cancelled).get("cancelledBy").asText()).isEqualTo("alice");

        // 撤销后不可再确认、不可再撤销；证物未迁移、无快照
        assertThat(confirm("dave", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(cancel("alice", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(inventory(ctx[0]).get("evidence")).hasSize(2);
        assertThat(getSnapshots(moveKey)).isEmpty();
    }

    @Test
    void completedMoveCannotBeCancelledOrConfirmedAgain() throws Exception {
        String[] ctx = prepareMovablePair();
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, List.of(ctx[2]), ctx[0], ctx[1], 2);
        confirm("carol", moveKey, uniqueKey("CMD"));
        confirm("dave", moveKey, uniqueKey("CMD"));

        assertThat(cancel("alice", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(confirm("erin", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void applyReplayAfterCompletionReturnsFullMigrationResult() throws Exception {
        String[] ctx = prepareMovablePair();
        String moveKey = uniqueKey("MOVE");
        List<String> keys = List.of(ctx[2], ctx[3]);
        applyMove("alice", moveKey, keys, ctx[0], ctx[1], 2);
        confirm("carol", moveKey, uniqueKey("CMD"));
        confirm("dave", moveKey, uniqueKey("CMD"));

        MvcResult replay = applyMove("alice", moveKey, keys, ctx[0], ctx[1], 2);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        JsonNode move = body(replay);
        assertThat(move.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(move.get("snapshots")).hasSize(2);
    }

    @Test
    void targetDisabledBetweenConfirmsReturns409AndKeepsOrderOpen() throws Exception {
        String[] ctx = prepareMovablePair();
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, List.of(ctx[2], ctx[3]), ctx[0], ctx[1], 2);
        confirm("carol", moveKey, uniqueKey("CMD"));
        disableLocation("ops", ctx[1], uniqueKey("CMD"));

        assertThat(confirm("dave", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        // 整单未执行：证物仍在源库位、无快照、迁移单仍可处理
        assertThat(inventory(ctx[0]).get("evidence")).hasSize(2);
        assertThat(getSnapshots(moveKey)).isEmpty();
        assertThat(getMove(moveKey).get("status").asText()).isEqualTo("FIRST_CONFIRMED");
    }

    @Test
    void sourceVersionChangeBetweenConfirmsReturns409() throws Exception {
        String[] ctx = prepareMovablePair();
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, List.of(ctx[2], ctx[3]), ctx[0], ctx[1], 2);
        confirm("carol", moveKey, uniqueKey("CMD"));
        // 首人确认后源库位库存变化（新证物放入），expectedVersion 失效
        intake("alice", uniqueKey("CMD"), uniqueKey("EV"), ctx[0]);

        assertThat(confirm("dave", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(inventory(ctx[0]).get("evidence")).hasSize(3);
        assertThat(getMove(moveKey).get("status").asText()).isEqualTo("FIRST_CONFIRMED");
    }

    @Test
    void borrowedEvidenceBetweenConfirmsRollsBackWholeOrder() throws Exception {
        String[] ctx = prepareMovablePair();
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, List.of(ctx[2], ctx[3]), ctx[0], ctx[1], 2);
        confirm("carol", moveKey, uniqueKey("CMD"));

        String loanKey = uniqueKey("LOAN");
        assertThat(borrow("alice", ctx[2], uniqueKey("CMD"), loanKey).getResponse().getStatus())
                .isEqualTo(200);
        // 任一证物借出：第二人确认 409，整单回滚（另一件证物也不迁移）
        assertThat(confirm("dave", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(inventory(ctx[0]).get("evidence")).hasSize(2);
        assertThat(inventory(ctx[1]).get("evidence")).isEmpty();
        assertThat(getSnapshots(moveKey)).isEmpty();

        // 归还后前置条件恢复，同一迁移单可继续完成
        assertThat(returnLoan("alice", ctx[2], uniqueKey("CMD"), loanKey).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(confirm("dave", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(getMove(moveKey).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(inventory(ctx[1]).get("evidence")).hasSize(2);
    }

    @Test
    void transferPendingEvidenceBetweenConfirmsReturns409() throws Exception {
        String[] ctx = prepareMovablePair();
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, List.of(ctx[2]), ctx[0], ctx[1], 2);
        confirm("carol", moveKey, uniqueKey("CMD"));

        Map<String, Object> transfer = new LinkedHashMap<>();
        transfer.put("commandKey", uniqueKey("CMD"));
        transfer.put("toCustodian", "bob");
        assertThat(postJson("/api/evidence/" + ctx[2] + "/transfers", "alice", transfer)
                .getResponse().getStatus()).isEqualTo(200);

        assertThat(confirm("dave", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        // 迁移单只含 ev1，ev1 未迁移且 ev2 本就不在单内，源库位仍有 2 件
        assertThat(inventory(ctx[0]).get("evidence")).hasSize(2);
        assertThat(getSnapshots(moveKey)).isEmpty();
    }

    @Test
    void sealBrokenBetweenConfirmsReturns409() throws Exception {
        String[] ctx = prepareMovablePair();
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, List.of(ctx[2]), ctx[0], ctx[1], 2);
        confirm("carol", moveKey, uniqueKey("CMD"));
        inspect("alice", ctx[2], uniqueKey("CMD"), false);

        assertThat(confirm("dave", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(inventory(ctx[0]).get("evidence")).hasSize(2);
        assertThat(getMove(moveKey).get("status").asText()).isEqualTo("FIRST_CONFIRMED");
    }

    @Test
    void moveQueryShowsFullLifecycle() throws Exception {
        String[] ctx = prepareMovablePair();
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, List.of(ctx[2]), ctx[0], ctx[1], 2);
        assertThat(getMove(moveKey).get("status").asText()).isEqualTo("PENDING");
        confirm("carol", moveKey, uniqueKey("CMD"));
        JsonNode first = getMove(moveKey);
        assertThat(first.get("status").asText()).isEqualTo("FIRST_CONFIRMED");
        assertThat(first.get("firstConfirmedAt").isNull()).isFalse();
        confirm("dave", moveKey, uniqueKey("CMD"));
        JsonNode completed = getMove(moveKey);
        assertThat(completed.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.get("expectedVersion").asInt()).isEqualTo(2);
        assertThat(completed.get("snapshots").get(0).get("evidenceKey").asText()).isEqualTo(ctx[2]);
    }
}
