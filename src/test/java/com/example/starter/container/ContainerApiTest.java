package com.example.starter.container;

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
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 封存容器巡检 API 主流程与失败分支测试，全部使用真实 H2(MODE=MySQL) 内存库与真实事务，
 * 覆盖 FAIL 批量状态变更、持续借出门禁、双人复核、历史快照与 inspectKey 幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContainerApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private LocalDateTime utc(int plusHours) {
        return LocalDateTime.now(ZoneOffset.UTC).plusHours(plusHours);
    }

    private MvcResult intake(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-C");
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-" + evidenceKey);
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult createContainer(String containerId, LocalDateTime nextInspectionAt)
            throws Exception {
        return createContainer(uniqueKey("CMD"), containerId, nextInspectionAt);
    }

    private MvcResult createContainer(String commandKey, String containerId,
                                      LocalDateTime nextInspectionAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("containerId", containerId);
        body.put("nextInspectionAt", nextInspectionAt.toString());
        return mockMvc.perform(post("/api/containers")
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult loadItems(String actor, String containerId, List<String> evidenceKeys)
            throws Exception {
        return items(actor, containerId, evidenceKeys, false);
    }

    private MvcResult unloadItems(String actor, String containerId, List<String> evidenceKeys)
            throws Exception {
        return items(actor, containerId, evidenceKeys, true);
    }

    private MvcResult items(String actor, String containerId, List<String> evidenceKeys,
                            boolean unload) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKeys", evidenceKeys);
        String path = unload ? "/api/containers/{id}/items/unload" : "/api/containers/{id}/items";
        return mockMvc.perform(post(path, containerId)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult inspect(String actor, String containerId, String commandKey,
                              long version, LocalDateTime inspectedAt,
                              LocalDateTime nextInspectionAt, String result, String note)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("containerVersion", version);
        body.put("inspectedAt", inspectedAt.toString());
        body.put("nextInspectionAt", nextInspectionAt.toString());
        body.put("result", result);
        if (note != null) {
            body.put("note", note);
        }
        return mockMvc.perform(post("/api/containers/{id}/inspections", containerId)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult review(String actor, String containerId, String commandKey, String note)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        if (note != null) {
            body.put("note", note);
        }
        return mockMvc.perform(post("/api/containers/{id}/reviews", containerId)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult borrow(String actor, String evidenceKey, String borrower, String loanKey)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", loanKey);
        body.put("borrowerId", borrower);
        body.put("purpose", "lab test");
        body.put("dueAt", utc(2).toString());
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
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

    private JsonNode detail(String containerId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/containers/{id}", containerId))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode blockReason(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/containers/evidence/{key}/block-reason", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void createContainerStartsSealedAtVersionZero() throws Exception {
        String containerId = uniqueKey("BOX");
        MvcResult result = createContainer(containerId, utc(24 * 7));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("containerId").asText()).isEqualTo(containerId);
        assertThat(body.get("status").asText()).isEqualTo("SEALED");
        assertThat(body.get("version").asLong()).isZero();
    }

    @Test
    void createDuplicateContainerReturns409AndReplayReturnsFirstResult() throws Exception {
        String containerId = uniqueKey("BOX");
        String commandKey = uniqueKey("CMD");
        LocalDateTime nextAt = utc(48);
        MvcResult first = createContainer(commandKey, containerId, nextAt);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        // 不同命令键再次创建同 ID 容器冲突
        assertThat(createContainer(containerId, nextAt).getResponse().getStatus()).isEqualTo(409);
        // 同键同参重放返回首次完整结果
        MvcResult replay = createContainer(commandKey, containerId, nextAt);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void loadItemsLocksEvidencesAndBumpsVersion() throws Exception {
        String containerId = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        createContainer(containerId, utc(72));

        MvcResult result = loadItems("alice", containerId, List.of(ev1, ev2));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("container").get("version").asLong()).isEqualTo(1);
        assertThat(body.get("evidenceKeys")).hasSize(2);

        JsonNode detail = detail(containerId);
        assertThat(detail.get("items")).hasSize(2);
        assertThat(detail.get("items").get(0).asText()).isNotBlank();
    }

    @Test
    void loadUnknownEvidenceReturns404() throws Exception {
        String containerId = uniqueKey("BOX");
        createContainer(containerId, utc(24));
        assertThat(loadItems("alice", containerId, List.of(uniqueKey("MISSING")))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void loadBorrowedEvidenceReturns409AndSameEvidenceCannotGoIntoTwoContainers()
            throws Exception {
        String containerId = uniqueKey("BOX");
        String otherContainer = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        createContainer(containerId, utc(24));
        createContainer(otherContainer, utc(24));
        loadItems("alice", containerId, List.of(ev1, ev2));

        // ev1 已在 containerId 中，装入其他容器冲突
        assertThat(loadItems("alice", otherContainer, List.of(ev1))
                .getResponse().getStatus()).isEqualTo(409);

        // ev3 借出中不可装入
        String ev3 = uniqueKey("EV");
        intake("alice", ev3);
        assertThat(borrow("alice", ev3, "bob", uniqueKey("LOAN"))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(loadItems("alice", containerId, List.of(ev3))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void setReorderAndDuplicatesAreSameParametersForIdempotency() throws Exception {
        String containerId = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        createContainer(containerId, utc(24));

        String commandKey = uniqueKey("CMD");
        MvcResult first = itemsWithKey("alice", containerId, List.of(ev1, ev2), commandKey, false);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        // 换序 + 重复元素 + 同一 commandKey：视为同参，重放首次完整结果
        MvcResult replay = itemsWithKey("alice", containerId, List.of(ev2, ev1, ev1),
                commandKey, false);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        JsonNode detail = detail(containerId);
        assertThat(detail.get("items")).hasSize(2);
    }

    private MvcResult itemsWithKey(String actor, String containerId, List<String> evidenceKeys,
                                   String commandKey, boolean unload) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("evidenceKeys", evidenceKeys);
        String path = unload ? "/api/containers/{id}/items/unload" : "/api/containers/{id}/items";
        return mockMvc.perform(post(path, containerId)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    @Test
    void unloadRemovesItemsAndRejectsUnknownMembership() throws Exception {
        String containerId = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        createContainer(containerId, utc(24));
        loadItems("alice", containerId, List.of(ev1, ev2));

        assertThat(unloadItems("alice", containerId, List.of(ev1)).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(detail(containerId).get("items")).hasSize(1);
        assertThat(unloadItems("alice", containerId, List.of(uniqueKey("EV")))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void passInspectionAllowedEarlyAndUpdatesScheduleOnly() throws Exception {
        String containerId = uniqueKey("BOX");
        createContainer(containerId, utc(24 * 30));
        // 装载一次使版本变为 1
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        loadItems("alice", containerId, List.of(ev1));

        LocalDateTime inspectedAt = utc(0);
        LocalDateTime nextAt = utc(24 * 14);
        MvcResult result = inspect("alice", containerId, uniqueKey("INSP"), 1,
                inspectedAt, nextAt, "PASS", "seal ok");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("inspection").get("result").asText()).isEqualTo("PASS");
        assertThat(body.get("container").get("status").asText()).isEqualTo("SEALED");
        assertThat(body.get("container").get("version").asLong()).isEqualTo(2);
        assertThat(body.get("snapshots")).isEmpty();

        JsonNode detail = detail(containerId);
        assertThat(detail.get("inspections")).hasSize(1);
    }

    @Test
    void nextInspectionMustBeStrictlyAfterActualTime() throws Exception {
        String containerId = uniqueKey("BOX");
        createContainer(containerId, utc(24));
        LocalDateTime at = utc(0);
        assertThat(inspect("alice", containerId, uniqueKey("INSP"), 0, at, at, "PASS", "x")
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void inspectWithStaleVersionReturns409() throws Exception {
        String containerId = uniqueKey("BOX");
        createContainer(containerId, utc(24 * 30));
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        loadItems("alice", containerId, List.of(ev1));

        // 容器当前版本 1，使用过期版本 0
        assertThat(inspect("alice", containerId, uniqueKey("INSP"), 0,
                utc(0), utc(48), "PASS", "x").getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void failInspectionRejectsBlankNote() throws Exception {
        String containerId = uniqueKey("BOX");
        createContainer(containerId, utc(24 * 30));
        assertThat(inspect("alice", containerId, uniqueKey("INSP"), 0,
                utc(0), utc(48), "FAIL", "  ").getResponse().getStatus()).isEqualTo(400);
        assertThat(inspect("alice", containerId, uniqueKey("INSP"), 0,
                utc(0), utc(48), "FAIL", null).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void failInspectionMarksContainerAndAllItemsWithPerItemSnapshots() throws Exception {
        String containerId = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        createContainer(containerId, utc(24 * 30));
        loadItems("alice", containerId, List.of(ev1, ev2));

        MvcResult result = inspect("alice", containerId, uniqueKey("INSP"), 1,
                utc(0), utc(24 * 14), "FAIL", "strap cut");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("container").get("status").asText())
                .isEqualTo("INSPECTION_FAILED");
        assertThat(body.get("snapshots")).hasSize(2);

        JsonNode detail = detail(containerId);
        assertThat(detail.get("container").get("status").asText())
                .isEqualTo("INSPECTION_FAILED");
        assertThat(detail.get("inspections")).hasSize(1);
        assertThat(detail.get("snapshots")).hasSize(2);
        JsonNode firstSnapshot = detail.get("snapshots").get(0);
        assertThat(firstSnapshot.get("evidenceStatus").asText()).isEqualTo("SEALED");
        assertThat(firstSnapshot.get("custodianId").asText()).isEqualTo("alice");
        assertThat(firstSnapshot.get("sealNo").asText()).startsWith("SEAL-");
        assertThat(firstSnapshot.get("snapshotNo").asInt()).isEqualTo(1);
        assertThat(detail.get("snapshots").get(1).get("snapshotNo").asInt()).isEqualTo(2);

        // 待核验查询
        MvcResult pending = mockMvc.perform(get("/api/containers/pending-verification")
                        .param("custodianId", "alice"))
                .andReturn();
        assertThat(pending.getResponse().getStatus()).isEqualTo(200);
        JsonNode pendingBody = objectMapper.readTree(pending.getResponse().getContentAsString());
        List<String> pendingKeys = pendingBody.findValuesAsText("evidenceKey");
        assertThat(pendingKeys).contains(ev1, ev2);
        pendingBody.forEach(node -> assertThat(node.get("status").asText())
                .isEqualTo("PENDING_VERIFICATION"));

        // 阻断原因
        JsonNode reason = blockReason(ev1);
        assertThat(reason.get("blocked").asBoolean()).isTrue();
        assertThat(reason.get("reasonCode").asText()).isEqualTo("CONTAINER_INSPECTION_FAILED");
        assertThat(reason.get("containerId").asText()).isEqualTo(containerId);
    }

    @Test
    void failInspectionRollsBackWhenAnyItemIsBorrowed() throws Exception {
        String containerId = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        createContainer(containerId, utc(24 * 30));
        loadItems("alice", containerId, List.of(ev1, ev2));

        // ev1 借出后 FAIL 巡检必须整单回滚
        assertThat(borrow("alice", ev1, "bob", uniqueKey("LOAN"))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(inspect("alice", containerId, uniqueKey("INSP"), 1,
                utc(0), utc(48), "FAIL", "cut").getResponse().getStatus()).isEqualTo(409);

        JsonNode detail = detail(containerId);
        assertThat(detail.get("container").get("status").asText()).isEqualTo("SEALED");
        assertThat(detail.get("inspections")).isEmpty();
        assertThat(detail.get("snapshots")).isEmpty();

        MvcResult chain = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/evidence/{key}/custody-chain", ev1)).andReturn();
        JsonNode evidence = objectMapper.readTree(chain.getResponse().getContentAsString())
                .get("evidence");
        assertThat(evidence.get("status").asText()).isEqualTo("BORROWED");
    }

    @Test
    void failedContainerBlocksLoanAndTransferAndFreezesCollection() throws Exception {
        String containerId = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        createContainer(containerId, utc(24 * 30));
        loadItems("alice", containerId, List.of(ev1, ev2));
        inspect("alice", containerId, uniqueKey("INSP"), 1, utc(0), utc(48), "FAIL", "cut");

        assertThat(borrow("alice", ev1, "bob", uniqueKey("LOAN"))
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(initiate("alice", ev1, "bob").getResponse().getStatus()).isEqualTo(409);
        assertThat(loadItems("alice", containerId, List.of()).getResponse().getStatus())
                .isEqualTo(400);
        String ev3 = uniqueKey("EV");
        intake("alice", ev3);
        assertThat(loadItems("alice", containerId, List.of(ev3)).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(unloadItems("alice", containerId, List.of(ev2)).getResponse().getStatus())
                .isEqualTo(409);
        // 集合未发生变化
        assertThat(detail(containerId).get("items")).hasSize(2);
    }

    @Test
    void passInspectionAfterFailDoesNotRewriteFailureOrLiftBlock() throws Exception {
        String containerId = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        createContainer(containerId, utc(24 * 30));
        loadItems("alice", containerId, List.of(ev1));
        inspect("alice", containerId, uniqueKey("INSP"), 1, utc(0), utc(48), "FAIL", "cut");

        // FAIL 后再次 PASS：仅追加记录与下次时刻，容器仍失败、历史 FAIL 与快照保留
        MvcResult pass = inspect("alice", containerId, uniqueKey("INSP"), 2,
                utc(1), utc(96), "PASS", "looks fine now");
        assertThat(pass.getResponse().getStatus()).isEqualTo(200);
        JsonNode detail = detail(containerId);
        assertThat(detail.get("container").get("status").asText())
                .isEqualTo("INSPECTION_FAILED");
        assertThat(detail.get("inspections")).hasSize(2);
        assertThat(detail.get("inspections").get(0).get("result").asText()).isEqualTo("FAIL");
        assertThat(detail.get("inspections").get(1).get("result").asText()).isEqualTo("PASS");
        assertThat(detail.get("snapshots")).hasSize(1);
        // 门禁仍在
        assertThat(borrow("alice", ev1, "bob", uniqueKey("LOAN"))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void twoDistinctCustodiansRestoreContainerAndEvidence() throws Exception {
        String containerId = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        createContainer(containerId, utc(24 * 30));
        loadItems("alice", containerId, List.of(ev1, ev2));
        inspect("alice", containerId, uniqueKey("INSP"), 1, utc(0), utc(48), "FAIL", "cut");

        // 第一名复核：仍阻断
        MvcResult first = review("alice", containerId, uniqueKey("REV"), "checked");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(firstBody.get("restored").asBoolean()).isFalse();
        assertThat(firstBody.get("distinctReviews").asInt()).isEqualTo(1);
        assertThat(firstBody.get("container").get("status").asText())
                .isEqualTo("INSPECTION_FAILED");

        // 同一保管人不能复核两次
        assertThat(review("alice", containerId, uniqueKey("REV"), "again")
                .getResponse().getStatus()).isEqualTo(409);
        // 仍阻断
        assertThat(borrow("alice", ev1, "bob", uniqueKey("LOAN"))
                .getResponse().getStatus()).isEqualTo(409);

        // 第二名不同保管人复核：恢复
        MvcResult second = review("bob", containerId, uniqueKey("REV"), "checked too");
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondBody.get("restored").asBoolean()).isTrue();
        assertThat(secondBody.get("distinctReviews").asInt()).isEqualTo(2);
        assertThat(secondBody.get("container").get("status").asText()).isEqualTo("SEALED");

        JsonNode detail = detail(containerId);
        assertThat(detail.get("reviews")).hasSize(2);
        assertThat(detail.get("snapshots")).hasSize(2);
        assertThat(blockReason(ev1).get("blocked").asBoolean()).isFalse();

        for (String ev : List.of(ev1, ev2)) {
            MvcResult chain = mockMvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/evidence/{key}/custody-chain", ev)).andReturn();
            assertThat(objectMapper.readTree(chain.getResponse().getContentAsString())
                    .get("evidence").get("status").asText()).isEqualTo("SEALED");
        }

        // 恢复后可正常借出
        assertThat(borrow("alice", ev1, "carol", uniqueKey("LOAN"))
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void reviewOnSealedContainerReturns409() throws Exception {
        String containerId = uniqueKey("BOX");
        createContainer(containerId, utc(24));
        assertThat(review("alice", containerId, uniqueKey("REV"), null)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void inspectKeyReplayReturnsFullFirstResultAndFailureDoesNotOccupyKey() throws Exception {
        String containerId = uniqueKey("BOX");
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        createContainer(containerId, utc(24 * 30));
        loadItems("alice", containerId, List.of(ev1));

        String inspectKey = uniqueKey("INSP");
        LocalDateTime inspectedAt = utc(0);
        LocalDateTime nextAt = utc(48);
        MvcResult first = inspect("alice", containerId, inspectKey, 1,
                inspectedAt, nextAt, "FAIL", "cut");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        MvcResult replay = inspect("alice", containerId, inspectKey, 1,
                inspectedAt, nextAt, "FAIL", "cut");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 重放不产生第二条巡检或快照
        JsonNode detail = detail(containerId);
        assertThat(detail.get("inspections")).hasSize(1);
        assertThat(detail.get("snapshots")).hasSize(1);

        // 同键改参返回 409
        assertThat(inspect("alice", containerId, inspectKey, 1,
                inspectedAt, utc(72), "FAIL", "cut").getResponse().getStatus()).isEqualTo(409);

        // 失败不占键：先用新键提交必然失败的 FAIL（空说明），再用同键成功提交
        String reusedKey = uniqueKey("INSP");
        // 当前容器已 FAIL，FAIL 巡检会回滚；键不应被占用
        assertThat(inspect("alice", containerId, reusedKey, 2,
                utc(1), utc(96), "FAIL", "again").getResponse().getStatus()).isEqualTo(409);
        // 双人复核恢复后，同一键可用于一次成功的 PASS 巡检（版本随复核已变化）
        review("alice", containerId, uniqueKey("REV"), null);
        review("bob", containerId, uniqueKey("REV"), null);
        long restoredVersion = detail(containerId).get("container").get("version").asLong();
        MvcResult reused = inspect("alice", containerId, reusedKey, restoredVersion,
                utc(2), utc(120), "PASS", "ok");
        assertThat(reused.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void missingContainerAndEvidenceReturn404() throws Exception {
        String missing = uniqueKey("MISSING");
        assertThat(mockMvc.perform(get("/api/containers/{id}", missing)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(
                        get("/api/containers/evidence/{key}/block-reason", missing)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(inspect("alice", missing, uniqueKey("INSP"), 0,
                utc(0), utc(48), "PASS", "x").getResponse().getStatus()).isEqualTo(404);
        assertThat(review("alice", missing, uniqueKey("REV"), null)
                .getResponse().getStatus()).isEqualTo(404);
    }
}
