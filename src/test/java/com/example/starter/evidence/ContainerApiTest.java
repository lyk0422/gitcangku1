package com.example.starter.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * 封存容器巡检 API：FAIL 批量状态变更、持续借出门禁、双人复核、
 * 历史快照、集合换序同参与 inspectKey 幂等的主流程与失败分支测试（真实 H2 数据库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContainerApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";
    private static final Instant BASE = Instant.parse("2026-09-26T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EvidenceClock evidenceClock;

    @BeforeEach
    void fixClock() {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
    }

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private LocalDateTime utc(long seconds) {
        return LocalDateTime.ofInstant(BASE.plusSeconds(seconds), ZoneOffset.UTC);
    }

    private MvcResult intake(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-C");
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-C");
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult createContainer(String actor, String containerKey, String commandKey,
                                      long nextInSeconds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("containerKey", containerKey);
        body.put("nextInspectionAt", utc(nextInSeconds));
        return mockMvc.perform(post("/api/containers")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult load(String actor, String containerKey, String commandKey,
                           List<String> evidenceKeys) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("evidenceKeys", evidenceKeys);
        return mockMvc.perform(post("/api/containers/{key}/load", containerKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult unload(String actor, String containerKey, String commandKey,
                             List<String> evidenceKeys) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("evidenceKeys", evidenceKeys);
        return mockMvc.perform(post("/api/containers/{key}/unload", containerKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult inspect(String actor, String containerKey, String commandKey,
                              String result, String note, long inspectedInSeconds,
                              long nextInSeconds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("inspectedAt", utc(inspectedInSeconds));
        body.put("result", result);
        body.put("note", note);
        body.put("nextInspectionAt", utc(nextInSeconds));
        return mockMvc.perform(post("/api/containers/{key}/inspections", containerKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult review(String actor, String containerKey, String commandKey, String note)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("note", note);
        body.put("reviewedAt", utc(3600));
        return mockMvc.perform(post("/api/containers/{key}/reviews", containerKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult borrow(String actor, String evidenceKey, String borrower) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", borrower);
        body.put("purpose", "鉴定用");
        body.put("dueAt", utc(7200));
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult initiateTransfer(String actor, String evidenceKey, String toCustodian)
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

    private JsonNode getContainer(String containerKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/containers/{key}", containerKey)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode listInspections(String containerKey) throws Exception {
        MvcResult result = mockMvc.perform(
                get("/api/containers/{key}/inspections", containerKey)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode listPending(String containerKey) throws Exception {
        MvcResult result = mockMvc.perform(
                get("/api/containers/{key}/pending-verification", containerKey)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode eligibility(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(
                get("/api/containers/loan-eligibility/{key}", evidenceKey)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void createContainerReturnsSealedWithVersionZero() throws Exception {
        String containerKey = uniqueKey("BOX");
        MvcResult result = createContainer("alice", containerKey, uniqueKey("CMD"), 86400);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("containerKey").asText()).isEqualTo(containerKey);
        assertThat(body.get("status").asText()).isEqualTo("SEALED");
        assertThat(body.get("version").asLong()).isZero();
        assertThat(body.get("evidenceKeys")).isEmpty();
    }

    @Test
    void createContainerDuplicateKeyReturns409AndReplaysFirstResult() throws Exception {
        String containerKey = uniqueKey("BOX");
        String commandKey = uniqueKey("CMD");
        assertThat(createContainer("alice", containerKey, commandKey, 86400).getResponse().getStatus())
                .isEqualTo(201);
        MvcResult replay = createContainer("alice", containerKey, commandKey, 86400);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(createContainer("alice", containerKey, uniqueKey("CMD"), 86400)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void createContainerWithPastNextInspectionReturns400() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String containerKey = uniqueKey("BOX");
        assertThat(createContainer("alice", containerKey, uniqueKey("CMD"), -10)
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void loadStoresEvidenceAndReorderedSetIsSameParameters() throws Exception {
        String containerKey = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        String ev3 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        intake("alice", ev3);
        createContainer("alice", containerKey, uniqueKey("CMD"), 86400);

        String commandKey = uniqueKey("CMD");
        MvcResult first = load("alice", containerKey, commandKey, List.of(ev1, ev2, ev3));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        // 集合换序 + 重复元素：同键视为同参，重放首次结果而非 409
        MvcResult replay = load("alice", containerKey, commandKey, List.of(ev3, ev1, ev2, ev1));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        JsonNode container = getContainer(containerKey);
        List<String> storedKeys = new java.util.ArrayList<>();
        container.get("evidenceKeys").forEach(node -> storedKeys.add(node.asText()));
        // 服务端按升序返回；集合语义只要求三件齐全
        assertThat(storedKeys).containsExactlyInAnyOrder(ev1, ev2, ev3);
        assertThat(storedKeys).isSorted();
    }

    @Test
    void loadRequiresContainerCustodianAndSealedState() throws Exception {
        String containerKey = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        createContainer("alice", containerKey, uniqueKey("CMD"), 86400);
        // 非容器负责人
        assertThat(load("mallory", containerKey, uniqueKey("CMD"), List.of(ev1))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void failInspectionMarksContainerAllEvidencePendingAndWritesSnapshots() throws Exception {
        String containerKey = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        createContainer("alice", containerKey, uniqueKey("CMD"), 86400);
        load("alice", containerKey, uniqueKey("CMD"), List.of(ev1, ev2));

        MvcResult result = inspect("alice", containerKey, uniqueKey("CMD"), "FAIL",
                "封签撕裂", 100, 90000);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode inspection = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(inspection.get("result").asText()).isEqualTo("FAIL");
        assertThat(inspection.get("containerVersion").asLong()).isZero();
        assertThat(inspection.get("snapshots")).hasSize(2);
        assertThat(inspection.get("snapshots").findValuesAsText("evidenceStatus"))
                .containsOnly("SEALED");

        JsonNode container = getContainer(containerKey);
        assertThat(container.get("status").asText()).isEqualTo("INSPECTION_FAILED");
        assertThat(container.get("version").asLong()).isEqualTo(1);

        JsonNode pending = listPending(containerKey);
        assertThat(pending.findValuesAsText("evidenceKey")).containsExactlyInAnyOrder(ev1, ev2);
        pending.forEach(node -> assertThat(node.get("status").asText())
                .isEqualTo("PENDING_VERIFICATION"));
    }

    @Test
    void failInspectionWithoutNoteReturns400AndDoesNotOccupyKey() throws Exception {
        String containerKey = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        createContainer("alice", containerKey, uniqueKey("CMD"), 86400);
        load("alice", containerKey, uniqueKey("CMD"), List.of(ev1));

        String commandKey = uniqueKey("CMD");
        assertThat(inspect("alice", containerKey, commandKey, "FAIL", "  ", 100, 90000)
                .getResponse().getStatus()).isEqualTo(400);
        // 失败不占键：同一 commandKey 可用于一次成功的 PASS 巡检
        MvcResult success = inspect("alice", containerKey, commandKey, "PASS", null, 100, 90000);
        assertThat(success.getResponse().getStatus()).isEqualTo(200);
        assertThat(getContainer(containerKey).get("status").asText()).isEqualTo("SEALED");
        assertThat(listInspections(containerKey)).hasSize(1);
    }

    @Test
    void nextInspectionNotAfterActualReturns400() throws Exception {
        String containerKey = uniqueKey("BOX");
        createContainer("alice", containerKey, uniqueKey("CMD"), 86400);
        // 下一次巡检时刻等于实际时刻：不是严格晚于
        assertThat(inspect("alice", containerKey, uniqueKey("CMD"), "PASS", null, 1000, 1000)
                .getResponse().getStatus()).isEqualTo(400);
        // 早于实际时刻
        assertThat(inspect("alice", containerKey, uniqueKey("CMD"), "PASS", null, 1000, 999)
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void earlyInspectionBeforeDueTimeIsAllowed() throws Exception {
        String containerKey = uniqueKey("BOX");
        createContainer("alice", containerKey, uniqueKey("CMD"), 86400);
        // 实际巡检时刻远早于容器下次巡检时刻（提前巡检），合法
        MvcResult result = inspect("alice", containerKey, uniqueKey("CMD"), "PASS", "例行提前检查",
                10, 40000);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode container = getContainer(containerKey);
        assertThat(container.get("nextInspectionAt").asText()).isEqualTo(utc(40000).toString());
        assertThat(container.get("version").asLong()).isEqualTo(1);
    }

    @Test
    void failedContainerBlocksLoanTransferAndCollectionChangesUntilTwoReviews() throws Exception {
        String containerKey = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        createContainer("alice", containerKey, uniqueKey("CMD"), 86400);
        load("alice", containerKey, uniqueKey("CMD"), List.of(ev1, ev2));
        inspect("alice", containerKey, uniqueKey("CMD"), "FAIL", "封签撕裂", 100, 90000);

        // 持续借出门禁：新借出 409，迁移（发起交接）409
        assertThat(borrow("alice", ev1, "bob").getResponse().getStatus()).isEqualTo(409);
        assertThat(initiateTransfer("alice", ev1, "bob").getResponse().getStatus()).isEqualTo(409);
        // FAIL 容器不得变更集合：装载与移出均 409
        String ev3 = uniqueKey("EV");
        intake("alice", ev3);
        assertThat(load("alice", containerKey, uniqueKey("CMD"), List.of(ev3))
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(unload("alice", containerKey, uniqueKey("CMD"), List.of(ev1))
                .getResponse().getStatus()).isEqualTo(409);
        // 阻断原因可查询
        JsonNode eligibility = eligibility(ev1);
        assertThat(eligibility.get("blocked").asBoolean()).isTrue();
        assertThat(eligibility.get("reasons").toString()).contains("INSPECTION_FAILED");
        // FAIL 容器禁止再次巡检
        assertThat(inspect("alice", containerKey, uniqueKey("CMD"), "PASS", null, 200, 91000)
                .getResponse().getStatus()).isEqualTo(409);

        // 第一名不同保管人复核：未恢复，门禁仍在
        MvcResult firstReview = review("bob", containerKey, uniqueKey("CMD"), "复核封签完好");
        assertThat(firstReview.getResponse().getStatus()).isEqualTo(200);
        JsonNode firstBody = objectMapper.readTree(firstReview.getResponse().getContentAsString());
        assertThat(firstBody.get("restored").asBoolean()).isFalse();
        assertThat(firstBody.get("reviewerCount").asInt()).isEqualTo(1);
        assertThat(borrow("alice", ev1, "carol").getResponse().getStatus()).isEqualTo(409);

        // FAIL 检查人本人不能复核；同一复核人不能重复
        assertThat(review("alice", containerKey, uniqueKey("CMD"), "自检")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(review("bob", containerKey, uniqueKey("CMD"), "再次复核")
                .getResponse().getStatus()).isEqualTo(409);

        // 第二名不同保管人复核：容器恢复、证物批量恢复 SEALED
        MvcResult secondReview = review("carol", containerKey, uniqueKey("CMD"), "复核封签完好");
        assertThat(secondReview.getResponse().getStatus()).isEqualTo(200);
        JsonNode secondBody = objectMapper.readTree(secondReview.getResponse().getContentAsString());
        assertThat(secondBody.get("restored").asBoolean()).isTrue();
        assertThat(secondBody.get("reviewerCount").asInt()).isEqualTo(2);
        JsonNode container = secondBody.get("container");
        assertThat(container.get("status").asText()).isEqualTo("SEALED");
        assertThat(container.get("version").asLong()).isEqualTo(2);
        assertThat(listPending(containerKey)).isEmpty();

        // 恢复后门禁解除：借出成功
        assertThat(borrow("alice", ev1, "dave").getResponse().getStatus()).isEqualTo(200);
        // 集合变更恢复允许
        assertThat(unload("alice", containerKey, uniqueKey("CMD"), List.of(ev2))
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void failInspectionRollsBackWhenAnyEvidenceCannotBeMarked() throws Exception {
        String containerKey = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        createContainer("alice", containerKey, uniqueKey("CMD"), 86400);
        load("alice", containerKey, uniqueKey("CMD"), List.of(ev1, ev2));
        // ev1 借出中（BORROWED，仍在容器集合内）：FAIL 时该证物不允许标记，整单回滚
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        assertThat(borrow("alice", ev1, "bob").getResponse().getStatus()).isEqualTo(200);

        String inspectKey = uniqueKey("CMD");
        MvcResult failed = inspect("alice", containerKey, inspectKey, "FAIL", "封签撕裂", 100, 90000);
        assertThat(failed.getResponse().getStatus()).isEqualTo(409);

        // 整单回滚：容器仍 SEALED、无巡检记录与快照、ev2 未被标记
        assertThat(getContainer(containerKey).get("status").asText()).isEqualTo("SEALED");
        assertThat(listInspections(containerKey)).isEmpty();
        assertThat(listPending(containerKey)).isEmpty();

        // 将借出中的 ev1 移出容器（SEALED 容器允许变更集合），仅剩 ev2，
        // 再以同一 inspectKey 发起 FAIL 巡检：失败不占键，巡检成功且只快照 ev2。
        assertThat(unload("alice", containerKey, uniqueKey("CMD"), List.of(ev1))
                .getResponse().getStatus()).isEqualTo(200);
        MvcResult retry = inspect("alice", containerKey, inspectKey, "FAIL", "封签撕裂", 100, 90000);
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        JsonNode inspection = objectMapper.readTree(retry.getResponse().getContentAsString());
        assertThat(inspection.get("snapshots")).hasSize(1);
    }

    @Test
    void passInspectionKeepsHistoricalFailAndSnapshotsUntouched() throws Exception {
        String containerKey = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        createContainer("alice", containerKey, uniqueKey("CMD"), 86400);
        load("alice", containerKey, uniqueKey("CMD"), List.of(ev1));
        inspect("alice", containerKey, uniqueKey("CMD"), "FAIL", "封签撕裂", 100, 90000);
        review("bob", containerKey, uniqueKey("CMD"), "复核一");
        review("carol", containerKey, uniqueKey("CMD"), "复核二");

        MvcResult pass = inspect("alice", containerKey, uniqueKey("CMD"), "PASS", "恢复后例行巡检",
                2000, 180000);
        assertThat(pass.getResponse().getStatus()).isEqualTo(200);

        JsonNode inspections = listInspections(containerKey);
        assertThat(inspections).hasSize(2);
        assertThat(inspections.get(0).get("result").asText()).isEqualTo("FAIL");
        assertThat(inspections.get(0).get("snapshots")).hasSize(1);
        assertThat(inspections.get(1).get("result").asText()).isEqualTo("PASS");
        assertThat(inspections.get(1).get("snapshots")).isEmpty();
        // PASS 不改写历史 FAIL：容器 SEALED，历史快照仍可按巡检查询
        assertThat(getContainer(containerKey).get("status").asText()).isEqualTo("SEALED");
    }

    @Test
    void inspectKeyReplayReturnsFirstCompleteResultAndDifferentParamsReturn409() throws Exception {
        String containerKey = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        createContainer("alice", containerKey, uniqueKey("CMD"), 86400);
        load("alice", containerKey, uniqueKey("CMD"), List.of(ev1));

        String inspectKey = uniqueKey("CMD");
        MvcResult first = inspect("alice", containerKey, inspectKey, "FAIL", "封签撕裂", 100, 90000);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        MvcResult replay = inspect("alice", containerKey, inspectKey, "FAIL", "封签撕裂", 100, 90000);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 只有一条巡检记录、一批快照
        JsonNode inspections = listInspections(containerKey);
        assertThat(inspections).hasSize(1);
        assertThat(inspections.get(0).get("snapshots")).hasSize(1);

        // 同键改参（说明不同）返回 409
        assertThat(inspect("alice", containerKey, inspectKey, "FAIL", "不同说明", 100, 90000)
                .getResponse().getStatus()).isEqualTo(409);
        // 同键改参（结果不同）返回 409
        assertThat(inspect("alice", containerKey, inspectKey, "PASS", null, 100, 90000)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void evidenceInFailedContainerCannotBeLoadedIntoAnotherContainer() throws Exception {
        String failedBox = uniqueKey("BOX");
        String otherBox = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        createContainer("alice", failedBox, uniqueKey("CMD"), 86400);
        createContainer("alice", otherBox, uniqueKey("CMD"), 86400);
        load("alice", failedBox, uniqueKey("CMD"), List.of(ev1));
        inspect("alice", failedBox, uniqueKey("CMD"), "FAIL", "封签撕裂", 100, 90000);

        // FAIL 容器内证物不得迁移：装入另一个 SEALED 容器返回 409
        assertThat(load("alice", otherBox, uniqueKey("CMD"), List.of(ev1))
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(getContainer(otherBox).get("evidenceKeys")).isEmpty();
    }

    @Test
    void loadMissingOrNonSealedEvidenceReturns404Or409() throws Exception {
        String containerKey = uniqueKey("BOX");
        createContainer("alice", containerKey, uniqueKey("CMD"), 86400);
        // 不存在的证物：404
        assertThat(load("alice", containerKey, uniqueKey("CMD"), List.of(uniqueKey("EV")))
                .getResponse().getStatus()).isEqualTo(404);

        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        assertThat(borrow("alice", ev1, "bob").getResponse().getStatus()).isEqualTo(200);
        // 借出中（BORROWED）证物不允许装入：409
        assertThat(load("alice", containerKey, uniqueKey("CMD"), List.of(ev1))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void unloadEvidenceNotInContainerReturns409() throws Exception {
        String containerKey = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        createContainer("alice", containerKey, uniqueKey("CMD"), 86400);
        assertThat(unload("alice", containerKey, uniqueKey("CMD"), List.of(ev1))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void independentSealInspectionBlockedWhilePendingVerification() throws Exception {
        String containerKey = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        createContainer("alice", containerKey, uniqueKey("CMD"), 86400);
        load("alice", containerKey, uniqueKey("CMD"), List.of(ev1));
        inspect("alice", containerKey, uniqueKey("CMD"), "FAIL", "封签撕裂", 100, 90000);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", true);
        body.put("note", "试图自行核验");
        MvcResult result = mockMvc.perform(post("/api/evidence/{key}/seal-inspections", ev1)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void reviewBySameCustodianTwiceAndOnSealedContainerReturn409() throws Exception {
        String containerKey = uniqueKey("BOX");
        createContainer("alice", containerKey, uniqueKey("CMD"), 86400);
        // SEALED 容器复核返回 409
        assertThat(review("bob", containerKey, uniqueKey("CMD"), "无失败可复核")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void operateOnMissingContainerReturns404() throws Exception {
        String missing = uniqueKey("BOX");
        assertThat(getContainer404(missing)).isEqualTo(404);
        assertThat(inspect("alice", missing, uniqueKey("CMD"), "PASS", null, 0, 100)
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(review("bob", missing, uniqueKey("CMD"), "x")
                .getResponse().getStatus()).isEqualTo(404);
    }

    private int getContainer404(String containerKey) throws Exception {
        return mockMvc.perform(get("/api/containers/{key}", containerKey))
                .andReturn().getResponse().getStatus();
    }
}
