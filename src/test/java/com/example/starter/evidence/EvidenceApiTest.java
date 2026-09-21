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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 证物封存交接 API 主流程与失败分支测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvidenceApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private Map<String, Object> intakeBody(String commandKey, String evidenceKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-1");
        return body;
    }

    private MvcResult intake(String actor, String commandKey, String evidenceKey) throws Exception {
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(intakeBody(commandKey, evidenceKey))))
                .andReturn();
    }

    private MvcResult initiate(String actor, String evidenceKey, String commandKey,
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

    private MvcResult decide(String actor, String evidenceKey, String commandKey,
                             String action) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return mockMvc.perform(post("/api/evidence/{key}/transfers/{action}", evidenceKey, action)
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
        body.put("note", "routine");
        return mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode chain(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void intakeCreatesSealedEvidenceWithActorAsCustodian() throws Exception {
        String evidenceKey = uniqueKey("EV");
        MvcResult result = intake("alice", uniqueKey("CMD"), evidenceKey);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("evidenceKey").asText()).isEqualTo(evidenceKey);
        assertThat(body.get("custodianId").asText()).isEqualTo("alice");
        assertThat(body.get("status").asText()).isEqualTo("SEALED");
        assertThat(body.get("caseKey").asText()).isEqualTo("CASE-1");
    }

    @Test
    void intakeDuplicateEvidenceKeyReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        assertThat(intake("alice", uniqueKey("CMD"), evidenceKey).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(intake("alice", uniqueKey("CMD"), evidenceKey).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void intakeIdempotentReplayReturnsFirstResult() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String commandKey = uniqueKey("CMD");
        MvcResult first = intake("alice", commandKey, evidenceKey);
        MvcResult replay = intake("alice", commandKey, evidenceKey);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void intakeSameCommandKeyWithDifferentParamsReturns409() throws Exception {
        String commandKey = uniqueKey("CMD");
        assertThat(intake("alice", commandKey, uniqueKey("EV")).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(intake("alice", commandKey, uniqueKey("EV")).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void intakeWithMissingFieldsReturns400() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        MvcResult result = mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void missingActorHeaderReturns400() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                intakeBody(uniqueKey("CMD"), uniqueKey("EV")))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void fullTransferFlowSwitchesCustodianAtomically() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);

        MvcResult initiated = initiate("alice", evidenceKey, uniqueKey("CMD"), "bob");
        assertThat(initiated.getResponse().getStatus()).isEqualTo(200);
        JsonNode transfer = objectMapper.readTree(initiated.getResponse().getContentAsString());
        assertThat(transfer.get("status").asText()).isEqualTo("PENDING");
        assertThat(transfer.get("fromCustodian").asText()).isEqualTo("alice");
        assertThat(transfer.get("toCustodian").asText()).isEqualTo("bob");

        MvcResult accepted = decide("bob", evidenceKey, uniqueKey("CMD"), "accept");
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        JsonNode evidence = objectMapper.readTree(accepted.getResponse().getContentAsString());
        assertThat(evidence.get("custodianId").asText()).isEqualTo("bob");
        assertThat(evidence.get("status").asText()).isEqualTo("SEALED");

        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("transfers").get(0).get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(chain.get("transfers").get(0).get("decidedAt").isNull()).isFalse();
    }

    @Test
    void initiateByNonCustodianReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        assertThat(initiate("mallory", evidenceKey, uniqueKey("CMD"), "bob")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void initiateToSelfReturns400() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        assertThat(initiate("alice", evidenceKey, uniqueKey("CMD"), "alice")
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void initiateWhilePendingReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        assertThat(initiate("alice", evidenceKey, uniqueKey("CMD"), "bob")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(initiate("alice", evidenceKey, uniqueKey("CMD"), "carol")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void operateOnMissingEvidenceReturns404() throws Exception {
        String missing = uniqueKey("EV");
        assertThat(initiate("alice", missing, uniqueKey("CMD"), "bob")
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(decide("alice", missing, uniqueKey("CMD"), "accept")
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(inspect("alice", missing, uniqueKey("CMD"), true)
                .getResponse().getStatus()).isEqualTo(404);
        MvcResult chainResult = mockMvc.perform(get("/api/evidence/{key}/custody-chain", missing))
                .andReturn();
        assertThat(chainResult.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void acceptByNonDesignatedReceiverReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        initiate("alice", evidenceKey, uniqueKey("CMD"), "bob");
        assertThat(decide("carol", evidenceKey, uniqueKey("CMD"), "accept")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void receiverCannotCancelButOriginalCustodianCan() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        initiate("alice", evidenceKey, uniqueKey("CMD"), "bob");

        assertThat(decide("bob", evidenceKey, uniqueKey("CMD"), "cancel")
                .getResponse().getStatus()).isEqualTo(409);

        MvcResult cancelled = decide("alice", evidenceKey, uniqueKey("CMD"), "cancel");
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
        JsonNode evidence = objectMapper.readTree(cancelled.getResponse().getContentAsString());
        assertThat(evidence.get("custodianId").asText()).isEqualTo("alice");
        assertThat(evidence.get("status").asText()).isEqualTo("SEALED");

        // 取消后交接已关闭，接受失败且保管人不变
        assertThat(decide("bob", evidenceKey, uniqueKey("CMD"), "accept")
                .getResponse().getStatus()).isEqualTo(409);
        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("evidence").get("custodianId").asText()).isEqualTo("alice");
        assertThat(chain.get("transfers").get(0).get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void inspectionPassAppendsRecordOnly() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);

        MvcResult result = inspect("alice", evidenceKey, uniqueKey("CMD"), true);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode inspection = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(inspection.get("passed").asBoolean()).isTrue();

        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(chain.get("inspections")).hasSize(1);
    }

    @Test
    void inspectionFailureBreaksSealAndBlocksTransfers() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);

        assertThat(inspect("alice", evidenceKey, uniqueKey("CMD"), false)
                .getResponse().getStatus()).isEqualTo(200);
        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("evidence").get("status").asText()).isEqualTo("SEAL_BROKEN");

        // 封条异常：禁止发起交接，返回 422
        assertThat(initiate("alice", evidenceKey, uniqueKey("CMD"), "bob")
                .getResponse().getStatus()).isEqualTo(422);
        // 核验通过也不能恢复 SEALED
        assertThat(inspect("alice", evidenceKey, uniqueKey("CMD"), true)
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("SEAL_BROKEN");
    }

    @Test
    void acceptOnSealBrokenReturns422() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        initiate("alice", evidenceKey, uniqueKey("CMD"), "bob");
        // 待接收期间禁止核验，因此通过并发之外无法构造 PENDING + SEAL_BROKEN；
        // 这里验证待接收期间核验返回 409，且取消后核验失败再接受返回 409（无待接收交接）。
        assertThat(inspect("alice", evidenceKey, uniqueKey("CMD"), false)
                .getResponse().getStatus()).isEqualTo(409);
        decide("alice", evidenceKey, uniqueKey("CMD"), "cancel");
        inspect("alice", evidenceKey, uniqueKey("CMD"), false);
        assertThat(decide("bob", evidenceKey, uniqueKey("CMD"), "accept")
                .getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void inspectionByNonCustodianReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        assertThat(inspect("mallory", evidenceKey, uniqueKey("CMD"), true)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void transferableListsOnlyOwnSealedEvidence() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        String ev3 = uniqueKey("EV");
        String actor = "user-" + UUID.randomUUID().toString().substring(0, 8);
        intake(actor, uniqueKey("CMD"), ev1);
        intake(actor, uniqueKey("CMD"), ev2);
        intake(actor, uniqueKey("CMD"), ev3);
        initiate(actor, ev2, uniqueKey("CMD"), "bob");

        MvcResult result = mockMvc.perform(get("/api/evidence/transferable")
                        .header(ACTOR_HEADER, actor))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode list = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(list).hasSize(2);
        assertThat(list.findValuesAsText("evidenceKey")).containsExactlyInAnyOrder(ev1, ev3);
    }

    @Test
    void custodyChainAppendsHistoryInOrder() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        inspect("alice", evidenceKey, uniqueKey("CMD"), true);
        initiate("alice", evidenceKey, uniqueKey("CMD"), "bob");
        decide("bob", evidenceKey, uniqueKey("CMD"), "accept");
        inspect("bob", evidenceKey, uniqueKey("CMD"), true);

        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("evidence").get("custodianId").asText()).isEqualTo("bob");
        assertThat(chain.get("transfers")).hasSize(1);
        assertThat(chain.get("inspections")).hasSize(2);
        assertThat(chain.get("inspections").get(0).get("inspectorId").asText()).isEqualTo("alice");
        assertThat(chain.get("inspections").get(1).get("inspectorId").asText()).isEqualTo("bob");
    }

    @Test
    void transferAcceptIdempotentReplayDoesNotChangeCustodianTwice() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String acceptKey = uniqueKey("CMD");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        initiate("alice", evidenceKey, uniqueKey("CMD"), "bob");

        MvcResult first = decide("bob", evidenceKey, acceptKey, "accept");
        MvcResult replay = decide("bob", evidenceKey, acceptKey, "accept");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 重放后保管链仍只有一条交接记录
        assertThat(chain(evidenceKey).get("transfers")).hasSize(1);
    }
}
