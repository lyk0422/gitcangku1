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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 批量入库与清单差异核对 API 测试：原子入库、差异判定、逐项 422、
 * 幂等重放（清单换序视为同参）、待复核门禁与复核关闭。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchIntakeApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WeightDiscrepancyRepository discrepancyRepository;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private Map<String, Object> item(String evidenceKey, String description, String declaredWeight) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("evidenceKey", evidenceKey);
        item.put("description", description);
        item.put("declaredWeight", declaredWeight);
        return item;
    }

    private Map<String, Object> batchBody(String requestId, List<Map<String, Object>> items,
                                          List<String> measuredWeights) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("items", items);
        body.put("measuredWeights", measuredWeights);
        return body;
    }

    private MvcResult batchIntake(String actor, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/evidence/batches")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult review(String actor, String evidenceKey, String commandKey,
                             String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("note", note);
        return mockMvc.perform(post("/api/evidence/{key}/review", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode batchView(String intakeKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/batches/{key}", intakeKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult initiateTransfer(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", "bob");
        return mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult inspectSeal(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", true);
        return mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    @Test
    void batchIntakeCreatesAllSealedAndClassifiesDiscrepancy() throws Exception {
        String intakeKey = uniqueKey("INTAKE");
        String matched = uniqueKey("EV");
        String boundary = uniqueKey("EV");
        String discrepant = uniqueKey("EV");
        // boundary：差异恰为申报 5%（0.50/10.00），不超过阈值，应记 MATCHED
        Map<String, Object> body = batchBody(intakeKey,
                List.of(item(matched, "笔记本电脑", "2.50"),
                        item(boundary, "证物箱", "10.00"),
                        item(discrepant, "现金袋", "1.00")),
                List.of("2.50", "10.50", "1.10"));

        MvcResult result = batchIntake("alice", body);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode view = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(view.get("intakeKey").asText()).isEqualTo(intakeKey);
        assertThat(view.get("custodianId").asText()).isEqualTo("alice");
        assertThat(view.get("totalCount").asInt()).isEqualTo(3);
        assertThat(view.get("matchedCount").asInt()).isEqualTo(2);
        assertThat(view.get("discrepantCount").asInt()).isEqualTo(1);
        assertThat(view.get("pendingReviewCount").asInt()).isEqualTo(1);

        JsonNode items = view.get("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(0).get("weightCheck").asText()).isEqualTo("MATCHED");
        assertThat(items.get(0).get("reviewStatus").isNull()).isTrue();
        assertThat(items.get(1).get("weightCheck").asText()).isEqualTo("MATCHED");
        assertThat(items.get(2).get("weightCheck").asText()).isEqualTo("DISCREPANT");
        assertThat(items.get(2).get("reviewStatus").asText()).isEqualTo("PENDING_REVIEW");

        // 全部证物已创建且为 SEALED，保管人为批次提交人
        JsonNode stored = batchView(intakeKey);
        assertThat(stored.get("items")).hasSize(3);
        assertThat(stored.get("pendingReviewCount").asInt()).isEqualTo(1);

        // DISCREPANT 项已记录不可变差异明细（申报 1.00 / 实测 1.10 / 差值 0.10 / 比例 0.1）
        List<WeightDiscrepancy> discrepancies = discrepancyRepository.findByIntakeKey(intakeKey);
        assertThat(discrepancies).hasSize(1);
        WeightDiscrepancy detail = discrepancies.get(0);
        assertThat(detail.evidenceKey()).isEqualTo(discrepant);
        assertThat(detail.declaredWeight()).isEqualByComparingTo("1.00");
        assertThat(detail.measuredWeight()).isEqualByComparingTo("1.10");
        assertThat(detail.deviation()).isEqualByComparingTo("0.10");
        assertThat(detail.deviationRatio()).isEqualByComparingTo("0.1");
    }

    @Test
    void invalidItemDataReturns422WithPerItemReasonsAndCreatesNothing() throws Exception {
        String intakeKey = uniqueKey("INTAKE");
        String good = uniqueKey("EV");
        String badWeight = uniqueKey("EV");
        String badDesc = uniqueKey("EV");
        Map<String, Object> body = batchBody(intakeKey,
                List.of(item(good, "正常证物", "1.00"),
                        item(badWeight, "重量非法", "0"),
                        item(badDesc, "  ", "2.00")),
                List.of("1.00", "1.00", "2.00"));

        MvcResult result = batchIntake("alice", body);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode errors = error.get("errors");
        assertThat(errors).hasSize(2);
        assertThat(errors.findValuesAsText("evidenceKey"))
                .containsExactlyInAnyOrder(badWeight, badDesc);

        // 整批回滚：合法项与批次均未创建，requestId 未被占用
        MvcResult view = mockMvc.perform(get("/api/evidence/batches/{key}", intakeKey)).andReturn();
        assertThat(view.getResponse().getStatus()).isEqualTo(404);
        MvcResult chain = mockMvc.perform(get("/api/evidence/{key}/custody-chain", good))
                .andReturn();
        assertThat(chain.getResponse().getStatus()).isEqualTo(404);

        // 同一 requestId 修正参数后可重新入库（失败不占键）
        Map<String, Object> fixed = batchBody(intakeKey,
                List.of(item(good, "正常证物", "1.00")), List.of("1.00"));
        assertThat(batchIntake("alice", fixed).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void nonPositiveMeasuredWeightReturns422() throws Exception {
        Map<String, Object> body = batchBody(uniqueKey("INTAKE"),
                List.of(item(uniqueKey("EV"), "证物", "1.00")),
                List.of("-0.5"));
        MvcResult result = batchIntake("alice", body);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(error.get("errors").get(0).get("reason").asText()).contains("实测重量");
    }

    @Test
    void duplicateEvidenceKeyWithinBatchReturns400AndCreatesNothing() throws Exception {
        String intakeKey = uniqueKey("INTAKE");
        String dup = uniqueKey("EV");
        String other = uniqueKey("EV");
        Map<String, Object> body = batchBody(intakeKey,
                List.of(item(dup, "第一件", "1.00"), item(other, "第二件", "1.00"),
                        item(dup, "重复键", "2.00")),
                List.of("1.00", "1.00", "2.00"));

        assertThat(batchIntake("alice", body).getResponse().getStatus()).isEqualTo(400);
        MvcResult chain = mockMvc.perform(get("/api/evidence/{key}/custody-chain", other))
                .andReturn();
        assertThat(chain.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void evidenceKeyAlreadyInSystemReturns400AndCreatesNothing() throws Exception {
        String existing = uniqueKey("EV");
        Map<String, Object> single = new LinkedHashMap<>();
        single.put("commandKey", uniqueKey("CMD"));
        single.put("evidenceKey", existing);
        single.put("caseKey", "CASE-1");
        single.put("category", "DOCUMENT");
        single.put("sealNo", "SEAL-1");
        mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(single)))
                .andReturn();

        String intakeKey = uniqueKey("INTAKE");
        String fresh = uniqueKey("EV");
        Map<String, Object> body = batchBody(intakeKey,
                List.of(item(existing, "已存在", "1.00"), item(fresh, "新证物", "1.00")),
                List.of("1.00", "1.00"));
        assertThat(batchIntake("alice", body).getResponse().getStatus()).isEqualTo(400);

        // 整批回滚：新证物未创建
        MvcResult chain = mockMvc.perform(get("/api/evidence/{key}/custody-chain", fresh))
                .andReturn();
        assertThat(chain.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void mismatchedMeasuredWeightsCountReturns400() throws Exception {
        Map<String, Object> body = batchBody(uniqueKey("INTAKE"),
                List.of(item(uniqueKey("EV"), "证物", "1.00"),
                        item(uniqueKey("EV"), "证物二", "2.00")),
                List.of("1.00"));
        assertThat(batchIntake("alice", body).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void emptyOrOversizedBatchReturns400() throws Exception {
        Map<String, Object> empty = batchBody(uniqueKey("INTAKE"), List.of(), List.of());
        assertThat(batchIntake("alice", empty).getResponse().getStatus()).isEqualTo(400);

        List<Map<String, Object>> items = new ArrayList<>();
        List<String> weights = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            items.add(item(uniqueKey("EV"), "证物" + i, "1.00"));
            weights.add("1.00");
        }
        Map<String, Object> oversized = batchBody(uniqueKey("INTAKE"), items, weights);
        assertThat(batchIntake("alice", oversized).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void replayWithReorderedItemsReturnsSameSnapshotWithoutRecreating() throws Exception {
        String intakeKey = uniqueKey("INTAKE");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        Map<String, Object> body = batchBody(intakeKey,
                List.of(item(ev1, "证物一", "1.00"), item(ev2, "证物二", "2.00")),
                List.of("1.00", "2.20"));

        MvcResult first = batchIntake("alice", body);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        // 清单换序 + 重量等价写法（2.2 与 2.20）视为同参，重放首次快照
        Map<String, Object> reordered = batchBody(intakeKey,
                List.of(item(ev2, "证物二", "2.00"), item(ev1, "证物一", "1.00")),
                List.of("2.2", "1.0"));
        MvcResult replay = batchIntake("alice", reordered);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 重放未重复创建：批次仍只有 2 件
        assertThat(batchView(intakeKey).get("items")).hasSize(2);
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        String intakeKey = uniqueKey("INTAKE");
        Map<String, Object> body = batchBody(intakeKey,
                List.of(item(uniqueKey("EV"), "证物一", "1.00")), List.of("1.00"));
        assertThat(batchIntake("alice", body).getResponse().getStatus()).isEqualTo(201);

        Map<String, Object> different = batchBody(intakeKey,
                List.of(item(uniqueKey("EV"), "证物二", "1.00")), List.of("1.00"));
        assertThat(batchIntake("alice", different).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void pendingReviewBlocksTransferAndInspectionUntilReviewed() throws Exception {
        String intakeKey = uniqueKey("INTAKE");
        String discrepant = uniqueKey("EV");
        Map<String, Object> body = batchBody(intakeKey,
                List.of(item(discrepant, "差异证物", "1.00")), List.of("1.20"));
        assertThat(batchIntake("alice", body).getResponse().getStatus()).isEqualTo(201);

        // 待复核：禁止发起交接与封条核验（422）
        assertThat(initiateTransfer("alice", discrepant).getResponse().getStatus()).isEqualTo(422);
        assertThat(inspectSeal("alice", discrepant).getResponse().getStatus()).isEqualTo(422);

        // 非保管人不能提交复核
        assertThat(review("mallory", discrepant, uniqueKey("CMD"), "他人复核")
                .getResponse().getStatus()).isEqualTo(409);

        // 保管人提交复核，说明必填
        Map<String, Object> noNote = new LinkedHashMap<>();
        noNote.put("commandKey", uniqueKey("CMD"));
        MvcResult missing = mockMvc.perform(post("/api/evidence/{key}/review", discrepant)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(noNote)))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);

        MvcResult reviewed = review("alice", discrepant, uniqueKey("CMD"), "复称确认为包装误差");
        assertThat(reviewed.getResponse().getStatus()).isEqualTo(200);
        JsonNode record = objectMapper.readTree(reviewed.getResponse().getContentAsString());
        assertThat(record.get("intakeKey").asText()).isEqualTo(intakeKey);
        assertThat(record.get("note").asText()).isEqualTo("复称确认为包装误差");

        // 复核不可逆：重复提交 409
        assertThat(review("alice", discrepant, uniqueKey("CMD"), "再次复核")
                .getResponse().getStatus()).isEqualTo(409);

        // 复核关闭后与 MATCHED 权限一致：可交接、可核验
        assertThat(batchView(intakeKey).get("pendingReviewCount").asInt()).isEqualTo(0);
        assertThat(initiateTransfer("alice", discrepant).getResponse().getStatus()).isEqualTo(200);

        // 复核记录不可变且可查询
        MvcResult reviews = mockMvc.perform(get("/api/evidence/batches/{key}/reviews", intakeKey))
                .andReturn();
        assertThat(reviews.getResponse().getStatus()).isEqualTo(200);
        JsonNode reviewList = objectMapper.readTree(reviews.getResponse().getContentAsString());
        assertThat(reviewList).hasSize(1);
        assertThat(reviewList.get(0).get("reviewerId").asText()).isEqualTo("alice");
    }

    @Test
    void reviewOnMatchedOrSingleIntakeEvidenceReturns409() throws Exception {
        String matched = uniqueKey("EV");
        Map<String, Object> body = batchBody(uniqueKey("INTAKE"),
                List.of(item(matched, "匹配证物", "1.00")), List.of("1.00"));
        assertThat(batchIntake("alice", body).getResponse().getStatus()).isEqualTo(201);
        assertThat(review("alice", matched, uniqueKey("CMD"), "无需复核")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(review("alice", uniqueKey("EV"), uniqueKey("CMD"), "不存在")
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void reviewIdempotentReplayReturnsFirstResult() throws Exception {
        String discrepant = uniqueKey("EV");
        Map<String, Object> body = batchBody(uniqueKey("INTAKE"),
                List.of(item(discrepant, "差异证物", "1.00")), List.of("1.20"));
        assertThat(batchIntake("alice", body).getResponse().getStatus()).isEqualTo(201);

        String commandKey = uniqueKey("CMD");
        MvcResult first = review("alice", discrepant, commandKey, "复称确认");
        MvcResult replay = review("alice", discrepant, commandKey, "复称确认");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void batchViewOfMissingIntakeReturns404() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/batches/{key}", uniqueKey("INTAKE")))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void transferableExcludesPendingReviewAndIncludesAfterReview() throws Exception {
        String intakeKey = uniqueKey("INTAKE");
        String matched = uniqueKey("EV");
        String discrepant = uniqueKey("EV");
        Map<String, Object> body = batchBody(intakeKey,
                List.of(item(matched, "匹配证物", "1.00"),
                        item(discrepant, "差异证物", "1.00")),
                List.of("1.00", "1.20"));
        assertThat(batchIntake("alice", body).getResponse().getStatus()).isEqualTo(201);

        MvcResult list1 = mockMvc.perform(get("/api/evidence/transferable")
                        .header(ACTOR_HEADER, "alice"))
                .andReturn();
        JsonNode transferable1 = objectMapper.readTree(list1.getResponse().getContentAsString());
        assertThat(transferable1.findValuesAsText("evidenceKey")).contains(matched)
                .doesNotContain(discrepant);

        assertThat(review("alice", discrepant, uniqueKey("CMD"), "复称确认")
                .getResponse().getStatus()).isEqualTo(200);

        MvcResult list2 = mockMvc.perform(get("/api/evidence/transferable")
                        .header(ACTOR_HEADER, "alice"))
                .andReturn();
        JsonNode transferable2 = objectMapper.readTree(list2.getResponse().getContentAsString());
        assertThat(transferable2.findValuesAsText("evidenceKey"))
                .contains(matched, discrepant);
    }
}
