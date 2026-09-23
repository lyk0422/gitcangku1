package com.example.starter.aliquot;

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
 * 多母样联合取样 API 主流程、失败分支与幂等边界测试（真实 H2 MySQL 兼容库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AliquotApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private String intake(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-ALIQUOT");
        body.put("category", "BLOOD");
        body.put("sealNo", "SEAL-" + evidenceKey);
        MvcResult result = mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return evidenceKey;
    }

    private MvcResult register(String actor, String sampleKey, long total, String unit) throws Exception {
        return register(actor, sampleKey, uniqueKey("CMD"), total, unit);
    }

    private MvcResult register(String actor, String sampleKey, String commandKey,
                               long total, String unit) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("totalQuantity", total);
        body.put("unit", unit);
        return mockMvc.perform(post("/api/aliquots/samples/{key}", sampleKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private Map<String, Object> item(String sampleKey, long quantity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sampleKey", sampleKey);
        item.put("quantity", quantity);
        return item;
    }

    private MvcResult apply(String actor, String commandKey, String aliquotKey,
                           List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("aliquotKey", aliquotKey);
        body.put("items", items);
        return mockMvc.perform(post("/api/aliquots")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult confirm(String path, String actor, String aliquotKey,
                              String commandKey, Object extra) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        if (extra != null) {
            body.putAll((Map<String, Object>) extra);
        }
        return mockMvc.perform(post("/api/aliquots/{key}/" + path, aliquotKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode detail(String aliquotKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/aliquots/{key}", aliquotKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode balance(String sampleKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/aliquots/samples/{key}/balance", sampleKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void registerSampleStoresImmutableTotalAndUnit() throws Exception {
        String s1 = uniqueKey("SMP");
        intake("alice", s1);

        MvcResult result = register("alice", s1, 100, "ML");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("totalQuantity").asLong()).isEqualTo(100);
        assertThat(body.get("unit").asText()).isEqualTo("ML");
        assertThat(body.get("reserved").asLong()).isZero();
        assertThat(body.get("consumed").asLong()).isZero();
        assertThat(body.get("available").asLong()).isEqualTo(100);

        // 重复登记 409，总量不可修改
        assertThat(register("alice", s1, uniqueKey("CMD"), 50, "ML")
                .getResponse().getStatus()).isEqualTo(409);
        // 非保管人登记 409
        String s2 = uniqueKey("SMP");
        intake("alice", s2);
        assertThat(register("bob", s2, 10, "ML").getResponse().getStatus()).isEqualTo(409);
        // 非正整数 400
        assertThat(register("alice", s2, uniqueKey("CMD"), 0, "ML")
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void applyReservesAllSamplesAtomicallyAndBalanceReflectsReservation() throws Exception {
        String s1 = uniqueKey("SMP");
        String s2 = uniqueKey("SMP");
        intake("alice", s1);
        intake("alice", s2);
        register("alice", s1, 10, "ML");
        register("alice", s2, 20, "G");

        String aliquotKey = uniqueKey("ALQ");
        MvcResult result = apply("alice", uniqueKey("REQ"), aliquotKey,
                List.of(item(s1, 4), item(s2, 5)));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("RESERVED");
        assertThat(body.get("version").asLong()).isZero();
        assertThat(body.get("items")).hasSize(2);

        assertThat(balance(s1).get("reserved").asLong()).isEqualTo(4);
        assertThat(balance(s1).get("available").asLong()).isEqualTo(6);
        assertThat(balance(s2).get("reserved").asLong()).isEqualTo(5);
        assertThat(balance(s2).get("available").asLong()).isEqualTo(15);
    }

    @Test
    void applyWithInsufficientBalanceRollsBackWholeOrder() throws Exception {
        String s1 = uniqueKey("SMP");
        String s2 = uniqueKey("SMP");
        intake("alice", s1);
        intake("alice", s2);
        register("alice", s1, 10, "ML");
        register("alice", s2, 10, "ML");

        // 先预留 s1=8
        apply("alice", uniqueKey("REQ"), uniqueKey("ALQ"), List.of(item(s1, 8), item(s2, 1)));

        // 新单 s1 再取 5（不足）且 s2 取 1：整单 422，s2 不得出现预留
        MvcResult failed = apply("alice", uniqueKey("REQ"), uniqueKey("ALQ"),
                List.of(item(s1, 5), item(s2, 1)));
        assertThat(failed.getResponse().getStatus()).isEqualTo(422);
        assertThat(balance(s1).get("reserved").asLong()).isEqualTo(8);
        assertThat(balance(s2).get("reserved").asLong()).isEqualTo(1);
    }

    @Test
    void applyRejectsInvalidStatesAndChildSamples() throws Exception {
        String broken = uniqueKey("SMP");
        String borrowed = uniqueKey("SMP");
        String pending = uniqueKey("SMP");
        String ok = uniqueKey("SMP");
        intake("alice", broken);
        intake("alice", borrowed);
        intake("alice", pending);
        intake("alice", ok);
        register("alice", broken, 10, "ML");
        register("alice", borrowed, 10, "ML");
        register("alice", pending, 10, "ML");
        register("alice", ok, 10, "ML");

        // 封条异常
        Map<String, Object> inspectBody = new LinkedHashMap<>();
        inspectBody.put("commandKey", uniqueKey("CMD"));
        inspectBody.put("passed", false);
        inspectBody.put("note", "cracked");
        mockMvc.perform(post("/api/evidence/{key}/seal-inspections", broken)
                .header(ACTOR_HEADER, "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(inspectBody))).andReturn();

        // 借出
        Map<String, Object> loanBody = new LinkedHashMap<>();
        loanBody.put("commandKey", uniqueKey("CMD"));
        loanBody.put("loanKey", uniqueKey("LOAN"));
        loanBody.put("borrowerId", "bob");
        loanBody.put("purpose", "analysis");
        loanBody.put("dueAt", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(10)
                .toString());
        mockMvc.perform(post("/api/evidence/{key}/loans", borrowed)
                .header(ACTOR_HEADER, "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loanBody))).andReturn();

        // 待接收交接
        Map<String, Object> transferBody = new LinkedHashMap<>();
        transferBody.put("commandKey", uniqueKey("CMD"));
        transferBody.put("toCustodian", "carol");
        mockMvc.perform(post("/api/evidence/{key}/transfers", pending)
                .header(ACTOR_HEADER, "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(transferBody))).andReturn();

        assertThat(apply("alice", uniqueKey("REQ"), uniqueKey("ALQ"),
                List.of(item(broken, 1), item(ok, 1))).getResponse().getStatus()).isEqualTo(422);
        assertThat(apply("alice", uniqueKey("REQ"), uniqueKey("ALQ"),
                List.of(item(borrowed, 1), item(ok, 1))).getResponse().getStatus()).isEqualTo(422);
        assertThat(apply("alice", uniqueKey("REQ"), uniqueKey("ALQ"),
                List.of(item(pending, 1), item(ok, 1))).getResponse().getStatus()).isEqualTo(422);

        // 母样重复、数量非法、数量不足 2 件
        assertThat(apply("alice", uniqueKey("REQ"), uniqueKey("ALQ"),
                List.of(item(ok, 1), item(ok, 1))).getResponse().getStatus()).isEqualTo(400);
        assertThat(apply("alice", uniqueKey("REQ"), uniqueKey("ALQ"),
                List.of(item(ok, 0))).getResponse().getStatus()).isEqualTo(400);
        assertThat(apply("alice", uniqueKey("REQ"), uniqueKey("ALQ"),
                List.of(item(ok, -1), item(broken, 1))).getResponse().getStatus()).isEqualTo(400);

        // 未登记台账 422
        String unregistered = uniqueKey("SMP");
        intake("alice", unregistered);
        assertThat(apply("alice", uniqueKey("REQ"), uniqueKey("ALQ"),
                List.of(item(unregistered, 1), item(ok, 1))).getResponse().getStatus())
                .isEqualTo(422);

        // 全部失败后 ok 母样无任何预留
        assertThat(balance(ok).get("reserved").asLong()).isZero();
    }

    @Test
    void applyByNonCustodianAndReusedKeysReturn409() throws Exception {
        String s1 = uniqueKey("SMP");
        String s2 = uniqueKey("SMP");
        intake("alice", s1);
        intake("alice", s2);
        register("alice", s1, 10, "ML");
        register("alice", s2, 10, "ML");

        // 非任一母样保管人 409
        assertThat(apply("mallory", uniqueKey("REQ"), uniqueKey("ALQ"),
                List.of(item(s1, 1), item(s2, 1))).getResponse().getStatus()).isEqualTo(409);

        String aliquotKey = uniqueKey("ALQ");
        assertThat(apply("alice", uniqueKey("REQ"), aliquotKey,
                List.of(item(s1, 1), item(s2, 1))).getResponse().getStatus()).isEqualTo(201);
        // aliquotKey 换请求复用 409
        assertThat(apply("alice", uniqueKey("REQ"), aliquotKey,
                List.of(item(s1, 2), item(s2, 2))).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void fullTwoReviewerFlowConsumesReservationAndGeneratesSealedChildren() throws Exception {
        String s1 = uniqueKey("SMP");
        String s2 = uniqueKey("SMP");
        intake("alice", s1);
        intake("alice", s2);
        register("alice", s1, 10, "ML");
        register("alice", s2, 10, "ML");

        String aliquotKey = uniqueKey("ALQ");
        apply("alice", uniqueKey("REQ"), aliquotKey, List.of(item(s1, 3), item(s2, 4)));

        // 审核人是母样保管人 -> 409
        assertThat(confirm("confirmations/first", "alice", aliquotKey, uniqueKey("CMD"), null)
                .getResponse().getStatus()).isEqualTo(409);

        MvcResult first = confirm("confirmations/first", "bob", aliquotKey, uniqueKey("CMD"), null);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        JsonNode afterFirst = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(afterFirst.get("version").asLong()).isEqualTo(1);
        assertThat(afterFirst.get("reviews")).hasSize(1);
        assertThat(afterFirst.get("reviews").get(0).get("seq").asInt()).isEqualTo(1);

        // 未做第二次确认前不能再次第一次确认
        assertThat(confirm("confirmations/first", "carol", aliquotKey, uniqueKey("CMD"), null)
                .getResponse().getStatus()).isEqualTo(409);

        // 同一审核人不能二次确认
        Map<String, Object> sameReviewer = new LinkedHashMap<>();
        sameReviewer.put("requestVersion", 1L);
        sameReviewer.put("sampleVersions", Map.of(s1, 0L, s2, 0L));
        assertThat(confirm("confirmations/second", "bob", aliquotKey, uniqueKey("CMD"), sameReviewer)
                .getResponse().getStatus()).isEqualTo(409);

        // 错误申请版本
        Map<String, Object> wrongVersion = new LinkedHashMap<>();
        wrongVersion.put("requestVersion", 0L);
        wrongVersion.put("sampleVersions", Map.of(s1, 0L, s2, 0L));
        assertThat(confirm("confirmations/second", "carol", aliquotKey, uniqueKey("CMD"), wrongVersion)
                .getResponse().getStatus()).isEqualTo(409);

        // 母样版本集合不全 -> 400
        Map<String, Object> missingSamples = new LinkedHashMap<>();
        missingSamples.put("requestVersion", 1L);
        missingSamples.put("sampleVersions", Map.of(s1, 0L));
        assertThat(confirm("confirmations/second", "carol", aliquotKey, uniqueKey("CMD"), missingSamples)
                .getResponse().getStatus()).isEqualTo(400);

        // 正确二次确认
        Map<String, Object> secondBody = new LinkedHashMap<>();
        secondBody.put("requestVersion", 1L);
        secondBody.put("sampleVersions", Map.of(s1, 0L, s2, 0L));
        MvcResult second = confirm("confirmations/second", "carol", aliquotKey,
                uniqueKey("CMD"), secondBody);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        JsonNode consumed = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(consumed.get("status").asText()).isEqualTo("CONSUMED");
        assertThat(consumed.get("reviews")).hasSize(2);
        assertThat(consumed.get("consumptions")).hasSize(2);

        // 余额：预留清零、耗用累计
        JsonNode b1 = balance(s1);
        assertThat(b1.get("reserved").asLong()).isZero();
        assertThat(b1.get("consumed").asLong()).isEqualTo(3);
        assertThat(b1.get("available").asLong()).isEqualTo(7);
        JsonNode b2 = balance(s2);
        assertThat(b2.get("consumed").asLong()).isEqualTo(4);
        assertThat(b2.get("available").asLong()).isEqualTo(6);

        // 子样均为 SEALED，保管人为母样当前保管人，且不可再取样
        for (JsonNode c : consumed.get("consumptions")) {
            String childKey = c.get("childEvidenceKey").asText();
            MvcResult chain = mockMvc.perform(get("/api/evidence/{key}/custody-chain", childKey))
                    .andReturn();
            assertThat(chain.getResponse().getStatus()).isEqualTo(200);
            JsonNode child = objectMapper.readTree(chain.getResponse().getContentAsString())
                    .get("evidence");
            assertThat(child.get("status").asText()).isEqualTo("SEALED");
            assertThat(child.get("custodianId").asText()).isEqualTo("alice");

            register("alice", childKey, uniqueKey("CMD"), 10, "ML");
            assertThat(apply("alice", uniqueKey("REQ"), uniqueKey("ALQ"),
                    List.of(item(childKey, 1), item(s1, 1))).getResponse().getStatus())
                    .isEqualTo(422);
        }

        // 终结后任何审核/拒绝/取消都 409
        assertThat(confirm("confirmations/second", "dave", aliquotKey, uniqueKey("CMD"), secondBody)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(confirm("rejections", "dave", aliquotKey, uniqueKey("CMD"), null)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void secondConfirmFails409WhenMotherChangedBetweenConfirmations() throws Exception {
        String s1 = uniqueKey("SMP");
        String s2 = uniqueKey("SMP");
        intake("alice", s1);
        intake("alice", s2);
        register("alice", s1, 10, "ML");
        register("alice", s2, 10, "ML");
        String aliquotKey = uniqueKey("ALQ");
        apply("alice", uniqueKey("REQ"), aliquotKey, List.of(item(s1, 2), item(s2, 2)));
        confirm("confirmations/first", "bob", aliquotKey, uniqueKey("CMD"), null);

        // 期间把 s1 交接给 carol（保管人与版本变化）
        Map<String, Object> transferBody = new LinkedHashMap<>();
        transferBody.put("commandKey", uniqueKey("CMD"));
        transferBody.put("toCustodian", "carol");
        mockMvc.perform(post("/api/evidence/{key}/transfers", s1)
                .header(ACTOR_HEADER, "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(transferBody))).andReturn();
        Map<String, Object> acceptBody = new LinkedHashMap<>();
        acceptBody.put("commandKey", uniqueKey("CMD"));
        mockMvc.perform(post("/api/evidence/{key}/transfers/accept", s1)
                .header(ACTOR_HEADER, "carol")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(acceptBody))).andReturn();

        Map<String, Object> secondBody = new LinkedHashMap<>();
        secondBody.put("requestVersion", 1L);
        secondBody.put("sampleVersions", Map.of(s1, 0L, s2, 0L));
        // 携带旧版本 -> 409
        assertThat(confirm("confirmations/second", "dave", aliquotKey, uniqueKey("CMD"), secondBody)
                .getResponse().getStatus()).isEqualTo(409);

        // 即使携带新版本也 409：申请快照版本已偏离，且审核人 dave 不是保管人
        Map<String, Object> newVersions = new LinkedHashMap<>();
        newVersions.put("requestVersion", 1L);
        newVersions.put("sampleVersions", Map.of(s1, 2L, s2, 0L));
        assertThat(confirm("confirmations/second", "dave", aliquotKey, uniqueKey("CMD"), newVersions)
                .getResponse().getStatus()).isEqualTo(409);

        // 未耗用、无半生成：预留仍在、无映射
        JsonNode d = detail(aliquotKey);
        assertThat(d.get("status").asText()).isEqualTo("RESERVED");
        assertThat(d.get("consumptions")).isEmpty();
        assertThat(balance(s1).get("reserved").asLong()).isEqualTo(2);
    }

    @Test
    void rejectAndCancelReleaseAllReservations() throws Exception {
        String s1 = uniqueKey("SMP");
        String s2 = uniqueKey("SMP");
        intake("alice", s1);
        intake("alice", s2);
        register("alice", s1, 10, "ML");
        register("alice", s2, 10, "ML");

        // 审核前取消：非申请人 409
        String cancelKey = uniqueKey("ALQ");
        apply("alice", uniqueKey("REQ"), cancelKey, List.of(item(s1, 2), item(s2, 2)));
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("commandKey", uniqueKey("CMD"));
        assertThat(mockMvc.perform(post("/api/aliquots/{key}/cancellations", cancelKey)
                        .header(ACTOR_HEADER, "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cmd)))
                .andReturn().getResponse().getStatus()).isEqualTo(409);

        MvcResult cancelled = mockMvc.perform(post("/api/aliquots/{key}/cancellations", cancelKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cmd)))
                .andReturn();
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(cancelled.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("CANCELLED");
        assertThat(balance(s1).get("reserved").asLong()).isZero();
        assertThat(balance(s2).get("available").asLong()).isEqualTo(10);

        // 第一次确认后不能取消，只能拒绝
        String rejectKey = uniqueKey("ALQ");
        apply("alice", uniqueKey("REQ"), rejectKey, List.of(item(s1, 3), item(s2, 3)));
        confirm("confirmations/first", "bob", rejectKey, uniqueKey("CMD"), null);
        assertThat(mockMvc.perform(post("/api/aliquots/{key}/cancellations", rejectKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cmd)))
                .andReturn().getResponse().getStatus()).isEqualTo(409);

        MvcResult rejected = confirm("rejections", "carol", rejectKey, uniqueKey("CMD"), null);
        assertThat(rejected.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(rejected.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("REJECTED");
        assertThat(balance(s1).get("reserved").asLong()).isZero();
        assertThat(balance(s2).get("reserved").asLong()).isZero();

        // 终结后拒绝 409
        assertThat(confirm("rejections", "dave", rejectKey, uniqueKey("CMD"), null)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void idempotentReplayReorderedItemsReturnsFirstResultAndDifferentParams409() throws Exception {
        String s1 = uniqueKey("SMP");
        String s2 = uniqueKey("SMP");
        intake("alice", s1);
        intake("alice", s2);
        register("alice", s1, 10, "ML");
        register("alice", s2, 10, "ML");

        String requestId = uniqueKey("REQ");
        String aliquotKey = uniqueKey("ALQ");
        MvcResult first = apply("alice", requestId, aliquotKey, List.of(item(s1, 2), item(s2, 3)));
        // 同参集合换序重放
        MvcResult replay = apply("alice", requestId, aliquotKey, List.of(item(s2, 3), item(s1, 2)));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 重放未产生第二张单或二次预留
        assertThat(balance(s1).get("reserved").asLong()).isEqualTo(2);

        // 同键异参 409
        assertThat(apply("alice", requestId, uniqueKey("ALQ"),
                List.of(item(s1, 9), item(s2, 9))).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void failedApplyDoesNotOccupyCommandKeyOrAliquotKey() throws Exception {
        String s1 = uniqueKey("SMP");
        String s2 = uniqueKey("SMP");
        intake("alice", s1);
        intake("alice", s2);
        register("alice", s1, 10, "ML");
        register("alice", s2, 10, "ML");

        String requestId = uniqueKey("REQ");
        // 余额不足失败
        assertThat(apply("alice", requestId, uniqueKey("ALQ"),
                List.of(item(s1, 99), item(s2, 1))).getResponse().getStatus()).isEqualTo(422);
        // 失败不占 commandKey：同键可用于另一组合法请求
        String aliquotKey = uniqueKey("ALQ");
        assertThat(apply("alice", requestId, aliquotKey,
                List.of(item(s1, 1), item(s2, 1))).getResponse().getStatus()).isEqualTo(201);
    }
}
