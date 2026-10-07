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
 * 接触批次与污染传播 API 主流程、失败分支与幂等测试。
 * 合成场景：A 接 B（第一批），B 接 C（第二批）；
 * 从 A 在第一批污染隔离 A、B、C，从 B 在第二批污染只隔离 B、C。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContactApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";
    private static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");

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

    private MvcResult registerBatch(String actor, String requestId, String... keys) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("evidenceKeys", List.of(keys));
        return mockMvc.perform(post("/api/evidence/contact-batches")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult contaminate(String actor, String requestId, String evidenceKey,
                                  long startSeq) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("evidenceKey", evidenceKey);
        body.put("startSeq", startSeq);
        return mockMvc.perform(post("/api/evidence/contaminations")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult initiate(String actor, String evidenceKey, String toCustodian) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", toCustodian);
        return mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult decide(String actor, String evidenceKey, String action) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        return mockMvc.perform(post("/api/evidence/{key}/transfers/{action}", evidenceKey, action)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult borrow(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", "borrower-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("purpose", "鉴定用");
        body.put("dueAt", LocalDateTime.now(ZoneOffset.UTC).plusHours(1).toString());
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getBatch(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/contact-batches/{key}", batchKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return readJson(result);
    }

    private JsonNode getContamination(String contaminationKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/contaminations/{key}", contaminationKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return readJson(result);
    }

    private String evidenceStatus(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return readJson(result).get("evidence").get("status").asText();
    }

    /** 合成场景：custodian 持有 A/B/C，批次1{A,B}，批次2{B,C}。 */
    private record Scenario(String a, String b, String c,
                            String batch1, String batch2, long seq1, long seq2) {
    }

    private Scenario abcScenario(String custodian) throws Exception {
        String a = uniqueKey("EVA");
        String b = uniqueKey("EVB");
        String c = uniqueKey("EVC");
        intake(custodian, a);
        intake(custodian, b);
        intake(custodian, c);
        String batch1 = uniqueKey("REQ");
        String batch2 = uniqueKey("REQ");
        JsonNode r1 = readJson(registerBatch(custodian, batch1, a, b));
        JsonNode r2 = readJson(registerBatch(custodian, batch2, b, c));
        return new Scenario(a, b, c, batch1, batch2,
                r1.get("eventSeq").asLong(), r2.get("eventSeq").asLong());
    }

    @Test
    void seedSyntheticDataPresent() throws Exception {
        JsonNode batch1 = getBatch("CB-SYN-1");
        assertThat(batch1.get("eventSeq").asLong()).isEqualTo(1);
        assertThat(batch1.get("custodianId").asText()).isEqualTo("syn-custodian");
        assertThat(batch1.get("evidenceKeys")).hasSize(2);
        assertThat(batch1.get("evidenceKeys").get(0).asText()).isEqualTo("EV-SYN-A");
        assertThat(batch1.get("evidenceKeys").get(1).asText()).isEqualTo("EV-SYN-B");

        JsonNode batch2 = getBatch("CB-SYN-2");
        assertThat(batch2.get("eventSeq").asLong()).isEqualTo(2);
        assertThat(batch2.get("evidenceKeys")).hasSize(2);
        assertThat(batch2.get("evidenceKeys").get(0).asText()).isEqualTo("EV-SYN-B");
        assertThat(batch2.get("evidenceKeys").get(1).asText()).isEqualTo("EV-SYN-C");
    }

    @Test
    void registerBatchAssignsIncreasingEventSeqWithUtcTime() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String actor = "user-" + UUID.randomUUID().toString().substring(0, 8);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        String ev3 = uniqueKey("EV");
        String ev4 = uniqueKey("EV");
        intake(actor, ev1);
        intake(actor, ev2);
        intake(actor, ev3);
        intake(actor, ev4);

        MvcResult first = registerBatch(actor, uniqueKey("REQ"), ev1, ev2);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        JsonNode firstBody = readJson(first);
        long seq = firstBody.get("eventSeq").asLong();
        assertThat(firstBody.get("createdAt").asText()).isEqualTo("2026-10-01T00:00:00");
        assertThat(firstBody.get("custodianId").asText()).isEqualTo(actor);

        MvcResult second = registerBatch(actor, uniqueKey("REQ"), ev3, ev4);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(readJson(second).get("eventSeq").asLong()).isEqualTo(seq + 1);
    }

    @Test
    void registerBatchRejectsInvalidMemberCount() throws Exception {
        String actor = "user-" + UUID.randomUUID().toString().substring(0, 8);
        String ev1 = uniqueKey("EV");
        intake(actor, ev1);
        // 少于 2 件
        assertThat(registerBatch(actor, uniqueKey("REQ"), ev1).getResponse().getStatus())
                .isEqualTo(400);
        // 多于 5 件
        assertThat(registerBatch(actor, uniqueKey("REQ"), ev1, ev1, ev1, ev1, ev1, ev1)
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void registerBatchRejectsDuplicateMembers() throws Exception {
        String actor = "user-" + UUID.randomUUID().toString().substring(0, 8);
        String ev1 = uniqueKey("EV");
        intake(actor, ev1);
        assertThat(registerBatch(actor, uniqueKey("REQ"), ev1, ev1).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void registerBatchByNonCustodianReturns403() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        assertThat(registerBatch("mallory", uniqueKey("REQ"), ev1, ev2)
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void registerBatchWithMissingEvidenceReturns404() throws Exception {
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        assertThat(registerBatch("alice", uniqueKey("REQ"), ev1, uniqueKey("EV"))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void registerBatchRejectsBorrowedOrPendingMembers() throws Exception {
        String actor = "user-" + UUID.randomUUID().toString().substring(0, 8);
        String borrowed = uniqueKey("EV");
        String pending = uniqueKey("EV");
        String free = uniqueKey("EV");
        intake(actor, borrowed);
        intake(actor, pending);
        intake(actor, free);
        assertThat(borrow(actor, borrowed).getResponse().getStatus()).isEqualTo(200);
        assertThat(initiate(actor, pending, "bob").getResponse().getStatus()).isEqualTo(200);

        // 借出对象不得参加
        assertThat(registerBatch(actor, uniqueKey("REQ"), borrowed, free)
                .getResponse().getStatus()).isEqualTo(409);
        // 待交接对象不得参加
        assertThat(registerBatch(actor, uniqueKey("REQ"), pending, free)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void registerBatchIdempotentReplayAndParamConflict() throws Exception {
        String actor = "user-" + UUID.randomUUID().toString().substring(0, 8);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        String ev3 = uniqueKey("EV");
        intake(actor, ev1);
        intake(actor, ev2);
        intake(actor, ev3);
        String requestId = uniqueKey("REQ");

        MvcResult first = registerBatch(actor, requestId, ev1, ev2);
        MvcResult replay = registerBatch(actor, requestId, ev1, ev2);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 同键改参 409
        assertThat(registerBatch(actor, requestId, ev1, ev3).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void contaminationFromFirstBatchIsolatesAll() throws Exception {
        Scenario s = abcScenario("alice");
        String requestId = uniqueKey("REQ");

        MvcResult result = contaminate("alice", requestId, s.a(), s.seq1());
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = readJson(result);
        assertThat(body.get("contaminationKey").asText()).isEqualTo(requestId);
        assertThat(body.get("evidenceKey").asText()).isEqualTo(s.a());
        assertThat(body.get("startSeq").asLong()).isEqualTo(s.seq1());
        assertThat(body.get("registeredBy").asText()).isEqualTo("alice");

        // 传播路径：A 为起点（来源批次 null），B 来自批次1，C 来自批次2
        JsonNode affected = body.get("affected");
        assertThat(affected).hasSize(3);
        assertThat(affected.get(0).get("evidenceKey").asText()).isEqualTo(s.a());
        assertThat(affected.get(0).get("sourceBatchKey").isNull()).isTrue();
        assertThat(affected.get(1).get("evidenceKey").asText()).isEqualTo(s.b());
        assertThat(affected.get(1).get("sourceBatchKey").asText()).isEqualTo(s.batch1());
        assertThat(affected.get(2).get("evidenceKey").asText()).isEqualTo(s.c());
        assertThat(affected.get(2).get("sourceBatchKey").asText()).isEqualTo(s.batch2());

        // 状态为 CONTAMINATED，与 SEAL_BROKEN 分别保存
        assertThat(evidenceStatus(s.a())).isEqualTo("CONTAMINATED");
        assertThat(evidenceStatus(s.b())).isEqualTo("CONTAMINATED");
        assertThat(evidenceStatus(s.c())).isEqualTo("CONTAMINATED");

        // 历史冻结：GET 查询返回相同传播路径
        assertThat(getContamination(requestId).get("affected")).hasSize(3);
    }

    @Test
    void contaminationFromSecondBatchDoesNotTraceBack() throws Exception {
        Scenario s = abcScenario("alice");

        MvcResult result = contaminate("alice", uniqueKey("REQ"), s.b(), s.seq2());
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode affected = readJson(result).get("affected");
        assertThat(affected.findValuesAsText("evidenceKey"))
                .containsExactlyInAnyOrder(s.b(), s.c());

        // 不逆向追溯：第一批的 A 保持 SEALED
        assertThat(evidenceStatus(s.a())).isEqualTo("SEALED");
        assertThat(evidenceStatus(s.b())).isEqualTo("CONTAMINATED");
        assertThat(evidenceStatus(s.c())).isEqualTo("CONTAMINATED");
    }

    @Test
    void contaminationByNonCustodianReturns403BeforeReplay() throws Exception {
        Scenario s = abcScenario("alice");
        String requestId = uniqueKey("REQ");
        assertThat(contaminate("alice", requestId, s.a(), s.seq1()).getResponse().getStatus())
                .isEqualTo(201);

        // 越权 403 先于重放：非保管人使用同一 requestId 同参，不得命中重放或 409
        String a2 = uniqueKey("EVA");
        String b2 = uniqueKey("EVB");
        intake("carol", a2);
        intake("carol", b2);
        JsonNode batch = readJson(registerBatch("carol", uniqueKey("REQ"), a2, b2));
        long seq = batch.get("eventSeq").asLong();
        assertThat(contaminate("mallory", requestId, a2, seq).getResponse().getStatus())
                .isEqualTo(403);
        // 非保管人新键登记同样 403
        assertThat(contaminate("mallory", uniqueKey("REQ"), a2, seq).getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    void contaminationWithUnknownStartSeqReturns404() throws Exception {
        Scenario s = abcScenario("alice");
        assertThat(contaminate("alice", uniqueKey("REQ"), s.a(), 999999L)
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void contaminationOnMissingEvidenceReturns404() throws Exception {
        Scenario s = abcScenario("alice");
        assertThat(contaminate("alice", uniqueKey("REQ"), uniqueKey("EV"), s.seq1())
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void contaminationIdempotentReplayAndParamConflict() throws Exception {
        Scenario s = abcScenario("alice");
        String requestId = uniqueKey("REQ");

        MvcResult first = contaminate("alice", requestId, s.a(), s.seq1());
        MvcResult replay = contaminate("alice", requestId, s.a(), s.seq1());
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 重放不重复标记：传播路径仍为 3 条
        assertThat(getContamination(requestId).get("affected")).hasSize(3);
        // 同键改参 409
        assertThat(contaminate("alice", requestId, s.a(), s.seq2()).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void contaminatedEvidenceBlocksBorrowTransferAndNewContact() throws Exception {
        Scenario s = abcScenario("alice");
        contaminate("alice", uniqueKey("REQ"), s.a(), s.seq1());

        // 阻断借出与交接（422），且状态不是伪造的 SEAL_BROKEN
        assertThat(borrow("alice", s.a()).getResponse().getStatus()).isEqualTo(422);
        assertThat(initiate("alice", s.a(), "bob").getResponse().getStatus()).isEqualTo(422);
        assertThat(evidenceStatus(s.a())).isEqualTo("CONTAMINATED");

        // 阻断新接触：已隔离对象不得参加新批次
        String free = uniqueKey("EV");
        intake("alice", free);
        assertThat(registerBatch("alice", uniqueKey("REQ"), s.a(), free)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void contaminationAffectsEvidenceAlreadyHandedOver() throws Exception {
        Scenario s = abcScenario("alice");
        // B 在批次登记后交接给 bob（已交出）
        assertThat(initiate("alice", s.b(), "bob").getResponse().getStatus()).isEqualTo(200);
        assertThat(decide("bob", s.b(), "accept").getResponse().getStatus()).isEqualTo(200);

        // alice 仍持有起点 A，可登记；传播影响已交出的 B
        MvcResult result = contaminate("alice", uniqueKey("REQ"), s.a(), s.seq1());
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(evidenceStatus(s.b())).isEqualTo("CONTAMINATED");
        assertThat(evidenceStatus(s.c())).isEqualTo("CONTAMINATED");
    }

    @Test
    void getUnknownBatchOrContaminationReturns404() throws Exception {
        MvcResult batch = mockMvc.perform(get("/api/evidence/contact-batches/{key}", uniqueKey("REQ")))
                .andReturn();
        assertThat(batch.getResponse().getStatus()).isEqualTo(404);
        MvcResult contamination = mockMvc.perform(get("/api/evidence/contaminations/{key}", uniqueKey("REQ")))
                .andReturn();
        assertThat(contamination.getResponse().getStatus()).isEqualTo(404);
    }
}
