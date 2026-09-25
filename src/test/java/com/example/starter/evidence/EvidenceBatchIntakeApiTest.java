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
 * 证物批量入库与清单差异核对 API 测试：原子入库、差异判定、逐项 422、
 * 待复核操作拦截、复核闭环、幂等重放与批次查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvidenceBatchIntakeApiTest {

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

    private Map<String, Object> item(String evidenceKey, String description, Object declaredWeight) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("evidenceKey", evidenceKey);
        item.put("description", description);
        item.put("declaredWeight", declaredWeight);
        return item;
    }

    private Map<String, Object> batchBody(String intakeKey, String custodian,
                                          List<Map<String, Object>> items, List<Object> measured) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("intakeKey", intakeKey);
        body.put("custodianId", custodian);
        body.put("items", items);
        body.put("measuredWeights", measured);
        return body;
    }

    private MvcResult batchIntake(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/evidence/batch-intake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult review(String actor, String evidenceKey, String commandKey,
                             String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("note", note);
        return mockMvc.perform(post("/api/evidence/{key}/weight-review", evidenceKey)
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

    private MvcResult inspect(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", true);
        return mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evidenceKey)
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

    private JsonNode chain(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void batchIntakeAllMatchedCreatesSealedEvidence() throws Exception {
        String intakeKey = uniqueKey("INTAKE");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        MvcResult result = batchIntake(batchBody(intakeKey, "alice",
                List.of(item(ev1, "物证一", "10.00"), item(ev2, "物证二", "20.00")),
                List.of("10.20", "20.50")));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("intakeKey").asText()).isEqualTo(intakeKey);
        assertThat(body.get("custodianId").asText()).isEqualTo("alice");
        assertThat(body.get("totalCount").asInt()).isEqualTo(2);
        assertThat(body.get("matchedCount").asInt()).isEqualTo(2);
        assertThat(body.get("discrepantCount").asInt()).isEqualTo(0);
        assertThat(body.get("items")).hasSize(2);
        for (JsonNode itemNode : body.get("items")) {
            assertThat(itemNode.get("status").asText()).isEqualTo("SEALED");
            assertThat(itemNode.get("weightStatus").asText()).isEqualTo("MATCHED");
            assertThat(itemNode.get("reviewStatus").asText()).isEqualTo("NONE");
        }
        // MATCHED 项直接可用：可发起交接
        assertThat(initiate("alice", ev1, "bob").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void discrepancyBoundaryExactlyFivePercentIsMatched() throws Exception {
        String evExact = uniqueKey("EV");
        String evOver = uniqueKey("EV");
        MvcResult result = batchIntake(batchBody(uniqueKey("INTAKE"), "alice",
                List.of(item(evExact, "恰好5%", "100.00"), item(evOver, "超过5%", "100.00")),
                List.of("105.00", "105.01")));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("matchedCount").asInt()).isEqualTo(1);
        assertThat(body.get("discrepantCount").asInt()).isEqualTo(1);
        assertThat(body.get("items").get(0).get("weightStatus").asText()).isEqualTo("MATCHED");
        assertThat(body.get("items").get(1).get("weightStatus").asText()).isEqualTo("DISCREPANT");
        assertThat(body.get("items").get(1).get("reviewStatus").asText()).isEqualTo("PENDING");
    }

    @Test
    void discrepantItemRecordsDetailAndBlocksOperationsUntilReviewed() throws Exception {
        String intakeKey = uniqueKey("INTAKE");
        String evOk = uniqueKey("EV");
        String evBad = uniqueKey("EV");
        MvcResult result = batchIntake(batchBody(intakeKey, "alice",
                List.of(item(evOk, "正常", "10.00"), item(evBad, "偏轻", "10.00")),
                List.of("10.00", "9.00")));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);

        // 差异明细已落库：差异 1.00 占申报 10.00 的 10%
        WeightDiscrepancy detail = discrepancyRepository.findByEvidenceKey(evBad).orElseThrow();
        assertThat(detail.intakeKey()).isEqualTo(intakeKey);
        assertThat(detail.diffPercent()).isEqualByComparingTo("10.0000");
        assertThat(discrepancyRepository.findByEvidenceKey(evOk)).isEmpty();

        // 待复核期间禁止发起交接与封条核验
        assertThat(initiate("alice", evBad, "bob").getResponse().getStatus()).isEqualTo(409);
        assertThat(inspect("alice", evBad).getResponse().getStatus()).isEqualTo(409);
        // 非保管人禁止复核
        assertThat(review("mallory", evBad, uniqueKey("CMD"), "非保管人")
                .getResponse().getStatus()).isEqualTo(409);

        // 保管人复核：写入不可变记录并关闭待复核状态
        MvcResult reviewed = review("alice", evBad, uniqueKey("CMD"), "复称确认包装损耗");
        assertThat(reviewed.getResponse().getStatus()).isEqualTo(200);
        JsonNode reviewBody = objectMapper.readTree(reviewed.getResponse().getContentAsString());
        assertThat(reviewBody.get("reviewerId").asText()).isEqualTo("alice");
        assertThat(reviewBody.get("note").asText()).isEqualTo("复称确认包装损耗");

        // 复核不可逆
        assertThat(review("alice", evBad, uniqueKey("CMD"), "再次复核")
                .getResponse().getStatus()).isEqualTo(409);

        // 关闭后与 MATCHED 权限一致：可发起交接
        assertThat(initiate("alice", evBad, "bob").getResponse().getStatus()).isEqualTo(200);

        // 批次查询反映复核状态与说明
        JsonNode view = batchView(intakeKey);
        assertThat(view.get("totalCount").asInt()).isEqualTo(2);
        assertThat(view.get("discrepantCount").asInt()).isEqualTo(1);
        assertThat(view.get("pendingReviewCount").asInt()).isEqualTo(0);
        JsonNode badItem = view.get("items").get(1);
        assertThat(badItem.get("evidenceKey").asText()).isEqualTo(evBad);
        assertThat(badItem.get("reviewStatus").asText()).isEqualTo("RESOLVED");
        assertThat(badItem.get("reviewNote").asText()).isEqualTo("复称确认包装损耗");
    }

    @Test
    void duplicateKeyWithinBatchReturns400AndCreatesNothing() throws Exception {
        String intakeKey = uniqueKey("INTAKE");
        String ev = uniqueKey("EV");
        MvcResult result = batchIntake(batchBody(intakeKey, "alice",
                List.of(item(ev, "第一件", "1.00"), item(ev, "重复键", "2.00")),
                List.of("1.00", "2.00")));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(mockMvc.perform(get("/api/evidence/{key}/custody-chain", ev))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        // 失败不占键：修正后同键可成功
        assertThat(batchIntake(batchBody(intakeKey, "alice",
                List.of(item(ev, "第一件", "1.00")), List.of("1.00")))
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void existingEvidenceKeyReturns400AndCreatesNothing() throws Exception {
        String evExisting = uniqueKey("EV");
        String evNew = uniqueKey("EV");
        assertThat(batchIntake(batchBody(uniqueKey("INTAKE"), "alice",
                List.of(item(evExisting, "首次", "1.00")), List.of("1.00")))
                .getResponse().getStatus()).isEqualTo(201);

        String intakeKey = uniqueKey("INTAKE");
        MvcResult result = batchIntake(batchBody(intakeKey, "alice",
                List.of(item(evNew, "新证物", "1.00"), item(evExisting, "已存在", "2.00")),
                List.of("1.00", "2.00")));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        // 整批回滚：新证物也未创建
        assertThat(mockMvc.perform(get("/api/evidence/{key}/custody-chain", evNew))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void invalidItemDataReturns422WithPerItemReasons() throws Exception {
        String intakeKey = uniqueKey("INTAKE");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        String ev3 = uniqueKey("EV");
        MvcResult result = batchIntake(batchBody(intakeKey, "alice",
                List.of(item(ev1, "", "10.00"),       // 描述为空
                        item(ev2, "重量非正", "0.00"),   // 申报重量非正
                        item(ev3, "正常", "5.00")),
                List.of("10.00", "1.00", "-1.00")));  // 实测重量非正

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode errors = body.get("errors");
        assertThat(errors).hasSize(3);
        assertThat(errors.findValuesAsText("reason"))
                .anyMatch(r -> r.contains("描述"))
                .anyMatch(r -> r.contains("申报重量"))
                .anyMatch(r -> r.contains("实测重量"));
        // 整批不创建任何证物
        for (String ev : List.of(ev1, ev2, ev3)) {
            assertThat(mockMvc.perform(get("/api/evidence/{key}/custody-chain", ev))
                    .andReturn().getResponse().getStatus()).isEqualTo(404);
        }
        // 失败不占键：修正后同键成功
        assertThat(batchIntake(batchBody(intakeKey, "alice",
                List.of(item(ev1, "已修正", "10.00")), List.of("10.00")))
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void mismatchedMeasuredWeightsReturns400() throws Exception {
        MvcResult result = batchIntake(batchBody(uniqueKey("INTAKE"), "alice",
                List.of(item(uniqueKey("EV"), "物证", "1.00")),
                List.of("1.00", "2.00")));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void emptyOrOversizedBatchReturns400() throws Exception {
        assertThat(batchIntake(batchBody(uniqueKey("INTAKE"), "alice",
                List.of(), List.of())).getResponse().getStatus()).isEqualTo(400);

        List<Map<String, Object>> items = new ArrayList<>();
        List<Object> measured = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            items.add(item(uniqueKey("EV"), "物证" + i, "1.00"));
            measured.add("1.00");
        }
        assertThat(batchIntake(batchBody(uniqueKey("INTAKE"), "alice", items, measured))
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void replayWithReorderedItemsReturnsFirstSnapshotWithoutDuplicates() throws Exception {
        String intakeKey = uniqueKey("INTAKE");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        MvcResult first = batchIntake(batchBody(intakeKey, "alice",
                List.of(item(ev1, "甲", "10.00"), item(ev2, "乙", "20.00")),
                List.of("10.00", "21.20")));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        // 清单换序（实测重量随证物对应）视为同参：重放首次响应快照
        MvcResult replay = batchIntake(batchBody(intakeKey, "alice",
                List.of(item(ev2, "乙", "20.00"), item(ev1, "甲", "10.00")),
                List.of("21.20", "10.00")));
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 重放未重复创建：批次仍只有两件
        assertThat(batchView(intakeKey).get("items")).hasSize(2);
    }

    @Test
    void sameIntakeKeyWithDifferentParamsReturns409() throws Exception {
        String intakeKey = uniqueKey("INTAKE");
        assertThat(batchIntake(batchBody(intakeKey, "alice",
                List.of(item(uniqueKey("EV"), "甲", "10.00")), List.of("10.00")))
                .getResponse().getStatus()).isEqualTo(201);
        // 异参：描述不同 → 409
        assertThat(batchIntake(batchBody(intakeKey, "alice",
                List.of(item(uniqueKey("EV"), "甲", "10.00")), List.of("10.00")))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void batchViewForUnknownIntakeKeyReturns404() throws Exception {
        assertThat(mockMvc.perform(get("/api/evidence/batches/{key}", uniqueKey("INTAKE")))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void reviewOnMatchedOrSingleIntakeEvidenceReturns409() throws Exception {
        String evMatched = uniqueKey("EV");
        batchIntake(batchBody(uniqueKey("INTAKE"), "alice",
                List.of(item(evMatched, "一致", "10.00")), List.of("10.00")));
        assertThat(review("alice", evMatched, uniqueKey("CMD"), "无差异")
                .getResponse().getStatus()).isEqualTo(409);

        // 单件入库证物无待复核差异
        String evSingle = uniqueKey("EV");
        Map<String, Object> intakeBody = new LinkedHashMap<>();
        intakeBody.put("commandKey", uniqueKey("CMD"));
        intakeBody.put("evidenceKey", evSingle);
        intakeBody.put("caseKey", "CASE-1");
        intakeBody.put("category", "DOCUMENT");
        intakeBody.put("sealNo", "SEAL-1");
        mockMvc.perform(post("/api/evidence")
                .header(ACTOR_HEADER, "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(intakeBody)));
        assertThat(review("alice", evSingle, uniqueKey("CMD"), "无差异")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void reviewIsIdempotentByCommandKey() throws Exception {
        String evBad = uniqueKey("EV");
        batchIntake(batchBody(uniqueKey("INTAKE"), "alice",
                List.of(item(evBad, "偏轻", "10.00")), List.of("8.00")));
        String commandKey = uniqueKey("CMD");
        MvcResult first = review("alice", evBad, commandKey, "复称确认");
        MvcResult replay = review("alice", evBad, commandKey, "复称确认");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 保管链中复核已关闭，证物仍为 SEALED
        assertThat(chain(evBad).get("evidence").get("reviewStatus").asText())
                .isEqualTo("RESOLVED");
    }
}
