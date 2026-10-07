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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 接触批次与污染传播 API 主流程、失败分支与幂等测试。
 * 合成场景：A 接 B（批次一），B 接 C（批次二）；
 * 从 A 在第一批污染隔离 A、B、C，从 B 在第二批污染只隔离 B、C，不逆向追溯 A。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContactContaminationApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private String uniqueActor() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void intake(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-1");
        MvcResult result = mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    private MvcResult contactBatch(String actor, String commandKey,
                                   List<String> evidenceKeys) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("evidenceKeys", evidenceKeys);
        return mockMvc.perform(post("/api/evidence/contact-batches")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private long registerBatch(String actor, String... evidenceKeys) throws Exception {
        MvcResult result = contactBatch(actor, uniqueKey("CMD"), List.of(evidenceKeys));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("eventSeq").asLong();
    }

    private MvcResult contaminate(String actor, String commandKey, String evidenceKey,
                                  long originEventSeq) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("evidenceKey", evidenceKey);
        body.put("originEventSeq", originEventSeq);
        return mockMvc.perform(post("/api/evidence/contaminations")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode evidenceNode(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("evidence");
    }

    private MvcResult borrow(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", "borrower-1");
        body.put("purpose", "analysis");
        body.put("dueAt", java.time.LocalDateTime.now().plusHours(24).toString());
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

    @Test
    void contactBatchRegistersWithIncreasingEventSeq() throws Exception {
        String actor = uniqueActor();
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        String ev3 = uniqueKey("EV");
        intake(actor, ev1);
        intake(actor, ev2);
        intake(actor, ev3);

        long seq1 = registerBatch(actor, ev1, ev2);
        long seq2 = registerBatch(actor, ev2, ev3);
        assertThat(seq2).isGreaterThan(seq1);
    }

    @Test
    void contactBatchRejectsInvalidMemberCount() throws Exception {
        String actor = uniqueActor();
        String ev1 = uniqueKey("EV");
        intake(actor, ev1);
        // 单成员不足 2 件
        assertThat(contactBatch(actor, uniqueKey("CMD"), List.of(ev1))
                .getResponse().getStatus()).isEqualTo(400);
        // 超过 5 件
        List<String> six = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> uniqueKey("EV")).toList();
        for (String key : six) {
            intake(actor, key);
        }
        assertThat(contactBatch(actor, uniqueKey("CMD"), six)
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void contactBatchRejectsDuplicateMembers() throws Exception {
        String actor = uniqueActor();
        String ev1 = uniqueKey("EV");
        intake(actor, ev1);
        assertThat(contactBatch(actor, uniqueKey("CMD"), List.of(ev1, ev1))
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void contactBatchByNonCustodianReturns409() throws Exception {
        String actor = uniqueActor();
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake(actor, ev1);
        intake(actor, ev2);
        assertThat(contactBatch("mallory", uniqueKey("CMD"), List.of(ev1, ev2))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void contactBatchRejectsBorrowedPendingAndContaminatedMembers() throws Exception {
        String actor = uniqueActor();
        String borrowed = uniqueKey("EV");
        String pending = uniqueKey("EV");
        String tainted = uniqueKey("EV");
        String partner = uniqueKey("EV");
        String clean = uniqueKey("EV");
        intake(actor, borrowed);
        intake(actor, pending);
        intake(actor, tainted);
        intake(actor, partner);
        intake(actor, clean);

        // 借出中对象不得参加
        assertThat(borrow(actor, borrowed).getResponse().getStatus()).isEqualTo(200);
        assertThat(contactBatch(actor, uniqueKey("CMD"), List.of(borrowed, clean))
                .getResponse().getStatus()).isEqualTo(409);
        // 待交接对象不得参加
        assertThat(initiate(actor, pending, "bob").getResponse().getStatus()).isEqualTo(200);
        assertThat(contactBatch(actor, uniqueKey("CMD"), List.of(pending, clean))
                .getResponse().getStatus()).isEqualTo(409);
        // 已隔离对象不得参加
        long seq = registerBatch(actor, tainted, partner);
        assertThat(contaminate(actor, uniqueKey("CMD"), tainted, seq)
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(contactBatch(actor, uniqueKey("CMD"), List.of(tainted, clean))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void contactBatchIdempotentReplayAndChangedParams() throws Exception {
        String actor = uniqueActor();
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        String ev3 = uniqueKey("EV");
        intake(actor, ev1);
        intake(actor, ev2);
        intake(actor, ev3);
        String commandKey = uniqueKey("CMD");

        MvcResult first = contactBatch(actor, commandKey, List.of(ev1, ev2));
        MvcResult replay = contactBatch(actor, commandKey, List.of(ev1, ev2));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 同键改参 409
        assertThat(contactBatch(actor, commandKey, List.of(ev1, ev3))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void contaminationFromFirstBatchIsolatesAllDownstream() throws Exception {
        String actor = uniqueActor();
        String evA = uniqueKey("EV-A");
        String evB = uniqueKey("EV-B");
        String evC = uniqueKey("EV-C");
        intake(actor, evA);
        intake(actor, evB);
        intake(actor, evC);
        long batch1 = registerBatch(actor, evA, evB);
        registerBatch(actor, evB, evC);

        MvcResult result = contaminate(actor, uniqueKey("CMD"), evA, batch1);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("contaminatedKeys"))
                .containsExactlyInAnyOrder(
                        objectMapper.valueToTree(evA),
                        objectMapper.valueToTree(evB),
                        objectMapper.valueToTree(evC));
        assertThat(evidenceNode(evA).get("contaminated").asBoolean()).isTrue();
        assertThat(evidenceNode(evB).get("contaminated").asBoolean()).isTrue();
        assertThat(evidenceNode(evC).get("contaminated").asBoolean()).isTrue();
    }

    @Test
    void contaminationFromSecondBatchDoesNotBacktrack() throws Exception {
        String actor = uniqueActor();
        String evA = uniqueKey("EV-A");
        String evB = uniqueKey("EV-B");
        String evC = uniqueKey("EV-C");
        intake(actor, evA);
        intake(actor, evB);
        intake(actor, evC);
        registerBatch(actor, evA, evB);
        long batch2 = registerBatch(actor, evB, evC);

        MvcResult result = contaminate(actor, uniqueKey("CMD"), evB, batch2);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("contaminatedKeys"))
                .containsExactlyInAnyOrder(
                        objectMapper.valueToTree(evB),
                        objectMapper.valueToTree(evC));
        // 之前批次不追溯：A 保持未污染
        assertThat(evidenceNode(evA).get("contaminated").asBoolean()).isFalse();
        assertThat(evidenceNode(evB).get("contaminated").asBoolean()).isTrue();
        assertThat(evidenceNode(evC).get("contaminated").asBoolean()).isTrue();
    }

    @Test
    void contaminationByNonCustodianReturns403BeforeReplay() throws Exception {
        String actor = uniqueActor();
        String evA = uniqueKey("EV-A");
        String evB = uniqueKey("EV-B");
        intake(actor, evA);
        intake(actor, evB);
        long batch = registerBatch(actor, evA, evB);

        // 越权登记 403
        assertThat(contaminate("mallory", uniqueKey("CMD"), evA, batch)
                .getResponse().getStatus()).isEqualTo(403);
        // 越权 403 先于重放：同一 commandKey 已被 actor 成功使用，越权者仍得到 403
        String commandKey = uniqueKey("CMD");
        assertThat(contaminate(actor, commandKey, evA, batch)
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(contaminate("mallory", commandKey, evA, batch)
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void contaminationWithUnknownEventSeqOrEvidenceReturns404() throws Exception {
        String actor = uniqueActor();
        String evA = uniqueKey("EV-A");
        String evB = uniqueKey("EV-B");
        intake(actor, evA);
        intake(actor, evB);
        long batch = registerBatch(actor, evA, evB);

        assertThat(contaminate(actor, uniqueKey("CMD"), evA, batch + 9999)
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(contaminate(actor, uniqueKey("CMD"), uniqueKey("EV-X"), batch)
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void contaminatedEvidenceBlockedFromLoanTransferAndNewContact() throws Exception {
        String actor = uniqueActor();
        String evA = uniqueKey("EV-A");
        String evB = uniqueKey("EV-B");
        String clean = uniqueKey("EV");
        intake(actor, evA);
        intake(actor, evB);
        intake(actor, clean);
        long batch = registerBatch(actor, evA, evB);
        assertThat(contaminate(actor, uniqueKey("CMD"), evA, batch)
                .getResponse().getStatus()).isEqualTo(201);

        // 阻断借出与发起交接（422），阻断新接触（409）
        assertThat(borrow(actor, evA).getResponse().getStatus()).isEqualTo(422);
        assertThat(initiate(actor, evB, "bob").getResponse().getStatus()).isEqualTo(422);
        assertThat(contactBatch(actor, uniqueKey("CMD"), List.of(evB, clean))
                .getResponse().getStatus()).isEqualTo(409);
        // 污染不可解除：核验通过后污染标记仍在
        Map<String, Object> inspectBody = new LinkedHashMap<>();
        inspectBody.put("commandKey", uniqueKey("CMD"));
        inspectBody.put("passed", true);
        mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evA)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(inspectBody)))
                .andReturn();
        assertThat(evidenceNode(evA).get("contaminated").asBoolean()).isTrue();
    }

    @Test
    void contaminationKeepsSealBrokenStatusSeparately() throws Exception {
        String actor = uniqueActor();
        String evA = uniqueKey("EV-A");
        String evB = uniqueKey("EV-B");
        intake(actor, evA);
        intake(actor, evB);
        long batch = registerBatch(actor, evA, evB);
        // 批次登记后 A 封条核验失败进入 SEAL_BROKEN
        Map<String, Object> inspectBody = new LinkedHashMap<>();
        inspectBody.put("commandKey", uniqueKey("CMD"));
        inspectBody.put("passed", false);
        inspectBody.put("note", "cracked");
        mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evA)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(inspectBody)))
                .andReturn();
        // 从 B 污染传播到 A：污染标记置位，但 SEAL_BROKEN 状态分别保存，不被改写
        assertThat(contaminate(actor, uniqueKey("CMD"), evB, batch)
                .getResponse().getStatus()).isEqualTo(201);
        JsonNode evidenceA = evidenceNode(evA);
        assertThat(evidenceA.get("contaminated").asBoolean()).isTrue();
        assertThat(evidenceA.get("status").asText()).isEqualTo("SEAL_BROKEN");
    }

    @Test
    void contaminationAffectsEvidenceTransferredAwayLater() throws Exception {
        String actor = uniqueActor();
        String evA = uniqueKey("EV-A");
        String evB = uniqueKey("EV-B");
        intake(actor, evA);
        intake(actor, evB);
        long batch = registerBatch(actor, evA, evB);
        // 批次登记后 A 交接给 bob
        assertThat(initiate(actor, evA, "bob").getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> acceptBody = new LinkedHashMap<>();
        acceptBody.put("commandKey", uniqueKey("CMD"));
        mockMvc.perform(post("/api/evidence/{key}/transfers/accept", evA)
                        .header(ACTOR_HEADER, "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptBody)))
                .andReturn();
        // 从 B 污染：传播影响已交出的 A（当前保管人已是 bob）
        assertThat(contaminate(actor, uniqueKey("CMD"), evB, batch)
                .getResponse().getStatus()).isEqualTo(201);
        JsonNode evidenceA = evidenceNode(evA);
        assertThat(evidenceA.get("custodianId").asText()).isEqualTo("bob");
        assertThat(evidenceA.get("contaminated").asBoolean()).isTrue();
    }

    @Test
    void contaminationIdempotentReplayChangedParamsAndFailureNotConsumingKey() throws Exception {
        String actor = uniqueActor();
        String evA = uniqueKey("EV-A");
        String evB = uniqueKey("EV-B");
        String evC = uniqueKey("EV-C");
        intake(actor, evA);
        intake(actor, evB);
        intake(actor, evC);
        long batch = registerBatch(actor, evA, evB);

        // 同键同参重放返回首次完整结果，污染事件不重复
        String commandKey = uniqueKey("CMD");
        MvcResult first = contaminate(actor, commandKey, evA, batch);
        MvcResult replay = contaminate(actor, commandKey, evA, batch);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 同键改参 409
        assertThat(contaminate(actor, commandKey, evB, batch)
                .getResponse().getStatus()).isEqualTo(409);
        // 失败不占键：先用新键触发 404，再同键换正确参数成功
        String failKey = uniqueKey("CMD");
        assertThat(contaminate(actor, failKey, evC, batch + 9999)
                .getResponse().getStatus()).isEqualTo(404);
        String evD = uniqueKey("EV-D");
        intake(actor, evD);
        long batch2 = registerBatch(actor, evC, evD);
        assertThat(contaminate(actor, failKey, evC, batch2)
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void contaminationDetailReturnsFrozenPropagationPath() throws Exception {
        String actor = uniqueActor();
        String evA = uniqueKey("EV-A");
        String evB = uniqueKey("EV-B");
        intake(actor, evA);
        intake(actor, evB);
        long batch = registerBatch(actor, evA, evB);
        MvcResult created = contaminate(actor, uniqueKey("CMD"), evA, batch);
        long contaminationId = objectMapper
                .readTree(created.getResponse().getContentAsString())
                .get("contaminationId").asLong();

        MvcResult detail = mockMvc.perform(
                        get("/api/evidence/contaminations/{id}", contaminationId))
                .andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(detail.getResponse().getContentAsString());
        assertThat(body.get("originEvidenceKey").asText()).isEqualTo(evA);
        assertThat(body.get("originEventSeq").asLong()).isEqualTo(batch);
        assertThat(body.get("registeredBy").asText()).isEqualTo(actor);
        assertThat(body.get("contaminatedKeys"))
                .containsExactlyInAnyOrder(
                        objectMapper.valueToTree(evA),
                        objectMapper.valueToTree(evB));

        assertThat(mockMvc.perform(get("/api/evidence/contaminations/{id}", contaminationId + 9999))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }
}
