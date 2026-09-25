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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 证物库位迁移与双人封签核验 API 主流程与失败分支测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MoveApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private MvcResult createLocation(String actor, String commandKey, String locationCode)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("locationCode", locationCode);
        body.put("description", "测试库位");
        return mockMvc.perform(post("/api/locations")
                        .header(ACTOR_HEADER, actor)
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

    private JsonNode inventory(String locationCode) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/locations/{code}/inventory", locationCode))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult intake(String actor, String commandKey, String evidenceKey,
                             String locationCode) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-" + evidenceKey.substring(0, 8));
        if (locationCode != null) {
            body.put("locationCode", locationCode);
        }
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult createMove(String actor, String moveKey, List<String> evidenceKeys,
                                 String source, String target, int expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("moveKey", moveKey);
        body.put("evidenceKeys", evidenceKeys);
        body.put("sourceLocation", source);
        body.put("targetLocation", target);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/moves")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult confirm(String actor, String moveKey, String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return mockMvc.perform(post("/api/moves/{key}/confirm", moveKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult cancel(String actor, String moveKey, String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return mockMvc.perform(post("/api/moves/{key}/cancel", moveKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode getMove(String moveKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/moves/{key}", moveKey)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getSnapshots(String moveKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/moves/{key}/snapshots", moveKey)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult borrow(String actor, String evidenceKey, String commandKey,
                             String loanKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("loanKey", loanKey);
        body.put("borrowerId", "borrower-1");
        body.put("purpose", "出庭");
        body.put("dueAt", LocalDateTime.now().plusHours(24).toString());
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult initiateTransfer(String actor, String evidenceKey, String commandKey,
                                       String toCustodian) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("toCustodian", toCustodian);
        return mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult inspect(String actor, String evidenceKey, String commandKey,
                              boolean passed) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("passed", passed);
        return mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    /**
     * 准备一对启用的源/目标库位与两件库内证物，返回 [source, target, ev1, ev2]。
     */
    private String[] prepareMoveFixture() throws Exception {
        String source = uniqueKey("LOC-S");
        String target = uniqueKey("LOC-T");
        assertThat(createLocation("admin", uniqueKey("CMD"), source).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(createLocation("admin", uniqueKey("CMD"), target).getResponse().getStatus())
                .isEqualTo(201);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        assertThat(intake("alice", uniqueKey("CMD"), ev1, source).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(intake("alice", uniqueKey("CMD"), ev2, source).getResponse().getStatus())
                .isEqualTo(201);
        return new String[]{source, target, ev1, ev2};
    }

    @Test
    void createLocationAndInventoryTracksVersion() throws Exception {
        String location = uniqueKey("LOC");
        MvcResult created = createLocation("admin", uniqueKey("CMD"), location);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode view = objectMapper.readTree(created.getResponse().getContentAsString());
        assertThat(view.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(view.get("version").asInt()).isEqualTo(0);

        String ev = uniqueKey("EV");
        assertThat(intake("alice", uniqueKey("CMD"), ev, location).getResponse().getStatus())
                .isEqualTo(201);

        JsonNode inventory = inventory(location);
        assertThat(inventory.get("version").asInt()).isEqualTo(1);
        assertThat(inventory.get("evidence")).hasSize(1);
        assertThat(inventory.get("evidence").get(0).get("evidenceKey").asText()).isEqualTo(ev);
        assertThat(inventory.get("evidence").get(0).get("locationCode").asText())
                .isEqualTo(location);
    }

    @Test
    void createDuplicateLocationReturns409() throws Exception {
        String location = uniqueKey("LOC");
        assertThat(createLocation("admin", uniqueKey("CMD"), location).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(createLocation("admin", uniqueKey("CMD"), location).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void disableLocationTwiceAndIdempotentReplay() throws Exception {
        String location = uniqueKey("LOC");
        createLocation("admin", uniqueKey("CMD"), location);

        String commandKey = uniqueKey("CMD");
        MvcResult first = disableLocation("admin", location, commandKey);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(first.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("DISABLED");

        // 同键同参重放返回首次结果
        MvcResult replay = disableLocation("admin", location, commandKey);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 新键重复停用返回 409
        assertThat(disableLocation("admin", location, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);

        // 停用库位不可作为入库目标
        assertThat(intake("alice", uniqueKey("CMD"), uniqueKey("EV"), location)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void intakeToMissingLocationReturns409() throws Exception {
        assertThat(intake("alice", uniqueKey("CMD"), uniqueKey("EV"), uniqueKey("LOC"))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void fullDoubleConfirmMovesAllEvidenceAtomicallyWithSnapshots() throws Exception {
        String[] fixture = prepareMoveFixture();
        String source = fixture[0];
        String target = fixture[1];
        String ev1 = fixture[2];
        String ev2 = fixture[3];
        int sourceVersion = inventory(source).get("version").asInt();
        int targetVersion = inventory(target).get("version").asInt();

        String moveKey = uniqueKey("MOVE");
        MvcResult created = createMove("carol", moveKey, List.of(ev1, ev2), source, target,
                sourceVersion);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode move = objectMapper.readTree(created.getResponse().getContentAsString());
        assertThat(move.get("status").asText()).isEqualTo("PENDING");
        assertThat(move.get("evidenceKeys")).hasSize(2);

        // 首人确认
        MvcResult first = confirm("alice", moveKey, uniqueKey("CMD"));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        JsonNode firstView = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(firstView.get("status").asText()).isEqualTo("FIRST_CONFIRMED");
        assertThat(firstView.get("firstConfirmer").asText()).isEqualTo("alice");

        // 首人确认后证物仍未迁移
        assertThat(inventory(source).get("evidence")).hasSize(2);

        // 第二人（不同保管人）确认：整单执行
        MvcResult second = confirm("bob", moveKey, uniqueKey("CMD"));
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        JsonNode result = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(result.get("move").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.get("move").get("secondConfirmer").asText()).isEqualTo("bob");
        assertThat(result.get("record").get("firstConfirmer").asText()).isEqualTo("alice");
        assertThat(result.get("record").get("secondConfirmer").asText()).isEqualTo("bob");
        assertThat(result.get("snapshots")).hasSize(2);

        // 全部证物库位原子切换，双方库位库存版本各递增
        JsonNode sourceInventory = inventory(source);
        JsonNode targetInventory = inventory(target);
        assertThat(sourceInventory.get("evidence")).isEmpty();
        assertThat(targetInventory.get("evidence")).hasSize(2);
        assertThat(sourceInventory.get("version").asInt()).isEqualTo(sourceVersion + 1);
        assertThat(targetInventory.get("version").asInt()).isEqualTo(targetVersion + 1);

        // 逐件封签快照：封条编号快照与完好状态
        JsonNode snapshots = getSnapshots(moveKey);
        assertThat(snapshots).hasSize(2);
        for (JsonNode snapshot : snapshots) {
            assertThat(snapshot.get("sealStatus").asText()).isEqualTo("INTACT");
            assertThat(snapshot.get("fromLocation").asText()).isEqualTo(source);
            assertThat(snapshot.get("toLocation").asText()).isEqualTo(target);
            assertThat(snapshot.get("sealNo").asText()).startsWith("SEAL-");
        }
        assertThat(snapshots.findValuesAsText("evidenceKey"))
                .containsExactlyInAnyOrder(ev1, ev2);

        // 迁移记录可查询且不可变字段完整
        MvcResult record = mockMvc.perform(get("/api/moves/{key}/record", moveKey)).andReturn();
        assertThat(record.getResponse().getStatus()).isEqualTo(200);
        JsonNode recordView = objectMapper.readTree(record.getResponse().getContentAsString());
        assertThat(recordView.get("expectedVersion").asInt()).isEqualTo(sourceVersion);
    }

    @Test
    void createMoveWithReorderedEvidenceSetReplaysSameResult() throws Exception {
        String[] fixture = prepareMoveFixture();
        String moveKey = uniqueKey("MOVE");
        int version = inventory(fixture[0]).get("version").asInt();

        MvcResult first = createMove("carol", moveKey, List.of(fixture[2], fixture[3]),
                fixture[0], fixture[1], version);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        // 集合换序视为同参：同键重放返回首次结果，不新建迁移单
        MvcResult replay = createMove("carol", moveKey, List.of(fixture[3], fixture[2]),
                fixture[0], fixture[1], version);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void createMoveSameKeyWithDifferentParamsReturns409() throws Exception {
        String[] fixture = prepareMoveFixture();
        String moveKey = uniqueKey("MOVE");
        int version = inventory(fixture[0]).get("version").asInt();
        assertThat(createMove("carol", moveKey, List.of(fixture[2], fixture[3]),
                fixture[0], fixture[1], version).getResponse().getStatus()).isEqualTo(201);
        // 同键改版本视为改参
        assertThat(createMove("carol", moveKey, List.of(fixture[2], fixture[3]),
                fixture[0], fixture[1], version + 1).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void createMoveValidationFailuresReturn422WithPerItemDetails() throws Exception {
        String[] fixture = prepareMoveFixture();
        String source = fixture[0];
        String target = fixture[1];
        String borrowed = fixture[2];
        String pending = uniqueKey("EV");
        String broken = uniqueKey("EV");
        String elsewhere = uniqueKey("EV");
        String missing = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), pending, source);
        intake("alice", uniqueKey("CMD"), broken, source);
        intake("alice", uniqueKey("CMD"), elsewhere, "DEFAULT");
        // 借出中
        assertThat(borrow("alice", borrowed, uniqueKey("CMD"), uniqueKey("LOAN"))
                .getResponse().getStatus()).isEqualTo(200);
        // 待核验（待接收交接）
        assertThat(initiateTransfer("alice", pending, uniqueKey("CMD"), "bob")
                .getResponse().getStatus()).isEqualTo(200);
        // 封条异常
        assertThat(inspect("alice", broken, uniqueKey("CMD"), false).getResponse().getStatus())
                .isEqualTo(200);

        int version = inventory(source).get("version").asInt();
        MvcResult result = createMove("carol", uniqueKey("MOVE"),
                List.of(borrowed, pending, broken, elsewhere, missing), source, target, version);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode items = error.get("items");
        assertThat(items).hasSize(5);
        assertThat(items.toString()).contains(borrowed, pending, broken, elsewhere, missing);
        assertThat(items.toString()).contains("借出", "待核验", "封条异常", "不等于源库位", "不存在");
    }

    @Test
    void createMoveWithSourceEqualsTargetReturns400() throws Exception {
        String[] fixture = prepareMoveFixture();
        int version = inventory(fixture[0]).get("version").asInt();
        assertThat(createMove("carol", uniqueKey("MOVE"), List.of(fixture[2]),
                fixture[0], fixture[0], version).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void createMoveWithDisabledTargetReturns409() throws Exception {
        String[] fixture = prepareMoveFixture();
        disableLocation("admin", fixture[1], uniqueKey("CMD"));
        int version = inventory(fixture[0]).get("version").asInt();
        assertThat(createMove("carol", uniqueKey("MOVE"), List.of(fixture[2]),
                fixture[0], fixture[1], version).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void createMoveWithStaleVersionReturns409() throws Exception {
        String[] fixture = prepareMoveFixture();
        int version = inventory(fixture[0]).get("version").asInt();
        assertThat(createMove("carol", uniqueKey("MOVE"), List.of(fixture[2]),
                fixture[0], fixture[1], version + 5).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void failedMoveDoesNotHoldMoveKey() throws Exception {
        String[] fixture = prepareMoveFixture();
        String moveKey = uniqueKey("MOVE");
        int version = inventory(fixture[0]).get("version").asInt();
        // 版本不符失败：不占键
        assertThat(createMove("carol", moveKey, List.of(fixture[2]),
                fixture[0], fixture[1], version + 1).getResponse().getStatus()).isEqualTo(409);
        // 修正参数后同键可成功
        assertThat(createMove("carol", moveKey, List.of(fixture[2]),
                fixture[0], fixture[1], version).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void samePersonCannotConfirmTwice() throws Exception {
        String[] fixture = prepareMoveFixture();
        String moveKey = uniqueKey("MOVE");
        int version = inventory(fixture[0]).get("version").asInt();
        createMove("carol", moveKey, List.of(fixture[2], fixture[3]), fixture[0], fixture[1],
                version);
        assertThat(confirm("alice", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(200);
        // 同一人不能作为第二人确认
        assertThat(confirm("alice", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(getMove(moveKey).get("status").asText()).isEqualTo("FIRST_CONFIRMED");
    }

    @Test
    void cancelAfterFirstConfirmBlocksFurtherConfirm() throws Exception {
        String[] fixture = prepareMoveFixture();
        String moveKey = uniqueKey("MOVE");
        int version = inventory(fixture[0]).get("version").asInt();
        createMove("carol", moveKey, List.of(fixture[2]), fixture[0], fixture[1], version);
        confirm("alice", moveKey, uniqueKey("CMD"));

        MvcResult cancelled = cancel("carol", moveKey, uniqueKey("CMD"));
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(cancelled.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("CANCELLED");

        // 撤销后不可再确认
        assertThat(confirm("bob", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        // 重复撤销返回 409
        assertThat(cancel("carol", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        // 证物库位未变化
        assertThat(inventory(fixture[0]).get("evidence")).hasSize(2);
    }

    @Test
    void completedMoveCannotBeCancelledOrConfirmed() throws Exception {
        String[] fixture = prepareMoveFixture();
        String moveKey = uniqueKey("MOVE");
        int version = inventory(fixture[0]).get("version").asInt();
        createMove("carol", moveKey, List.of(fixture[2]), fixture[0], fixture[1], version);
        confirm("alice", moveKey, uniqueKey("CMD"));
        confirm("bob", moveKey, uniqueKey("CMD"));

        assertThat(cancel("carol", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(confirm("dave", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void confirmWithDisabledTargetReturns409() throws Exception {
        String[] fixture = prepareMoveFixture();
        String moveKey = uniqueKey("MOVE");
        int version = inventory(fixture[0]).get("version").asInt();
        createMove("carol", moveKey, List.of(fixture[2]), fixture[0], fixture[1], version);
        // 申请后目标库位被停用
        disableLocation("admin", fixture[1], uniqueKey("CMD"));
        assertThat(confirm("alice", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(getMove(moveKey).get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void secondConfirmWithBorrowedEvidenceReturns409AndRollsBackWholeOrder() throws Exception {
        String[] fixture = prepareMoveFixture();
        String source = fixture[0];
        String moveKey = uniqueKey("MOVE");
        int version = inventory(source).get("version").asInt();
        createMove("carol", moveKey, List.of(fixture[2], fixture[3]), source, fixture[1], version);
        confirm("alice", moveKey, uniqueKey("CMD"));

        // 首人确认后其中一件被借出
        assertThat(borrow("alice", fixture[2], uniqueKey("CMD"), uniqueKey("LOAN"))
                .getResponse().getStatus()).isEqualTo(200);

        // 第二人确认：整单回滚，另一件证物库位不变
        MvcResult second = confirm("bob", moveKey, uniqueKey("CMD"));
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        JsonNode error = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(error.get("items").toString()).contains(fixture[2]);

        assertThat(getMove(moveKey).get("status").asText()).isEqualTo("FIRST_CONFIRMED");
        assertThat(inventory(source).get("evidence")).hasSize(2);
        assertThat(getSnapshots(moveKey)).isEmpty();
        MvcResult record = mockMvc.perform(get("/api/moves/{key}/record", moveKey)).andReturn();
        assertThat(record.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void secondConfirmWithStaleVersionReturns409AndRollsBack() throws Exception {
        String[] fixture = prepareMoveFixture();
        String source = fixture[0];
        String moveKey = uniqueKey("MOVE");
        int version = inventory(source).get("version").asInt();
        createMove("carol", moveKey, List.of(fixture[2]), source, fixture[1], version);
        confirm("alice", moveKey, uniqueKey("CMD"));

        // 首人确认后源库位库存变化（新证物入库），版本不再等于 expectedVersion
        assertThat(intake("alice", uniqueKey("CMD"), uniqueKey("EV"), source)
                .getResponse().getStatus()).isEqualTo(201);

        assertThat(confirm("bob", moveKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(getMove(moveKey).get("status").asText()).isEqualTo("FIRST_CONFIRMED");
        // 证物仍在源库位
        assertThat(inventory(source).get("evidence")).hasSize(3);
    }

    @Test
    void historyRecordAndSnapshotsAreNotRewrittenByLaterOperations() throws Exception {
        String[] fixture = prepareMoveFixture();
        String source = fixture[0];
        String target = fixture[1];
        String moveKey = uniqueKey("MOVE");
        int version = inventory(source).get("version").asInt();
        createMove("carol", moveKey, List.of(fixture[2]), source, target, version);
        confirm("alice", moveKey, uniqueKey("CMD"));
        confirm("bob", moveKey, uniqueKey("CMD"));

        JsonNode recordBefore = objectMapper.readTree(mockMvc
                .perform(get("/api/moves/{key}/record", moveKey)).andReturn()
                .getResponse().getContentAsString());
        JsonNode snapshotsBefore = getSnapshots(moveKey);

        // 后续操作：迁入目标库位的证物再次核验、新证物入库目标库位
        inspect("alice", fixture[2], uniqueKey("CMD"), true);
        intake("alice", uniqueKey("CMD"), uniqueKey("EV"), target);

        JsonNode recordAfter = objectMapper.readTree(mockMvc
                .perform(get("/api/moves/{key}/record", moveKey)).andReturn()
                .getResponse().getContentAsString());
        JsonNode snapshotsAfter = getSnapshots(moveKey);
        assertThat(recordAfter).isEqualTo(recordBefore);
        assertThat(snapshotsAfter).isEqualTo(snapshotsBefore);
    }

    @Test
    void secondConfirmIdempotentReplayReturnsFullResultWithoutDuplicates() throws Exception {
        String[] fixture = prepareMoveFixture();
        String moveKey = uniqueKey("MOVE");
        int version = inventory(fixture[0]).get("version").asInt();
        createMove("carol", moveKey, List.of(fixture[2], fixture[3]), fixture[0], fixture[1],
                version);
        confirm("alice", moveKey, uniqueKey("CMD"));

        String commandKey = uniqueKey("CMD");
        MvcResult first = confirm("bob", moveKey, commandKey);
        MvcResult replay = confirm("bob", moveKey, commandKey);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 重放不产生重复快照与迁移记录
        assertThat(getSnapshots(moveKey)).hasSize(2);
    }

    @Test
    void queriesOnMissingMoveReturn404() throws Exception {
        String missing = uniqueKey("MOVE");
        assertThat(mockMvc.perform(get("/api/moves/{key}", missing)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/moves/{key}/record", missing)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/moves/{key}/snapshots", missing)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(confirm("alice", missing, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(cancel("alice", missing, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/locations/{code}/inventory", uniqueKey("LOC")))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void createMoveWithEmptyEvidenceSetReturns400() throws Exception {
        String[] fixture = prepareMoveFixture();
        int version = inventory(fixture[0]).get("version").asInt();
        assertThat(createMove("carol", uniqueKey("MOVE"), List.of(), fixture[0], fixture[1],
                version).getResponse().getStatus()).isEqualTo(400);
    }
}
