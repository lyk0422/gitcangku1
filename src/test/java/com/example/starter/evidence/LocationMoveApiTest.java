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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证物位置封签 API 测试（真实 H2 内存库，MODE=MySQL）：
 * 库位建档/停用/库存、上架、位置迁移申请与逐项 422、双人确认、集合原子迁移、
 * 封签快照、撤销语义、409 冲突、幂等重放与失败不占键。
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

    private MvcResult post(String path, String actor, Object body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult get(String path) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(path)).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ---------- 基础数据构造 ----------

    private String intake(String actor) throws Exception {
        String evidenceKey = uniqueKey("EV");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", uniqueKey("SEAL"));
        MvcResult result = post("/api/evidence", actor, body);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return evidenceKey;
    }

    private String createLocation(String actor) throws Exception {
        String code = uniqueKey("LOC");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("locationCode", code);
        body.put("description", "库位-" + code);
        MvcResult result = post("/api/locations", actor, body);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return code;
    }

    private void shelve(String actor, String evidenceKey, String locationCode) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("locationCode", locationCode);
        MvcResult result = post("/api/evidence/" + evidenceKey + "/shelve", actor, body);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private void breakSeal(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", false);
        body.put("note", "封条破损");
        MvcResult result = post("/api/evidence/" + evidenceKey + "/seal-inspections", actor, body);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private void initiateTransfer(String actor, String evidenceKey, String toCustodian) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", toCustodian);
        MvcResult result = post("/api/evidence/" + evidenceKey + "/transfers", actor, body);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private void acceptTransfer(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        MvcResult result = post("/api/evidence/" + evidenceKey + "/transfers/accept", actor, body);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private void borrow(String custodian, String evidenceKey) throws Exception {
        activeLoanKey(custodian, evidenceKey);
    }

    private void returnLoan(String custodian, String evidenceKey, String loanKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", loanKey);
        body.put("sealIntact", true);
        body.put("note", "封条完好归还");
        MvcResult result = post("/api/evidence/" + evidenceKey + "/loans/return", custodian, body);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private String activeLoanKey(String custodian, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        String loanKey = uniqueKey("LOAN");
        body.put("loanKey", loanKey);
        body.put("borrowerId", "borrower-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("purpose", "鉴定");
        body.put("dueAt", LocalDateTime.now().plusHours(2).toString());
        MvcResult result = post("/api/evidence/" + evidenceKey + "/loans", custodian, body);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return loanKey;
    }

    private Map<String, Object> moveBody(String commandKey, String moveKey, List<String> keys,
                                         String source, String target, long expectedVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("moveKey", moveKey);
        body.put("evidenceKeys", keys);
        body.put("sourceLocationCode", source);
        body.put("targetLocationCode", target);
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private MvcResult createMove(String actor, String moveKey, List<String> keys,
                                 String source, String target, long expectedVersion) throws Exception {
        return post("/api/location-moves", actor,
                moveBody(uniqueKey("CMD"), moveKey, keys, source, target, expectedVersion));
    }

    private MvcResult moveAction(String actor, String moveKey, String action) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        return post("/api/location-moves/" + moveKey + "/" + action, actor, body);
    }

    private MvcResult moveAction(String actor, String moveKey, String action,
                                 String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return post("/api/location-moves/" + moveKey + "/" + action, actor, body);
    }

    private JsonNode stock(String locationCode) throws Exception {
        MvcResult result = get("/api/locations/" + locationCode + "/stock");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result);
    }

    private JsonNode moveView(String moveKey) throws Exception {
        MvcResult result = get("/api/location-moves/" + moveKey);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result);
    }

    private JsonNode snapshots(String moveKey) throws Exception {
        MvcResult result = get("/api/location-moves/" + moveKey + "/snapshots");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result);
    }

    /**
     * 构造两件启用库位与 n 件由 alice 入库并上架到源库位的证物（上架后版本为 1）。
     */
    private List<String> prepareStock(String source, String target, int n) throws Exception {
        createLocationAt("alice", source);
        createLocationAt("alice", target);
        List<String> keys = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            String key = intake("alice");
            shelve("alice", key, source);
            keys.add(key);
        }
        return keys;
    }

    private void createLocationAt(String actor, String code) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("locationCode", code);
        MvcResult result = post("/api/locations", actor, body);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    // ---------- 库位与上架 ----------

    @Test
    void locationCreateDuplicateAndStockQuery() throws Exception {
        String code = createLocation("alice");
        assertThat(stock(code).get("items")).isEmpty();

        // 重复建档 409
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("locationCode", code);
        assertThat(post("/api/locations", "alice", body).getResponse().getStatus()).isEqualTo(409);

        String ev = intake("alice");
        shelve("alice", ev, code);
        JsonNode stock = stock(code);
        assertThat(stock.get("location").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(stock.get("items")).hasSize(1);
        assertThat(stock.get("items").get(0).get("evidenceKey").asText()).isEqualTo(ev);
        assertThat(stock.get("items").get(0).get("locationCode").asText()).isEqualTo(code);
        assertThat(stock.get("items").get(0).get("version").asLong()).isEqualTo(1);
    }

    @Test
    void shelveRejectsNonCustodianBrokenSealAlreadyShelvedAndDisabledTarget() throws Exception {
        String locA = createLocation("alice");
        String locB = createLocation("alice");

        String evOther = intake("alice");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("locationCode", locA);
        // 非保管人上架 409
        assertThat(post("/api/evidence/" + evOther + "/shelve", "mallory", body)
                .getResponse().getStatus()).isEqualTo(409);

        // 封条异常上架 409
        String evBroken = intake("alice");
        breakSeal("alice", evBroken);
        body.put("commandKey", uniqueKey("CMD"));
        assertThat(post("/api/evidence/" + evBroken + "/shelve", "alice", body)
                .getResponse().getStatus()).isEqualTo(409);

        // 停用库位上架 409
        String evDisabled = intake("alice");
        Map<String, Object> disableBody = new LinkedHashMap<>();
        disableBody.put("commandKey", uniqueKey("CMD"));
        assertThat(post("/api/locations/" + locB + "/disable", "alice", disableBody)
                .getResponse().getStatus()).isEqualTo(200);
        body.put("commandKey", uniqueKey("CMD"));
        body.put("locationCode", locB);
        assertThat(post("/api/evidence/" + evDisabled + "/shelve", "alice", body)
                .getResponse().getStatus()).isEqualTo(409);

        // 已上架证物重复上架（改位）409，必须走迁移
        shelve("alice", evOther, locA);
        body.put("commandKey", uniqueKey("CMD"));
        body.put("locationCode", locA);
        assertThat(post("/api/evidence/" + evOther + "/shelve", "alice", body)
                .getResponse().getStatus()).isEqualTo(409);
    }

    // ---------- 主流程：双人确认 + 原子迁移 + 封签快照 ----------

    @Test
    void twoDifferentCustodiansMoveWholeSetAtomicallyAndWriteSnapshots() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");
        List<String> keys = prepareStock(locA, locB, 3);

        String moveKey = uniqueKey("MOVE");
        MvcResult created = createMove("alice", moveKey, keys, locA, locB, 1);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode createdView = json(created);
        assertThat(createdView.get("status").asText()).isEqualTo("AWAITING_FIRST");
        List<String> frozenKeys = new java.util.ArrayList<>();
        createdView.get("evidenceKeys").forEach(node -> frozenKeys.add(node.asText()));
        assertThat(frozenKeys).containsExactlyElementsOf(keys.stream().sorted().toList());

        MvcResult first = moveAction("alice", moveKey, "confirm-first");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(first).get("status").asText()).isEqualTo("AWAITING_SECOND");
        assertThat(json(first).get("firstConfirmer").asText()).isEqualTo("alice");

        // 同一保管人不能做第二确认
        assertThat(moveAction("alice", moveKey, "confirm-second")
                .getResponse().getStatus()).isEqualTo(400);

        MvcResult second = moveAction("bob", moveKey, "confirm-second");
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        JsonNode completed = json(second);
        assertThat(completed.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.get("firstConfirmer").asText()).isEqualTo("alice");
        assertThat(completed.get("secondConfirmer").asText()).isEqualTo("bob");
        assertThat(completed.get("completedAt").isNull()).isFalse();

        // 源库位清空、目标库位 3 件、版本各加 1
        assertThat(stock(locA).get("items")).isEmpty();
        JsonNode targetStock = stock(locB).get("items");
        assertThat(targetStock).hasSize(3);
        for (var item : targetStock) {
            assertThat(item.get("locationCode").asText()).isEqualTo(locB);
            assertThat(item.get("status").asText()).isEqualTo("SEALED");
            assertThat(item.get("version").asLong()).isEqualTo(2);
        }

        // 逐件封签快照：迁移前库位与版本、双人保管人、SEALED
        JsonNode snaps = snapshots(moveKey);
        assertThat(snaps).hasSize(3);
        for (var snap : snaps) {
            assertThat(snap.get("locationCode").asText()).isEqualTo(locA);
            assertThat(snap.get("version").asLong()).isEqualTo(1);
            assertThat(snap.get("evidenceStatus").asText()).isEqualTo("SEALED");
            assertThat(snap.get("firstConfirmer").asText()).isEqualTo("alice");
            assertThat(snap.get("secondConfirmer").asText()).isEqualTo("bob");
            assertThat(snap.get("custodianId").asText()).isEqualTo("alice");
            assertThat(snap.get("snapshotAt").isNull()).isFalse();
        }
    }

    @Test
    void completedMoveCannotBeConfirmedOrCancelled() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");
        List<String> keys = prepareStock(locA, locB, 1);
        String moveKey = uniqueKey("MOVE");
        createMove("alice", moveKey, keys, locA, locB, 1);
        moveAction("alice", moveKey, "confirm-first");
        assertThat(moveAction("bob", moveKey, "confirm-second").getResponse().getStatus())
                .isEqualTo(200);

        assertThat(moveAction("alice", moveKey, "confirm-first").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(moveAction("carol", moveKey, "confirm-second").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(moveAction("alice", moveKey, "cancel").getResponse().getStatus())
                .isEqualTo(409);
    }

    // ---------- 申请前置条件：逐项 422 ----------

    @Test
    void createMoveWithBadItemsReturns422ItemizedAndCreatesNothing() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");
        createLocationAt("alice", locA);
        createLocationAt("alice", locB);

        String good = intake("alice");
        shelve("alice", good, locA);

        String broken = intake("alice");
        shelve("alice", broken, locA);
        breakSeal("alice", broken);

        String elsewhere = intake("alice");
        shelve("alice", elsewhere, locB);

        // 通过一次交接接受改变版本但仍 SEALED 在 locA
        String drifted = intake("alice");
        shelve("alice", drifted, locA);
        initiateTransfer("alice", drifted, "bob");
        acceptTransfer("bob", drifted);

        String missing = uniqueKey("EV");
        List<String> set = List.of(good, broken, elsewhere, drifted, missing);
        String moveKey = uniqueKey("MOVE");
        MvcResult result = createMove("alice", moveKey, set, locA, locB, 1);

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        JsonNode body = json(result);
        assertThat(body.get("status").asInt()).isEqualTo(422);
        JsonNode itemErrors = body.get("itemErrors");
        assertThat(itemErrors).hasSize(4);
        Map<String, String> reasons = new LinkedHashMap<>();
        itemErrors.forEach(node -> reasons.put(node.get("evidenceKey").asText(),
                node.get("reason").asText()));
        assertThat(reasons).containsOnlyKeys(broken, elsewhere, drifted, missing);
        assertThat(reasons.get(broken)).contains("封条");
        assertThat(reasons.get(elsewhere)).contains(locB).contains(locA);
        assertThat(reasons.get(drifted)).contains("版本");
        assertThat(reasons.get(missing)).contains("不存在");

        // 整单未创建，合格证物也未移动；封条异常件与交接后版本漂移件库位均不变，仍在源库位
        MvcResult getMove = get("/api/location-moves/" + moveKey);
        assertThat(getMove.getResponse().getStatus()).isEqualTo(404);
        assertThat(stock(locA).get("items")).hasSize(3);
        assertThat(stock(locB).get("items")).hasSize(1);
    }

    @Test
    void createMoveWithSameSourceAndTargetReturns400() throws Exception {
        String locA = uniqueKey("LOC");
        List<String> keys = prepareStock(locA, uniqueKey("LOC"), 1);
        // 复用 locA 同时作为目标
        MvcResult result = createMove("alice", uniqueKey("MOVE"), keys, locA, locA, 1);
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void applyingWhileBorrowedOrPendingReturns422Itemized() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");
        createLocationAt("alice", locA);
        createLocationAt("alice", locB);

        String borrowed = intake("alice");
        shelve("alice", borrowed, locA);
        borrow("alice", borrowed);

        String pending = intake("alice");
        shelve("alice", pending, locA);
        initiateTransfer("alice", pending, "carol");

        MvcResult result = createMove("alice", uniqueKey("MOVE"), List.of(borrowed, pending),
                locA, locB, 1);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        JsonNode itemErrors = json(result).get("itemErrors");
        assertThat(itemErrors).hasSize(2);
        Map<String, String> reasons = new LinkedHashMap<>();
        itemErrors.forEach(node -> reasons.put(node.get("evidenceKey").asText(),
                node.get("reason").asText()));
        assertThat(reasons.get(borrowed)).contains("借出");
        assertThat(reasons.get(pending)).contains("待核验");
    }

    @Test
    void preconditionDriftBetweenApplicationAndFirstConfirmReturns422() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");
        List<String> keys = prepareStock(locA, locB, 1);
        String moveKey = uniqueKey("MOVE");
        createMove("alice", moveKey, keys, locA, locB, 1);

        // 申请后、首人确认前，证物版本漂移（交接接受）
        initiateTransfer("alice", keys.get(0), "bob");
        acceptTransfer("bob", keys.get(0));

        MvcResult result = moveAction("bob", moveKey, "confirm-first");
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(stock(locA).get("items")).hasSize(1);
    }

    // ---------- 409 冲突分支 ----------

    @Test
    void disabledTargetDuringConfirmationWindowReturns409() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");
        List<String> keys = prepareStock(locA, locB, 1);
        String moveKey = uniqueKey("MOVE");
        createMove("alice", moveKey, keys, locA, locB, 1);
        assertThat(moveAction("alice", moveKey, "confirm-first").getResponse().getStatus())
                .isEqualTo(200);

        Map<String, Object> disableBody = new LinkedHashMap<>();
        disableBody.put("commandKey", uniqueKey("CMD"));
        assertThat(post("/api/locations/" + locB + "/disable", "alice", disableBody)
                .getResponse().getStatus()).isEqualTo(200);

        assertThat(moveAction("bob", moveKey, "confirm-second").getResponse().getStatus())
                .isEqualTo(409);
        // 单据仍待第二人，库位未变
        assertThat(moveView(moveKey).get("status").asText()).isEqualTo("AWAITING_SECOND");
        assertThat(stock(locA).get("items")).hasSize(1);
        assertThat(stock(locB).get("items")).isEmpty();
        assertThat(snapshots(moveKey)).isEmpty();
    }

    @Test
    void borrowedOrPendingEvidenceDuringWindowReturns409() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");

        // 借出场景
        List<String> borrowedSet = prepareStock(locA, locB, 1);
        String moveBorrowed = uniqueKey("MOVE");
        createMove("alice", moveBorrowed, borrowedSet, locA, locB, 1);
        moveAction("alice", moveBorrowed, "confirm-first");
        borrow("alice", borrowedSet.get(0));
        assertThat(moveAction("bob", moveBorrowed, "confirm-second").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(snapshots(moveBorrowed)).isEmpty();

        // 待核验（TRANSFER_PENDING）场景：复用新库位避免干扰
        String locC = uniqueKey("LOC");
        String locD = uniqueKey("LOC");
        List<String> pendingSet = prepareStock(locC, locD, 1);
        String movePending = uniqueKey("MOVE");
        createMove("alice", movePending, pendingSet, locC, locD, 1);
        moveAction("alice", movePending, "confirm-first");
        initiateTransfer("alice", pendingSet.get(0), "carol");
        assertThat(moveAction("bob", movePending, "confirm-second").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(snapshots(movePending)).isEmpty();
    }

    @Test
    void firstConfirmStateGuards() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");
        List<String> keys = prepareStock(locA, locB, 1);
        String moveKey = uniqueKey("MOVE");
        createMove("alice", moveKey, keys, locA, locB, 1);

        // 未首人确认不能第二人确认、不能撤销
        assertThat(moveAction("bob", moveKey, "confirm-second").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(moveAction("alice", moveKey, "cancel").getResponse().getStatus()).isEqualTo(409);

        // 首人确认重复提交 409
        assertThat(moveAction("alice", moveKey, "confirm-first").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(moveAction("alice", moveKey, "confirm-first").getResponse().getStatus())
                .isEqualTo(409);
    }

    // ---------- 撤销语义 ----------

    @Test
    void cancelAfterFirstBlocksFurtherConfirmationsAndKeepsHistory() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");
        List<String> keys = prepareStock(locA, locB, 2);
        String moveKey = uniqueKey("MOVE");
        createMove("alice", moveKey, keys, locA, locB, 1);
        moveAction("alice", moveKey, "confirm-first");

        // 非首人不能撤销
        assertThat(moveAction("bob", moveKey, "cancel").getResponse().getStatus()).isEqualTo(409);

        MvcResult cancelled = moveAction("alice", moveKey, "cancel");
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
        JsonNode view = json(cancelled);
        assertThat(view.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(view.get("cancelledAt").isNull()).isFalse();

        // 撤销后首人/第二人确认均 409，重复撤销 409
        assertThat(moveAction("alice", moveKey, "confirm-first").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(moveAction("bob", moveKey, "confirm-second").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(moveAction("alice", moveKey, "cancel").getResponse().getStatus()).isEqualTo(409);

        // 历史迁移单可查、逐件快照为空、库位与版本不变
        assertThat(moveView(moveKey).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(snapshots(moveKey)).isEmpty();
        JsonNode items = stock(locA).get("items");
        assertThat(items).hasSize(2);
        for (var item : items) {
            assertThat(item.get("version").asLong()).isEqualTo(1);
        }
    }

    // ---------- 第二人确认失败整单回滚 ----------

    @Test
    void secondConfirmRollsBackAtomicallyWhenVersionDrifted() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");
        List<String> keys = prepareStock(locA, locB, 2);
        String moveKey = uniqueKey("MOVE");
        createMove("alice", moveKey, keys, locA, locB, 1);
        moveAction("alice", moveKey, "confirm-first");

        // 窗口内对其中一件借出并完好归还：状态恢复 SEALED 仍在 locA，但版本已变化
        String loanKey = activeLoanKey("alice", keys.get(0));
        returnLoan("alice", keys.get(0), loanKey);

        MvcResult result = moveAction("bob", moveKey, "confirm-second");
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(result).get("itemErrors")).hasSize(1);

        // 整单回滚：两件证物都仍在源库位、无任何快照、单据未完成
        assertThat(stock(locA).get("items")).hasSize(2);
        assertThat(stock(locB).get("items")).isEmpty();
        assertThat(snapshots(moveKey)).isEmpty();
        assertThat(moveView(moveKey).get("status").asText()).isEqualTo("AWAITING_SECOND");

        // 回滚后仍可撤销
        assertThat(moveAction("alice", moveKey, "cancel").getResponse().getStatus()).isEqualTo(200);
    }

    // ---------- 幂等 ----------

    @Test
    void completedConfirmReplaysSameResultAndWritesSnapshotsOnce() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");
        List<String> keys = prepareStock(locA, locB, 2);
        String moveKey = uniqueKey("MOVE");
        createMove("alice", moveKey, keys, locA, locB, 1);
        moveAction("alice", moveKey, "confirm-first");

        String commandKey = uniqueKey("CMD");
        MvcResult first = moveAction("bob", moveKey, "confirm-second", commandKey);
        MvcResult replay = moveAction("bob", moveKey, "confirm-second", commandKey);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        assertThat(snapshots(moveKey)).hasSize(2);
        for (var item : stock(locB).get("items")) {
            assertThat(item.get("version").asLong()).isEqualTo(2);
        }
    }

    @Test
    void failedConfirmationDoesNotOccupyCommandKey() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");
        List<String> keys = prepareStock(locA, locB, 1);
        String moveKey = uniqueKey("MOVE");
        createMove("alice", moveKey, keys, locA, locB, 1);
        moveAction("alice", moveKey, "confirm-first");

        // 同一保管人第二确认 400（失败），该 commandKey 不被占用
        String commandKey = uniqueKey("CMD");
        assertThat(moveAction("alice", moveKey, "confirm-second", commandKey)
                .getResponse().getStatus()).isEqualTo(400);
        // 换用合规的第二人复用同一 commandKey 仍可成功
        MvcResult success = moveAction("bob", moveKey, "confirm-second", commandKey);
        assertThat(success.getResponse().getStatus()).isEqualTo(200);
        assertThat(moveView(moveKey).get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void reorderedSetWithSameCommandKeyReplaysApplication() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");
        List<String> keys = prepareStock(locA, locB, 3);
        String moveKey = uniqueKey("MOVE");
        String commandKey = uniqueKey("CMD");

        List<String> reordered = new java.util.ArrayList<>(keys);
        java.util.Collections.reverse(reordered);
        MvcResult first = post("/api/location-moves", "alice",
                moveBody(commandKey, moveKey, reordered, locA, locB, 1));
        MvcResult replay = post("/api/location-moves", "alice",
                moveBody(commandKey, moveKey, keys, locA, locB, 1));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 集合落库按字典序规范化
        assertThat(moveView(moveKey).get("evidenceKeys"))
                .hasSize(3);
    }

    @Test
    void sameCommandKeyDifferentParamsReturns409() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");
        prepareStock(locA, locB, 1);
        String commandKey = uniqueKey("CMD");
        MvcResult first = post("/api/location-moves", "alice",
                moveBody(commandKey, uniqueKey("MOVE"), List.of(intakeAdditional(locA)),
                        locA, locB, 1));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        // 改 expectedVersion 同键 → 409
        MvcResult changed = post("/api/location-moves", "alice",
                moveBody(commandKey, uniqueKey("MOVE"), List.of(intakeAdditional(locA)),
                        locA, locB, 2));
        assertThat(changed.getResponse().getStatus()).isEqualTo(409);
    }

    private String intakeAdditional(String locA) throws Exception {
        String key = intake("alice");
        shelve("alice", key, locA);
        return key;
    }

    // ---------- 保管链联动 ----------

    @Test
    void custodyChainContainsMoveSnapshots() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");
        List<String> keys = prepareStock(locA, locB, 1);
        String moveKey = uniqueKey("MOVE");
        createMove("alice", moveKey, keys, locA, locB, 1);
        moveAction("alice", moveKey, "confirm-first");
        moveAction("bob", moveKey, "confirm-second");

        MvcResult chainResult = get("/api/evidence/" + keys.get(0) + "/custody-chain");
        assertThat(chainResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode chain = json(chainResult);
        assertThat(chain.get("sealSnapshots")).hasSize(1);
        assertThat(chain.get("sealSnapshots").get(0).get("moveKey").asText()).isEqualTo(moveKey);
        assertThat(chain.get("evidence").get("locationCode").asText()).isEqualTo(locB);
    }
}
