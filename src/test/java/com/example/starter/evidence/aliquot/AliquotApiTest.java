package com.example.starter.evidence.aliquot;

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
 * 多母样联合取样主流程与失败分支的真实 H2 数据库测试。
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

    private MvcResult intake(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-ALIQUOT");
        body.put("category", "BLOOD");
        body.put("sealNo", "SEAL-" + evidenceKey);
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult registerMother(String actor, String sampleKey, long totalQty, String unit,
                                     String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("totalQty", totalQty);
        body.put("unit", unit);
        return mockMvc.perform(post("/api/evidence/{key}/mother-register", sampleKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private Map<String, Object> item(String sampleKey, long qty) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sampleKey", sampleKey);
        item.put("qty", qty);
        return item;
    }

    private MvcResult apply(String actor, String commandKey, String requestId, String aliquotKey,
                            List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("requestId", requestId);
        body.put("aliquotKey", aliquotKey);
        body.put("items", items);
        return mockMvc.perform(post("/api/sampling")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult review(String endpoint, String actor, String requestId, String commandKey,
                             Long requestVersion, Map<String, Long> sampleVersions,
                             String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        if (note != null) {
            body.put("note", note);
        }
        if (requestVersion != null) {
            body.put("requestVersion", requestVersion);
        }
        if (sampleVersions != null) {
            body.put("sampleVersions", sampleVersions);
        }
        return mockMvc.perform(post("/api/sampling/{requestId}/{endpoint}", requestId, endpoint)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult confirmFirst(String actor, String requestId) throws Exception {
        return review("confirm", actor, requestId, uniqueKey("CMD"), null, null, "first");
    }

    private MvcResult confirmSecond(String actor, String requestId, long requestVersion,
                                    Map<String, Long> sampleVersions) throws Exception {
        return review("confirm", actor, requestId, uniqueKey("CMD"), requestVersion,
                sampleVersions, "second");
    }

    private JsonNode mother(String sampleKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/mother", sampleKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode order(String requestId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/sampling/{requestId}", requestId))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode chain(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * 准备两件由 alice 保管、已登记总量的母样。
     */
    private String[] twoMothers(long qty1, long qty2) throws Exception {
        String s1 = uniqueKey("M");
        String s2 = uniqueKey("M");
        assertThat(intake("alice", s1).getResponse().getStatus()).isEqualTo(201);
        assertThat(intake("alice", s2).getResponse().getStatus()).isEqualTo(201);
        assertThat(registerMother("alice", s1, qty1, "ML", uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(registerMother("alice", s2, qty2, "ML", uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(201);
        return new String[]{s1, s2};
    }

    @Test
    void registerMotherStoresImmutableTotalAndUnit() throws Exception {
        String sampleKey = uniqueKey("M");
        intake("alice", sampleKey);

        MvcResult result = registerMother("alice", sampleKey, 100, "ML", uniqueKey("CMD"));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("totalQty").asLong()).isEqualTo(100);
        assertThat(body.get("unit").asText()).isEqualTo("ML");
        assertThat(body.get("reservedQty").asLong()).isZero();
        assertThat(body.get("consumedQty").asLong()).isZero();
        assertThat(body.get("availableQty").asLong()).isEqualTo(100);
        assertThat(body.get("version").asLong()).isZero();

        // 重复登记被拒绝，总量不可修改
        assertThat(registerMother("alice", sampleKey, 50, "G", uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(409);
        JsonNode view = mother(sampleKey);
        assertThat(view.get("totalQty").asLong()).isEqualTo(100);
        assertThat(view.get("unit").asText()).isEqualTo("ML");
    }

    @Test
    void registerMotherByNonCustodianReturns409() throws Exception {
        String sampleKey = uniqueKey("M");
        intake("alice", sampleKey);
        assertThat(registerMother("mallory", sampleKey, 10, "ML", uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void registerMotherWithNonPositiveQtyReturns400() throws Exception {
        String sampleKey = uniqueKey("M");
        intake("alice", sampleKey);
        assertThat(registerMother("alice", sampleKey, 0, "ML", uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void fullFlowReservesConfumesAndCreatesSealedAliquotWithImmutableMapping() throws Exception {
        String[] mothers = twoMothers(100, 50);
        String requestId = uniqueKey("REQ");
        String aliquotKey = uniqueKey("ALQ");

        MvcResult applied = apply("alice", uniqueKey("CMD"), requestId, aliquotKey,
                List.of(item(mothers[0], 30), item(mothers[1], 20)));
        assertThat(applied.getResponse().getStatus()).isEqualTo(201);
        JsonNode orderAfterApply = objectMapper.readTree(
                applied.getResponse().getContentAsString());
        assertThat(orderAfterApply.get("status").asText()).isEqualTo("PENDING");
        assertThat(orderAfterApply.get("version").asLong()).isZero();
        assertThat(orderAfterApply.get("items")).hasSize(2);

        assertThat(mother(mothers[0]).get("reservedQty").asLong()).isEqualTo(30);
        assertThat(mother(mothers[0]).get("availableQty").asLong()).isEqualTo(70);
        assertThat(mother(mothers[1]).get("reservedQty").asLong()).isEqualTo(20);
        assertThat(mother(mothers[1]).get("availableQty").asLong()).isEqualTo(30);

        // 两名不同实验审核人，且都不是保管人 alice
        assertThat(confirmFirst("bob", requestId).getResponse().getStatus()).isEqualTo(200);
        JsonNode pending = order(requestId);
        assertThat(pending.get("version").asLong()).isEqualTo(1);

        Map<String, Long> versions = new LinkedHashMap<>();
        versions.put(mothers[0], pending.get("items").get(0).get("sampleVersion").asLong());
        versions.put(mothers[1], pending.get("items").get(1).get("sampleVersion").asLong());
        MvcResult confirmed = confirmSecond("carol", requestId, 1, versions);
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        JsonNode done = objectMapper.readTree(confirmed.getResponse().getContentAsString());
        assertThat(done.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(done.get("version").asLong()).isEqualTo(2);
        assertThat(done.get("mappings")).hasSize(2);

        // 预留一次转为耗用
        JsonNode m1 = mother(mothers[0]);
        assertThat(m1.get("reservedQty").asLong()).isZero();
        assertThat(m1.get("consumedQty").asLong()).isEqualTo(30);
        assertThat(m1.get("availableQty").asLong()).isEqualTo(70);
        JsonNode m2 = mother(mothers[1]);
        assertThat(m2.get("reservedQty").asLong()).isZero();
        assertThat(m2.get("consumedQty").asLong()).isEqualTo(20);
        assertThat(m2.get("availableQty").asLong()).isEqualTo(30);

        // 生成 SEALED 子样，保管人为申请保管人，独立保管链
        JsonNode aliquotChain = chain(aliquotKey);
        assertThat(aliquotChain.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(aliquotChain.get("evidence").get("sampleKind").asText()).isEqualTo("ALIQUOT");
        assertThat(aliquotChain.get("evidence").get("custodianId").asText()).isEqualTo("alice");

        // 审核历史两条且按顺序
        assertThat(done.get("reviews")).hasSize(2);
        assertThat(done.get("reviews").get(0).get("reviewerId").asText()).isEqualTo("bob");
        assertThat(done.get("reviews").get(1).get("reviewerId").asText()).isEqualTo("carol");
    }

    @Test
    void insufficientBalanceRollsBackWholeOrderWithoutReservationOrKeys() throws Exception {
        String[] mothers = twoMothers(10, 100);
        String requestId = uniqueKey("REQ");
        String aliquotKey = uniqueKey("ALQ");
        String commandKey = uniqueKey("CMD");

        // 第二件余额不足：第一件取 100 > 10 不足（排序后第二件先报），整单 422
        MvcResult result = apply("alice", commandKey, requestId, aliquotKey,
                List.of(item(mothers[0], 5), item(mothers[1], 200)));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);

        // 无任何预留
        assertThat(mother(mothers[0]).get("reservedQty").asLong()).isZero();
        assertThat(mother(mothers[1]).get("reservedQty").asLong()).isZero();
        // 申请单不存在（不占 requestId）：查询 404
        assertThat(mockMvc.perform(get("/api/sampling/{requestId}", requestId))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        // 失败不占 commandKey：同键可用于另一成功申请
        String requestId2 = uniqueKey("REQ");
        String aliquotKey2 = uniqueKey("ALQ");
        assertThat(apply("alice", commandKey, requestId2, aliquotKey2,
                List.of(item(mothers[0], 5), item(mothers[1], 5))).getResponse().getStatus())
                .isEqualTo(201);
    }

    @Test
    void duplicateAliquotKeyAcrossRequestsReturns409() throws Exception {
        String[] mothersA = twoMothers(100, 100);
        String[] mothersB = twoMothers(100, 100);
        String aliquotKey = uniqueKey("ALQ");
        assertThat(apply("alice", uniqueKey("CMD"), uniqueKey("REQ"), aliquotKey,
                List.of(item(mothersA[0], 1), item(mothersA[1], 1)))
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(apply("alice", uniqueKey("CMD"), uniqueKey("REQ"), aliquotKey,
                List.of(item(mothersB[0], 1), item(mothersB[1], 1)))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void duplicateRequestIdWithDifferentParamsReturns409() throws Exception {
        String[] mothers = twoMothers(100, 100);
        String requestId = uniqueKey("REQ");
        assertThat(apply("alice", uniqueKey("CMD"), requestId, uniqueKey("ALQ"),
                List.of(item(mothers[0], 10), item(mothers[1], 10)))
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(apply("alice", uniqueKey("CMD"), requestId, uniqueKey("ALQ"),
                List.of(item(mothers[0], 20), item(mothers[1], 10)))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void sameRequestIdWithSameSetInDifferentOrderReplaysFirstResult() throws Exception {
        String[] mothers = twoMothers(100, 100);
        String requestId = uniqueKey("REQ");
        String aliquotKey = uniqueKey("ALQ");
        List<Map<String, Object>> orderA = List.of(item(mothers[0], 10), item(mothers[1], 20));
        List<Map<String, Object>> orderB = List.of(item(mothers[1], 20), item(mothers[0], 10));

        MvcResult first = apply("alice", uniqueKey("CMD"), requestId, aliquotKey, orderA);
        // 换 commandKey、换序重放
        MvcResult replay = apply("alice", uniqueKey("CMD"), requestId, aliquotKey, orderB);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 只产生一次预留
        assertThat(mother(mothers[0]).get("reservedQty").asLong()).isEqualTo(10);
        assertThat(mother(mothers[1]).get("reservedQty").asLong()).isEqualTo(20);
    }

    @Test
    void applyRejectsWrongCustodianBrokenSealBorrowedAndPendingTransfer() throws Exception {
        String good = uniqueKey("M");
        String broken = uniqueKey("M");
        String borrowed = uniqueKey("M");
        String pending = uniqueKey("M");
        intake("alice", good);
        intake("alice", broken);
        intake("alice", borrowed);
        intake("alice", pending);
        registerMother("alice", good, 100, "ML", uniqueKey("CMD"));
        registerMother("alice", broken, 100, "ML", uniqueKey("CMD"));
        registerMother("alice", borrowed, 100, "ML", uniqueKey("CMD"));
        registerMother("alice", pending, 100, "ML", uniqueKey("CMD"));

        // 非保管人申请
        assertThat(apply("mallory", uniqueKey("CMD"), uniqueKey("REQ"), uniqueKey("ALQ"),
                List.of(item(good, 1), item(broken, 1))).getResponse().getStatus())
                .isEqualTo(409);

        // 封条异常：422
        Map<String, Object> inspectBody = new LinkedHashMap<>();
        inspectBody.put("commandKey", uniqueKey("CMD"));
        inspectBody.put("passed", false);
        inspectBody.put("note", "cracked");
        mockMvc.perform(post("/api/evidence/{key}/seal-inspections", broken)
                .header(ACTOR_HEADER, "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(inspectBody)));
        assertThat(apply("alice", uniqueKey("CMD"), uniqueKey("REQ"), uniqueKey("ALQ"),
                List.of(item(good, 1), item(broken, 1))).getResponse().getStatus())
                .isEqualTo(422);

        // 借出：409
        Map<String, Object> loanBody = new LinkedHashMap<>();
        loanBody.put("commandKey", uniqueKey("CMD"));
        loanBody.put("loanKey", uniqueKey("LOAN"));
        loanBody.put("borrowerId", "bob");
        loanBody.put("purpose", "test");
        loanBody.put("dueAt", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(24)
                .toString());
        mockMvc.perform(post("/api/evidence/{key}/loans", borrowed)
                .header(ACTOR_HEADER, "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loanBody)));
        assertThat(apply("alice", uniqueKey("CMD"), uniqueKey("REQ"), uniqueKey("ALQ"),
                List.of(item(good, 1), item(borrowed, 1))).getResponse().getStatus())
                .isEqualTo(409);

        // 待接交接：409
        Map<String, Object> transferBody = new LinkedHashMap<>();
        transferBody.put("commandKey", uniqueKey("CMD"));
        transferBody.put("toCustodian", "bob");
        mockMvc.perform(post("/api/evidence/{key}/transfers", pending)
                .header(ACTOR_HEADER, "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(transferBody)));
        assertThat(apply("alice", uniqueKey("CMD"), uniqueKey("REQ"), uniqueKey("ALQ"),
                List.of(item(good, 1), item(pending, 1))).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void applyRequiresTwoToTwentyDistinctMothersAndPositiveQty() throws Exception {
        String one = uniqueKey("M");
        intake("alice", one);
        registerMother("alice", one, 100, "ML", uniqueKey("CMD"));

        // 仅一件母样：400
        assertThat(apply("alice", uniqueKey("CMD"), uniqueKey("REQ"), uniqueKey("ALQ"),
                List.of(item(one, 1))).getResponse().getStatus()).isEqualTo(400);
        // 重复母样：400
        assertThat(apply("alice", uniqueKey("CMD"), uniqueKey("REQ"), uniqueKey("ALQ"),
                List.of(item(one, 1), item(one, 2))).getResponse().getStatus()).isEqualTo(400);
        // 非正数量：400
        String second = uniqueKey("M");
        intake("alice", second);
        registerMother("alice", second, 100, "ML", uniqueKey("CMD"));
        assertThat(apply("alice", uniqueKey("CMD"), uniqueKey("REQ"), uniqueKey("ALQ"),
                List.of(item(one, 0), item(second, 1))).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void reviewerWhoIsCurrentCustodianReturns409() throws Exception {
        String[] mothers = twoMothers(100, 100);
        String requestId = uniqueKey("REQ");
        apply("alice", uniqueKey("CMD"), requestId, uniqueKey("ALQ"),
                List.of(item(mothers[0], 5), item(mothers[1], 5)));
        assertThat(confirmFirst("alice", requestId).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void sameReviewerCannotConfirmTwice() throws Exception {
        String[] mothers = twoMothers(100, 100);
        String requestId = uniqueKey("REQ");
        apply("alice", uniqueKey("CMD"), requestId, uniqueKey("ALQ"),
                List.of(item(mothers[0], 5), item(mothers[1], 5)));
        assertThat(confirmFirst("bob", requestId).getResponse().getStatus()).isEqualTo(200);
        Map<String, Long> versions = new LinkedHashMap<>();
        versions.put(mothers[0], 0L);
        versions.put(mothers[1], 0L);
        assertThat(confirmSecond("bob", requestId, 1, versions).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void secondConfirmWithWrongRequestVersionReturns409() throws Exception {
        String[] mothers = twoMothers(100, 100);
        String requestId = uniqueKey("REQ");
        apply("alice", uniqueKey("CMD"), requestId, uniqueKey("ALQ"),
                List.of(item(mothers[0], 5), item(mothers[1], 5)));
        confirmFirst("bob", requestId);
        Map<String, Long> versions = new LinkedHashMap<>();
        versions.put(mothers[0], 0L);
        versions.put(mothers[1], 0L);
        // 申请版本传 0（应为 1）
        assertThat(confirmSecond("carol", requestId, 0, versions).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void secondConfirmWithIncompleteSampleVersionsReturns409() throws Exception {
        String[] mothers = twoMothers(100, 100);
        String requestId = uniqueKey("REQ");
        apply("alice", uniqueKey("CMD"), requestId, uniqueKey("ALQ"),
                List.of(item(mothers[0], 5), item(mothers[1], 5)));
        confirmFirst("bob", requestId);
        Map<String, Long> versions = new LinkedHashMap<>();
        versions.put(mothers[0], 0L);
        assertThat(confirmSecond("carol", requestId, 1, versions).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void firstConfirmCarryingVersionsReturns400() throws Exception {
        String[] mothers = twoMothers(100, 100);
        String requestId = uniqueKey("REQ");
        apply("alice", uniqueKey("CMD"), requestId, uniqueKey("ALQ"),
                List.of(item(mothers[0], 5), item(mothers[1], 5)));
        Map<String, Long> versions = new LinkedHashMap<>();
        versions.put(mothers[0], 0L);
        versions.put(mothers[1], 0L);
        MvcResult result = review("confirm", "bob", requestId, uniqueKey("CMD"), 0L, versions, null);
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void rejectReleasesAllReservations() throws Exception {
        String[] mothers = twoMothers(100, 100);
        String requestId = uniqueKey("REQ");
        apply("alice", uniqueKey("CMD"), requestId, uniqueKey("ALQ"),
                List.of(item(mothers[0], 40), item(mothers[1], 30)));
        confirmFirst("bob", requestId);

        MvcResult rejected = review("reject", "carol", requestId, uniqueKey("CMD"),
                null, null, "denied");
        assertThat(rejected.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("REJECTED");
        assertThat(mother(mothers[0]).get("reservedQty").asLong()).isZero();
        assertThat(mother(mothers[0]).get("availableQty").asLong()).isEqualTo(100);
        assertThat(mother(mothers[1]).get("reservedQty").asLong()).isZero();

        // 终态后拒绝/确认均 409
        assertThat(review("reject", "carol", requestId, uniqueKey("CMD"), null, null, null)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(confirmFirst("bob", requestId).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void cancelBeforeAnyReviewReleasesAllReservations() throws Exception {
        String[] mothers = twoMothers(100, 100);
        String requestId = uniqueKey("REQ");
        apply("alice", uniqueKey("CMD"), requestId, uniqueKey("ALQ"),
                List.of(item(mothers[0], 40), item(mothers[1], 30)));

        // 非申请保管人不可取消
        assertThat(review("cancel", "bob", requestId, uniqueKey("CMD"), null, null, null)
                .getResponse().getStatus()).isEqualTo(409);

        MvcResult cancelled = review("cancel", "alice", requestId, uniqueKey("CMD"),
                null, null, "withdraw");
        // cancel 端点用 SamplingCancelRequest（commandKey+note），review 辅助方法多传字段无碍
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(cancelled.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(mother(mothers[0]).get("reservedQty").asLong()).isZero();
        assertThat(mother(mothers[1]).get("reservedQty").asLong()).isZero();
    }

    @Test
    void cancelAfterFirstConfirmationReturns409() throws Exception {
        String[] mothers = twoMothers(100, 100);
        String requestId = uniqueKey("REQ");
        apply("alice", uniqueKey("CMD"), requestId, uniqueKey("ALQ"),
                List.of(item(mothers[0], 5), item(mothers[1], 5)));
        confirmFirst("bob", requestId);
        assertThat(review("cancel", "alice", requestId, uniqueKey("CMD"), null, null, null)
                .getResponse().getStatus()).isEqualTo(409);
        // 预留仍在
        assertThat(mother(mothers[0]).get("reservedQty").asLong()).isEqualTo(5);
    }

    @Test
    void generatedAliquotCannotBeSampledAgainButKeepsOwnCustodyChain() throws Exception {
        String[] mothers = twoMothers(100, 100);
        String requestId = uniqueKey("REQ");
        String aliquotKey = uniqueKey("ALQ");
        apply("alice", uniqueKey("CMD"), requestId, aliquotKey,
                List.of(item(mothers[0], 10), item(mothers[1], 10)));
        confirmFirst("bob", requestId);
        JsonNode pending = order(requestId);
        Map<String, Long> versions = new LinkedHashMap<>();
        versions.put(mothers[0], pending.get("items").get(0).get("sampleVersion").asLong());
        versions.put(mothers[1], pending.get("items").get(1).get("sampleVersion").asLong());
        confirmSecond("carol", requestId, 1, versions);

        // 子样登记为母样：422
        assertThat(registerMother("alice", aliquotKey, 10, "ML", uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(422);

        // 子样可独立交接：alice -> bob
        Map<String, Object> transferBody = new LinkedHashMap<>();
        transferBody.put("commandKey", uniqueKey("CMD"));
        transferBody.put("toCustodian", "bob");
        MvcResult initiated = mockMvc.perform(post("/api/evidence/{key}/transfers", aliquotKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferBody)))
                .andReturn();
        assertThat(initiated.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> acceptBody = new LinkedHashMap<>();
        acceptBody.put("commandKey", uniqueKey("CMD"));
        MvcResult accepted = mockMvc.perform(post("/api/evidence/{key}/transfers/accept", aliquotKey)
                        .header(ACTOR_HEADER, "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptBody)))
                .andReturn();
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(aliquotKey).get("evidence").get("custodianId").asText()).isEqualTo("bob");
    }

    @Test
    void commandKeyIdempotentReplayReturnsIdenticalResponse() throws Exception {
        String[] mothers = twoMothers(100, 100);
        String requestId = uniqueKey("REQ");
        String aliquotKey = uniqueKey("ALQ");
        String commandKey = uniqueKey("CMD");
        MvcResult first = apply("alice", commandKey, requestId, aliquotKey,
                List.of(item(mothers[0], 5), item(mothers[1], 5)));
        MvcResult replay = apply("alice", commandKey, requestId, aliquotKey,
                List.of(item(mothers[0], 5), item(mothers[1], 5)));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(mother(mothers[0]).get("reservedQty").asLong()).isEqualTo(5);
    }

    @Test
    void motherRejectByCustodianReviewerReturns409EvenAfterFirstConfirmation() throws Exception {
        String[] mothers = twoMothers(100, 100);
        String requestId = uniqueKey("REQ");
        apply("alice", uniqueKey("CMD"), requestId, uniqueKey("ALQ"),
                List.of(item(mothers[0], 5), item(mothers[1], 5)));
        // 保管人本人不能拒绝
        assertThat(review("reject", "alice", requestId, uniqueKey("CMD"), null, null, null)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void cancelIdempotentReplayReleasesOnlyOnce() throws Exception {
        String[] mothers = twoMothers(100, 100);
        String requestId = uniqueKey("REQ");
        String commandKey = uniqueKey("CMD");
        apply("alice", uniqueKey("CMD"), requestId, uniqueKey("ALQ"),
                List.of(item(mothers[0], 40), item(mothers[1], 30)));
        MvcResult first = review("cancel", "alice", requestId, commandKey, null, null, "x");
        MvcResult replay = review("cancel", "alice", requestId, commandKey, null, null, "x");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(mother(mothers[0]).get("reservedQty").asLong()).isZero();
        assertThat(order(requestId).get("reviews")).hasSize(1);
    }

    @Test
    void confirmFailsWhenMotherQuantityChangedByAnotherCompletedOrder() throws Exception {
        // s1 总量 20：单 B 先预留 10（s1 版本 1），单 C 再预留 10 并抢先完成耗用（s1 版本变为 3）；
        // 此后单 B 的确认须 409，拒绝可释放其预留。
        String s1 = uniqueKey("M");
        String s2 = uniqueKey("M");
        String s3 = uniqueKey("M");
        intake("alice", s1);
        intake("alice", s2);
        intake("alice", s3);
        registerMother("alice", s1, 20, "ML", uniqueKey("CMD"));
        registerMother("alice", s2, 100, "ML", uniqueKey("CMD"));
        registerMother("alice", s3, 100, "ML", uniqueKey("CMD"));

        String reqB = uniqueKey("REQ");
        apply("alice", uniqueKey("CMD"), reqB, uniqueKey("ALQ"),
                List.of(item(s1, 10), item(s2, 5)));
        String reqC = uniqueKey("REQ");
        apply("alice", uniqueKey("CMD"), reqC, uniqueKey("ALQ"),
                List.of(item(s1, 10), item(s3, 5)));
        assertThat(mother(s1).get("reservedQty").asLong()).isEqualTo(20);

        // 单 C 抢先完成耗用
        confirmFirst("dave", reqC);
        JsonNode pendingC = order(reqC);
        Map<String, Long> versionsC = new LinkedHashMap<>();
        versionsC.put(s1, pendingC.get("items").get(0).get("sampleVersion").asLong());
        versionsC.put(s3, pendingC.get("items").get(1).get("sampleVersion").asLong());
        assertThat(confirmSecond("erin", reqC, 1, versionsC).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(mother(s1).get("consumedQty").asLong()).isEqualTo(10);

        // 单 B 第一次确认即检测到 s1 数量版本变化：409，且未耗用
        assertThat(confirmFirst("bob", reqB).getResponse().getStatus()).isEqualTo(409);
        assertThat(order(reqB).get("status").asText()).isEqualTo("PENDING");
        assertThat(mother(s2).get("reservedQty").asLong()).isEqualTo(5);

        // 拒绝释放单 B 的全部预留
        assertThat(review("reject", "carol", reqB, uniqueKey("CMD"), null, null, "deny")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(mother(s1).get("reservedQty").asLong()).isZero();
        assertThat(mother(s2).get("reservedQty").asLong()).isZero();
        assertThat(mother(s1).get("availableQty").asLong()).isEqualTo(10);
    }
}
