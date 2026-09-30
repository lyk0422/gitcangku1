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
 * 容器巡检 API 主流程与失败分支测试（真实 H2 库）：
 * 双人确认封条异常一次隔离箱内全部证物、复核前禁止借出/归还/转移、
 * requestId 幂等重放与换参 409、版本不一致不改变状态、复核放行保留历史。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContainerInspectionApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private MvcResult intake(String actor, String evidenceKey) throws Exception {
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

    private MvcResult createContainer(String actor, String containerKey, String location) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("containerKey", containerKey);
        body.put("location", location);
        return mockMvc.perform(post("/api/containers")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult place(String actor, String containerKey, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        return mockMvc.perform(post("/api/containers/{key}/items", containerKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult remove(String actor, String containerKey, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        return mockMvc.perform(post("/api/containers/{key}/removals", containerKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult inspect(String actor, String containerKey, String requestId,
                              long version, boolean sealIntact) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("containerVersion", version);
        body.put("sealIntact", sealIntact);
        return mockMvc.perform(post("/api/containers/{key}/inspections", containerKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult review(String actor, String containerKey, String commandKey,
                             String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("note", note);
        return mockMvc.perform(post("/api/containers/{key}/review", containerKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode detail(String containerKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/containers/{key}", containerKey)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode chain(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String evidenceStatus(String evidenceKey) throws Exception {
        return chain(evidenceKey).get("evidence").get("status").asText();
    }

    private MvcResult borrow(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", "borrower-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("purpose", "鉴定用");
        body.put("dueAt", LocalDateTime.now().plusHours(1).withNano(0).toString());
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult initiateTransfer(String actor, String evidenceKey, String toCustodian) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", toCustodian);
        return mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    /**
     * 准备容器及箱内两件证物（保管人 alice），返回容器当前版本（2）。
     */
    private long setupContainerWithTwoItems(String containerKey, String evidenceA,
                                            String evidenceB) throws Exception {
        assertThat(createContainer("alice", containerKey, "证物室-1号柜").getResponse().getStatus())
                .isEqualTo(201);
        assertThat(intake("alice", evidenceA).getResponse().getStatus()).isEqualTo(201);
        assertThat(intake("alice", evidenceB).getResponse().getStatus()).isEqualTo(201);
        assertThat(place("alice", containerKey, evidenceA).getResponse().getStatus()).isEqualTo(200);
        assertThat(place("alice", containerKey, evidenceB).getResponse().getStatus()).isEqualTo(200);
        return 2L;
    }

    @Test
    void brokenSealConfirmedByTwoCustodiansIsolatesAllContentsUntilReview() throws Exception {
        String containerKey = uniqueKey("BOX");
        String evidenceA = uniqueKey("EV");
        String evidenceB = uniqueKey("EV");
        long version = setupContainerWithTwoItems(containerKey, evidenceA, evidenceB);
        String requestId = uniqueKey("INSP");

        // 甲首次确认：PENDING，证物状态不变
        MvcResult first = inspect("custodian-jia", containerKey, requestId, version, false);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        JsonNode pending = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(pending.get("status").asText()).isEqualTo("PENDING");
        assertThat(pending.get("firstInspector").asText()).isEqualTo("custodian-jia");
        assertThat(evidenceStatus(evidenceA)).isEqualTo("SEALED");
        assertThat(evidenceStatus(evidenceB)).isEqualTo("SEALED");

        // 乙（不同保管人）确认同一版本同一封条结果：两件证物一次隔离
        MvcResult confirmed = inspect("custodian-yi", containerKey, requestId, version, false);
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        JsonNode done = objectMapper.readTree(confirmed.getResponse().getContentAsString());
        assertThat(done.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(done.get("secondInspector").asText()).isEqualTo("custodian-yi");
        assertThat(done.get("confirmedAt").isNull()).isFalse();
        assertThat(done.get("items")).hasSize(2);
        assertThat(done.get("items").findValuesAsText("evidenceKey"))
                .containsExactlyInAnyOrder(evidenceA, evidenceB);
        assertThat(done.get("items").get(0).get("status").asText()).isEqualTo("QUARANTINED");
        assertThat(done.get("items").get(0).get("originalLocation").asText()).isEqualTo("证物室-1号柜");
        assertThat(evidenceStatus(evidenceA)).isEqualTo("QUARANTINED");
        assertThat(evidenceStatus(evidenceB)).isEqualTo("QUARANTINED");

        // 复核前：借出 A 返回 409，B 不得转移（交接与移出均 409）
        assertThat(borrow("alice", evidenceA).getResponse().getStatus()).isEqualTo(409);
        assertThat(initiateTransfer("alice", evidenceB, "bob").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(remove("alice", containerKey, evidenceB).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(place("alice", containerKey, evidenceA).getResponse().getStatus())
                .isEqualTo(409);

        // 复核放行：证物回到 SEALED，容器回到 NORMAL
        MvcResult reviewed = review("reviewer-1", containerKey, uniqueKey("CMD"), "复核通过，封条已更换");
        assertThat(reviewed.getResponse().getStatus()).isEqualTo(200);
        assertThat(evidenceStatus(evidenceA)).isEqualTo("SEALED");
        assertThat(evidenceStatus(evidenceB)).isEqualTo("SEALED");

        // 历史保留：巡检双人、封条结果、当时箱内清单、每件隔离状态与带时刻的复核结论
        JsonNode detail = detail(containerKey);
        assertThat(detail.get("container").get("status").asText()).isEqualTo("NORMAL");
        // 复核放行后证物仍在箱内
        List<String> currentItems = new ArrayList<>();
        detail.get("container").get("currentItems").forEach(node -> currentItems.add(node.asText()));
        assertThat(currentItems).containsExactlyInAnyOrder(evidenceA, evidenceB);
        JsonNode inspection = detail.get("inspections").get(0);
        assertThat(inspection.get("firstInspector").asText()).isEqualTo("custodian-jia");
        assertThat(inspection.get("secondInspector").asText()).isEqualTo("custodian-yi");
        assertThat(inspection.get("sealIntact").asBoolean()).isFalse();
        assertThat(inspection.get("containerVersion").asLong()).isEqualTo(version);
        assertThat(inspection.get("confirmedAt").isNull()).isFalse();
        assertThat(inspection.get("items")).hasSize(2);
        for (JsonNode item : inspection.get("items")) {
            assertThat(item.get("status").asText()).isEqualTo("RELEASED");
            assertThat(item.get("reviewedBy").asText()).isEqualTo("reviewer-1");
            assertThat(item.get("reviewedAt").isNull()).isFalse();
            assertThat(item.get("reviewNote").asText()).isEqualTo("复核通过，封条已更换");
            // 原位置快照不随复核改写
            assertThat(item.get("originalLocation").asText()).isEqualTo("证物室-1号柜");
        }

        // 位置轨迹不随复核改写：A 的放入记录仍在，位置快照保持
        JsonNode placements = chain(evidenceA).get("placements");
        assertThat(placements).hasSize(1);
        assertThat(placements.get(0).get("containerKey").asText()).isEqualTo(containerKey);
        assertThat(placements.get(0).get("location").asText()).isEqualTo("证物室-1号柜");
        assertThat(placements.get(0).get("removedAt").isNull()).isTrue();
    }

    @Test
    void singleConfirmationDoesNotChangeEvidenceState() throws Exception {
        String containerKey = uniqueKey("BOX");
        String evidenceA = uniqueKey("EV");
        String evidenceB = uniqueKey("EV");
        long version = setupContainerWithTwoItems(containerKey, evidenceA, evidenceB);
        String requestId = uniqueKey("INSP");

        MvcResult first = inspect("custodian-jia", containerKey, requestId, version, false);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(evidenceStatus(evidenceA)).isEqualTo("SEALED");
        assertThat(evidenceStatus(evidenceB)).isEqualTo("SEALED");
        assertThat(detail(containerKey).get("container").get("status").asText()).isEqualTo("NORMAL");

        // 同一保管人重复提交同 requestId：重放首次结果，仍不改变状态
        MvcResult replay = inspect("custodian-jia", containerKey, requestId, version, false);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(evidenceStatus(evidenceA)).isEqualTo("SEALED");
        assertThat(detail(containerKey).get("inspections")).hasSize(1);
    }

    @Test
    void replayConfirmedRequestIdReturnsFirstIsolationListAndChangedParamsReturn409() throws Exception {
        String containerKey = uniqueKey("BOX");
        String evidenceA = uniqueKey("EV");
        String evidenceB = uniqueKey("EV");
        long version = setupContainerWithTwoItems(containerKey, evidenceA, evidenceB);
        String requestId = uniqueKey("INSP");

        inspect("custodian-jia", containerKey, requestId, version, false);
        MvcResult confirmed = inspect("custodian-yi", containerKey, requestId, version, false);
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);

        // 同 requestId 同参重放（含第三人）：返回首次隔离清单
        MvcResult replay = inspect("custodian-bing", containerKey, requestId, version, false);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(confirmed.getResponse().getContentAsString());
        assertThat(detail(containerKey).get("inspections")).hasSize(1);

        // 同 requestId 换封条结果返回 409；换版本返回 409
        assertThat(inspect("custodian-bing", containerKey, requestId, version, true)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(inspect("custodian-bing", containerKey, requestId, version + 1, false)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void versionMismatchBetweenInspectorsKeepsStateUnchanged() throws Exception {
        String containerKey = uniqueKey("BOX");
        String evidenceA = uniqueKey("EV");
        String evidenceB = uniqueKey("EV");
        long version = setupContainerWithTwoItems(containerKey, evidenceA, evidenceB);
        String requestId = uniqueKey("INSP");

        assertThat(inspect("custodian-jia", containerKey, requestId, version, false)
                .getResponse().getStatus()).isEqualTo(201);
        // 第二人见证版本与首次不一致：409，不改变证物状态
        assertThat(inspect("custodian-yi", containerKey, requestId, version + 1, false)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(evidenceStatus(evidenceA)).isEqualTo("SEALED");
        assertThat(evidenceStatus(evidenceB)).isEqualTo("SEALED");
        JsonNode inspection = detail(containerKey).get("inspections").get(0);
        assertThat(inspection.get("status").asText()).isEqualTo("PENDING");
        assertThat(detail(containerKey).get("container").get("status").asText()).isEqualTo("NORMAL");
    }

    @Test
    void staleInspectionVersionAfterContentChangeDoesNotIsolate() throws Exception {
        String containerKey = uniqueKey("BOX");
        String evidenceA = uniqueKey("EV");
        String evidenceB = uniqueKey("EV");
        long version = setupContainerWithTwoItems(containerKey, evidenceA, evidenceB);
        String requestId = uniqueKey("INSP");
        assertThat(inspect("custodian-jia", containerKey, requestId, version, false)
                .getResponse().getStatus()).isEqualTo(201);

        // 确认前箱内发生变化（放入新证物，版本 +1）：见证版本过期，第二人确认 409
        String evidenceC = uniqueKey("EV");
        assertThat(intake("alice", evidenceC).getResponse().getStatus()).isEqualTo(201);
        assertThat(place("alice", containerKey, evidenceC).getResponse().getStatus()).isEqualTo(200);
        assertThat(inspect("custodian-yi", containerKey, requestId, version, false)
                .getResponse().getStatus()).isEqualTo(409);

        assertThat(evidenceStatus(evidenceA)).isEqualTo("SEALED");
        assertThat(evidenceStatus(evidenceB)).isEqualTo("SEALED");
        assertThat(evidenceStatus(evidenceC)).isEqualTo("SEALED");
        assertThat(detail(containerKey).get("container").get("status").asText()).isEqualTo("NORMAL");
    }

    @Test
    void intactSealConfirmedByTwoKeepsContainerNormal() throws Exception {
        String containerKey = uniqueKey("BOX");
        String evidenceA = uniqueKey("EV");
        String evidenceB = uniqueKey("EV");
        long version = setupContainerWithTwoItems(containerKey, evidenceA, evidenceB);
        String requestId = uniqueKey("INSP");

        assertThat(inspect("custodian-jia", containerKey, requestId, version, true)
                .getResponse().getStatus()).isEqualTo(201);
        MvcResult confirmed = inspect("custodian-yi", containerKey, requestId, version, true);
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        JsonNode done = objectMapper.readTree(confirmed.getResponse().getContentAsString());
        assertThat(done.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(done.get("items")).isEmpty();

        assertThat(evidenceStatus(evidenceA)).isEqualTo("SEALED");
        assertThat(evidenceStatus(evidenceB)).isEqualTo("SEALED");
        assertThat(detail(containerKey).get("container").get("status").asText()).isEqualTo("NORMAL");
    }

    @Test
    void reviewOnNormalContainerReturns409() throws Exception {
        String containerKey = uniqueKey("BOX");
        assertThat(createContainer("alice", containerKey, "证物室-2号柜").getResponse().getStatus())
                .isEqualTo(201);
        assertThat(review("reviewer-1", containerKey, uniqueKey("CMD"), "无需复核")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void reviewIdempotentReplayDoesNotReleaseTwice() throws Exception {
        String containerKey = uniqueKey("BOX");
        String evidenceA = uniqueKey("EV");
        String evidenceB = uniqueKey("EV");
        long version = setupContainerWithTwoItems(containerKey, evidenceA, evidenceB);
        String requestId = uniqueKey("INSP");
        inspect("custodian-jia", containerKey, requestId, version, false);
        inspect("custodian-yi", containerKey, requestId, version, false);
        assertThat(evidenceStatus(evidenceA)).isEqualTo("QUARANTINED");

        String commandKey = uniqueKey("CMD");
        MvcResult first = review("reviewer-1", containerKey, commandKey, "复核通过");
        MvcResult replay = review("reviewer-1", containerKey, commandKey, "复核通过");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 复核已完成，新命令再次复核返回 409；证物保持 SEALED
        assertThat(review("reviewer-2", containerKey, uniqueKey("CMD"), "重复复核")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(evidenceStatus(evidenceA)).isEqualTo("SEALED");
        assertThat(evidenceStatus(evidenceB)).isEqualTo("SEALED");
    }

    @Test
    void placeAndRemoveBumpVersionAndKeepPlacementTrail() throws Exception {
        String containerKey = uniqueKey("BOX");
        String evidenceA = uniqueKey("EV");
        assertThat(createContainer("alice", containerKey, "证物室-3号柜").getResponse().getStatus())
                .isEqualTo(201);
        assertThat(intake("alice", evidenceA).getResponse().getStatus()).isEqualTo(201);

        MvcResult placed = place("alice", containerKey, evidenceA);
        assertThat(placed.getResponse().getStatus()).isEqualTo(200);
        JsonNode afterPlace = objectMapper.readTree(placed.getResponse().getContentAsString());
        assertThat(afterPlace.get("version").asLong()).isEqualTo(1L);
        assertThat(afterPlace.get("currentItems")).hasSize(1);
        assertThat(afterPlace.get("currentItems").get(0).asText()).isEqualTo(evidenceA);

        // 重复放入同一容器返回 409
        assertThat(place("alice", containerKey, evidenceA).getResponse().getStatus()).isEqualTo(409);

        MvcResult removed = remove("alice", containerKey, evidenceA);
        assertThat(removed.getResponse().getStatus()).isEqualTo(200);
        JsonNode afterRemove = objectMapper.readTree(removed.getResponse().getContentAsString());
        assertThat(afterRemove.get("version").asLong()).isEqualTo(2L);
        assertThat(afterRemove.get("currentItems")).isEmpty();

        // 位置轨迹：放入一条，移出后回填 removedAt
        JsonNode placements = chain(evidenceA).get("placements");
        assertThat(placements).hasSize(1);
        assertThat(placements.get(0).get("location").asText()).isEqualTo("证物室-3号柜");
        assertThat(placements.get(0).get("removedAt").isNull()).isFalse();
    }

    @Test
    void placeFailuresReturnConflictOrNotFound() throws Exception {
        String containerKey = uniqueKey("BOX");
        String evidenceA = uniqueKey("EV");
        assertThat(createContainer("alice", containerKey, "证物室-4号柜").getResponse().getStatus())
                .isEqualTo(201);
        assertThat(intake("alice", evidenceA).getResponse().getStatus()).isEqualTo(201);

        // 非保管人放入返回 409
        assertThat(place("mallory", containerKey, evidenceA).getResponse().getStatus())
                .isEqualTo(409);
        // 放入不存在的容器返回 404
        assertThat(place("alice", uniqueKey("BOX"), evidenceA).getResponse().getStatus())
                .isEqualTo(404);
        // 不存在的证物返回 404
        assertThat(place("alice", containerKey, uniqueKey("EV")).getResponse().getStatus())
                .isEqualTo(404);
        // 借出中的证物不能放入容器
        assertThat(borrow("alice", evidenceA).getResponse().getStatus()).isEqualTo(200);
        assertThat(place("alice", containerKey, evidenceA).getResponse().getStatus())
                .isEqualTo(409);
        // 在箱证物须先移出才能借出
        String evidenceB = uniqueKey("EV");
        assertThat(intake("alice", evidenceB).getResponse().getStatus()).isEqualTo(201);
        assertThat(place("alice", containerKey, evidenceB).getResponse().getStatus()).isEqualTo(200);
        assertThat(borrow("alice", evidenceB).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void operateOnMissingContainerReturns404() throws Exception {
        String missing = uniqueKey("BOX");
        assertThat(inspect("custodian-jia", missing, uniqueKey("INSP"), 0L, false)
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(review("reviewer-1", missing, uniqueKey("CMD"), null)
                .getResponse().getStatus()).isEqualTo(404);
        MvcResult detail = mockMvc.perform(get("/api/containers/{key}", missing)).andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void duplicateContainerKeyReturns409AndCreateIsIdempotent() throws Exception {
        String containerKey = uniqueKey("BOX");
        assertThat(createContainer("alice", containerKey, "证物室-5号柜").getResponse().getStatus())
                .isEqualTo(201);
        assertThat(createContainer("alice", containerKey, "证物室-5号柜").getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void brokenSealOnEmptyContainerConfirmsWithoutIsolationItems() throws Exception {
        String containerKey = uniqueKey("BOX");
        assertThat(createContainer("alice", containerKey, "证物室-6号柜").getResponse().getStatus())
                .isEqualTo(201);
        String requestId = uniqueKey("INSP");
        assertThat(inspect("custodian-jia", containerKey, requestId, 0L, false)
                .getResponse().getStatus()).isEqualTo(201);
        MvcResult confirmed = inspect("custodian-yi", containerKey, requestId, 0L, false);
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        JsonNode done = objectMapper.readTree(confirmed.getResponse().getContentAsString());
        assertThat(done.get("items")).isEmpty();
        assertThat(detail(containerKey).get("container").get("status").asText()).isEqualTo("ABNORMAL");

        // 空容器复核放行回到 NORMAL
        assertThat(review("reviewer-1", containerKey, uniqueKey("CMD"), "空箱复核")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(detail(containerKey).get("container").get("status").asText()).isEqualTo("NORMAL");
    }
}
