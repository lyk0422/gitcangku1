package com.example.starter.batch;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 批次隔离与放行 API 的主流程、失败分支与幂等边界测试（H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Map<String, Object> createBody(String commandKey, String batchKey, List<String> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("batchKey", batchKey);
        body.put("productCode", "PROD-1");
        body.put("lotNumber", "LOT-1");
        body.put("producedAt", "2026-09-21T08:00:00Z");
        body.put("requiredItems", items);
        return body;
    }

    private MvcResult postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private void postJsonExpect(String url, Object body, int expectedStatus) throws Exception {
        mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus));
    }

    private void createBatch(String batchKey, List<String> items) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                createBody(unique("cmd-create"), batchKey, items))))
                .andExpect(status().isCreated());
    }

    private Map<String, Object> testBody(String commandKey, String testKey, String item,
                                         String outcome, String inspector) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("testKey", testKey);
        body.put("item", item);
        body.put("outcome", outcome);
        body.put("inspector", inspector);
        return body;
    }

    private void submitPass(String batchKey, String testKey, String item, String inspector) throws Exception {
        mockMvc.perform(post("/api/batches/{key}/tests", batchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                testBody(unique("cmd-test"), testKey, item, "PASS", inspector))))
                .andExpect(status().isOk());
    }

    private void approve(String batchKey, String actor, String role, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/batches/{key}/approvals", batchKey)
                        .header("X-Actor-Id", actor)
                        .header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("commandKey", unique("cmd-approve")))))
                .andExpect(status().is(expectedStatus));
    }

    /** 将批次推进到 PENDING_RELEASE：两个必做项均 PASS。 */
    private void toPendingRelease(String batchKey) throws Exception {
        createBatch(batchKey, List.of("ITEM-A", "ITEM-B"));
        submitPass(batchKey, unique("tk"), "ITEM-A", "inspector-1");
        submitPass(batchKey, unique("tk"), "ITEM-B", "inspector-2");
    }

    /** 将批次推进到 RELEASED。 */
    private void toReleased(String batchKey) throws Exception {
        toPendingRelease(batchKey);
        approve(batchKey, "quality-1", "QUALITY", 200);
        approve(batchKey, "ops-1", "OPERATIONS", 200);
    }

    // ---------- 创建 ----------

    @Test
    void createBatchSuccess() throws Exception {
        String batchKey = unique("batch");
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                createBody(unique("cmd"), batchKey, List.of("ITEM-A")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchKey").value(batchKey))
                .andExpect(jsonPath("$.status").value("QUARANTINED"))
                .andExpect(jsonPath("$.producedAt").value("2026-09-21T08:00:00Z"))
                .andExpect(jsonPath("$.requiredItems", hasSize(1)));
    }

    @Test
    void createBatchDuplicateKeyConflict() throws Exception {
        String batchKey = unique("batch");
        createBatch(batchKey, List.of("ITEM-A"));
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                createBody(unique("cmd"), batchKey, List.of("ITEM-B")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_KEY_EXISTS"));
    }

    @Test
    void createBatchValidationErrors() throws Exception {
        // 0 个必做项
        postJsonExpect("/api/batches", createBody(unique("cmd"), unique("batch"), List.of()), 400);
        // 6 个必做项
        postJsonExpect("/api/batches", createBody(unique("cmd"), unique("batch"),
                List.of("A", "B", "C", "D", "E", "F")), 400);
        // 必做项重复
        postJsonExpect("/api/batches", createBody(unique("cmd"), unique("batch"),
                List.of("A", "A")), 400);
        // 缺少 batchKey
        Map<String, Object> missing = createBody(unique("cmd"), unique("batch"), List.of("A"));
        missing.remove("batchKey");
        postJsonExpect("/api/batches", missing, 400);
        // producedAt 非法
        Map<String, Object> badDate = createBody(unique("cmd"), unique("batch"), List.of("A"));
        badDate.put("producedAt", "not-a-date");
        postJsonExpect("/api/batches", badDate, 400);
    }

    @Test
    void createBatchCommandReplay() throws Exception {
        String commandKey = unique("cmd");
        String batchKey = unique("batch");
        Map<String, Object> body = createBody(commandKey, batchKey, List.of("ITEM-A"));
        MvcResult first = mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        // 同键同参重放：返回首次结果
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchKey").value(batchKey))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getContentAsString())
                        .isEqualTo(first.getResponse().getContentAsString()));
        // 同键改参：409
        Map<String, Object> changed = createBody(commandKey, unique("batch"), List.of("ITEM-A"));
        postJsonExpect("/api/batches", changed, 409);
    }

    // ---------- 检验 ----------

    @Test
    void submitTestsToPendingRelease() throws Exception {
        String batchKey = unique("batch");
        createBatch(batchKey, List.of("ITEM-A", "ITEM-B"));
        mockMvc.perform(post("/api/batches/{key}/tests", batchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                testBody(unique("cmd"), unique("tk"), "ITEM-A", "PASS", "insp-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchStatus").value("QUARANTINED"));
        mockMvc.perform(post("/api/batches/{key}/tests", batchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                testBody(unique("cmd"), unique("tk"), "ITEM-B", "PASS", "insp-2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchStatus").value("PENDING_RELEASE"));
    }

    @Test
    void submitTestFailRejectsBatch() throws Exception {
        String batchKey = unique("batch");
        createBatch(batchKey, List.of("ITEM-A", "ITEM-B"));
        submitPass(batchKey, unique("tk"), "ITEM-A", "insp-1");
        mockMvc.perform(post("/api/batches/{key}/tests", batchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                testBody(unique("cmd"), unique("tk"), "ITEM-B", "FAIL", "insp-2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchStatus").value("REJECTED"));
        // 已拒绝批次不能再检验
        postJsonExpect("/api/batches/" + batchKey + "/tests",
                testBody(unique("cmd"), unique("tk"), "ITEM-A", "PASS", "insp-3"), 409);
        // 已拒绝批次不能批准
        approve(batchKey, "quality-1", "QUALITY", 409);
    }

    @Test
    void submitTestUnknownBatchOrItem() throws Exception {
        String batchKey = unique("batch");
        createBatch(batchKey, List.of("ITEM-A"));
        // 批次不存在
        postJsonExpect("/api/batches/" + unique("nope") + "/tests",
                testBody(unique("cmd"), unique("tk"), "ITEM-A", "PASS", "insp-1"), 404);
        // 检验项不存在
        MvcResult result = postJson("/api/batches/" + batchKey + "/tests",
                testBody(unique("cmd"), unique("tk"), "ITEM-X", "PASS", "insp-1"));
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(404);
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .contains("TEST_ITEM_NOT_FOUND");
    }

    @Test
    void submitTestKeyIdempotency() throws Exception {
        String batchKey = unique("batch");
        createBatch(batchKey, List.of("ITEM-A", "ITEM-B"));
        String testKey = unique("tk");
        Map<String, Object> body = testBody(unique("cmd"), testKey, "ITEM-A", "PASS", "insp-1");
        MvcResult first = postJson("/api/batches/" + batchKey + "/tests", body);
        org.assertj.core.api.Assertions.assertThat(first.getResponse().getStatus()).isEqualTo(200);
        // 同 testKey 同内容重放（不同 commandKey）：返回原结果
        Map<String, Object> replay = testBody(unique("cmd"), testKey, "ITEM-A", "PASS", "insp-1");
        mockMvc.perform(post("/api/batches/{key}/tests", batchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replay)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testKey").value(testKey))
                .andExpect(jsonPath("$.testedAt").value(
                        objectMapper.readTree(first.getResponse().getContentAsString())
                                .get("testedAt").asText()));
        // 同 testKey 不同内容：409
        postJsonExpect("/api/batches/" + batchKey + "/tests",
                testBody(unique("cmd"), testKey, "ITEM-A", "FAIL", "insp-1"), 409);
        // 同 commandKey 不同参数：409
        String commandKey = unique("cmd");
        postJsonExpect("/api/batches/" + batchKey + "/tests",
                testBody(commandKey, unique("tk"), "ITEM-B", "PASS", "insp-1"), 200);
        postJsonExpect("/api/batches/" + batchKey + "/tests",
                testBody(commandKey, unique("tk"), "ITEM-B", "FAIL", "insp-1"), 409);
    }

    @Test
    void submitTestOnNonQuarantinedConflict() throws Exception {
        String batchKey = unique("batch");
        toPendingRelease(batchKey);
        postJsonExpect("/api/batches/" + batchKey + "/tests",
                testBody(unique("cmd"), unique("tk"), "ITEM-A", "PASS", "insp-9"), 409);
    }

    // ---------- 批准 ----------

    @Test
    void approveFlowToReleased() throws Exception {
        String batchKey = unique("batch");
        toPendingRelease(batchKey);
        mockMvc.perform(post("/api/batches/{key}/approvals", batchKey)
                        .header("X-Actor-Id", "quality-1")
                        .header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("commandKey", unique("cmd")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchStatus").value("RELEASE_REVIEW"));
        mockMvc.perform(post("/api/batches/{key}/approvals", batchKey)
                        .header("X-Actor-Id", "ops-1")
                        .header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("commandKey", unique("cmd")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchStatus").value("RELEASED"));
    }

    @Test
    void approveWhileQuarantinedReturns422() throws Exception {
        String batchKey = unique("batch");
        createBatch(batchKey, List.of("ITEM-A", "ITEM-B"));
        submitPass(batchKey, unique("tk"), "ITEM-A", "insp-1");
        // 检验未满足：422
        approve(batchKey, "quality-1", "QUALITY", 422);
    }

    @Test
    void approveRoleAndActorConstraints() throws Exception {
        String batchKey = unique("batch");
        toPendingRelease(batchKey);
        approve(batchKey, "quality-1", "QUALITY", 200);
        // 重复角色：409
        approve(batchKey, "quality-2", "QUALITY", 409);
        // 同一人完成两个角色：409
        approve(batchKey, "quality-1", "OPERATIONS", 409);
        // 批准人是检验人：409
        approve(batchKey, "inspector-1", "OPERATIONS", 409);
        // 批次仍停在 RELEASE_REVIEW
        mockMvc.perform(get("/api/batches/{key}", batchKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASE_REVIEW"))
                .andExpect(jsonPath("$.approvals", hasSize(1)));
    }

    @Test
    void approveTerminalStatesConflict() throws Exception {
        String batchKey = unique("batch");
        toReleased(batchKey);
        // 已放行不能再批准
        approve(batchKey, "quality-2", "QUALITY", 409);
        approve(batchKey, "ops-2", "OPERATIONS", 409);
    }

    @Test
    void approveHeaderValidation() throws Exception {
        String batchKey = unique("batch");
        toPendingRelease(batchKey);
        // 缺少 X-Actor-Id
        mockMvc.perform(post("/api/batches/{key}/approvals", batchKey)
                        .header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("commandKey", unique("cmd")))))
                .andExpect(status().isBadRequest());
        // 非法角色
        mockMvc.perform(post("/api/batches/{key}/approvals", batchKey)
                        .header("X-Actor-Id", "a1")
                        .header("X-Approval-Role", "MANAGER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("commandKey", unique("cmd")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void approveCommandReplay() throws Exception {
        String batchKey = unique("batch");
        toPendingRelease(batchKey);
        String commandKey = unique("cmd");
        Map<String, Object> body = Map.of("commandKey", commandKey);
        MvcResult first = mockMvc.perform(post("/api/batches/{key}/approvals", batchKey)
                        .header("X-Actor-Id", "quality-1")
                        .header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        // 同键同参重放：返回首次结果，不重复推进状态
        mockMvc.perform(post("/api/batches/{key}/approvals", batchKey)
                        .header("X-Actor-Id", "quality-1")
                        .header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchStatus").value("RELEASE_REVIEW"))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getContentAsString())
                        .isEqualTo(first.getResponse().getContentAsString()));
        // 同键改参：409
        mockMvc.perform(post("/api/batches/{key}/approvals", batchKey)
                        .header("X-Actor-Id", "ops-1")
                        .header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
    }

    // ---------- 召回 ----------

    @Test
    void recallReleasedBatch() throws Exception {
        String batchKey = unique("batch");
        toReleased(batchKey);
        String commandKey = unique("cmd");
        mockMvc.perform(post("/api/batches/{key}/recalls", batchKey)
                        .header("X-Actor-Id", "anyone-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("commandKey", commandKey, "reason", "包装破损"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchStatus").value("RECALLED"))
                .andExpect(jsonPath("$.reason").value("包装破损"));
        // 同键同参重放
        mockMvc.perform(post("/api/batches/{key}/recalls", batchKey)
                        .header("X-Actor-Id", "anyone-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("commandKey", commandKey, "reason", "包装破损"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchStatus").value("RECALLED"));
        // 同键改参：409
        mockMvc.perform(post("/api/batches/{key}/recalls", batchKey)
                        .header("X-Actor-Id", "anyone-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("commandKey", commandKey, "reason", "其他原因"))))
                .andExpect(status().isConflict());
        // 已召回不能再次召回
        mockMvc.perform(post("/api/batches/{key}/recalls", batchKey)
                        .header("X-Actor-Id", "anyone-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("commandKey", unique("cmd"), "reason", "再次召回"))))
                .andExpect(status().isConflict());
    }

    @Test
    void recallNonReleasedConflict() throws Exception {
        String batchKey = unique("batch");
        toPendingRelease(batchKey);
        mockMvc.perform(post("/api/batches/{key}/recalls", batchKey)
                        .header("X-Actor-Id", "anyone-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("commandKey", unique("cmd"), "reason", "提前召回"))))
                .andExpect(status().isConflict());
        // 原因为空：400
        mockMvc.perform(post("/api/batches/{key}/recalls", batchKey)
                        .header("X-Actor-Id", "anyone-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("commandKey", unique("cmd"), "reason", " "))))
                .andExpect(status().isBadRequest());
    }

    // ---------- 查询 ----------

    @Test
    void availableExcludesRejectedAndRecalled() throws Exception {
        String quarantined = unique("batch");
        String rejected = unique("batch");
        String released = unique("batch");
        String recalled = unique("batch");
        createBatch(quarantined, List.of("ITEM-A"));
        createBatch(rejected, List.of("ITEM-A"));
        postJsonExpect("/api/batches/" + rejected + "/tests",
                testBody(unique("cmd"), unique("tk"), "ITEM-A", "FAIL", "insp-1"), 200);
        toReleased(released);
        toReleased(recalled);
        mockMvc.perform(post("/api/batches/{key}/recalls", recalled)
                        .header("X-Actor-Id", "anyone-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("commandKey", unique("cmd"), "reason", "质量风险"))))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].batchKey", hasItem(quarantined)))
                .andExpect(jsonPath("$[*].batchKey", hasItem(released)))
                .andExpect(jsonPath("$[*].batchKey", not(hasItem(rejected))))
                .andExpect(jsonPath("$[*].batchKey", not(hasItem(recalled))))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .contains("PENDING_RELEASE").contains("RELEASED");
    }

    @Test
    void detailKeepsFullHistoryAfterRecall() throws Exception {
        String batchKey = unique("batch");
        toReleased(batchKey);
        mockMvc.perform(post("/api/batches/{key}/recalls", batchKey)
                        .header("X-Actor-Id", "anyone-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("commandKey", unique("cmd"), "reason", "冷链中断"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/batches/{key}", batchKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECALLED"))
                .andExpect(jsonPath("$.requiredItems", hasSize(2)))
                .andExpect(jsonPath("$.tests", hasSize(2)))
                .andExpect(jsonPath("$.tests[0].outcome").value("PASS"))
                .andExpect(jsonPath("$.approvals", hasSize(2)))
                .andExpect(jsonPath("$.approvals[0].role").value("QUALITY"))
                .andExpect(jsonPath("$.approvals[1].role").value("OPERATIONS"))
                .andExpect(jsonPath("$.recall.reason").value("冷链中断"))
                .andExpect(jsonPath("$.recall.actorId").value("anyone-1"));
    }

    @Test
    void detailUnknownBatchNotFound() throws Exception {
        mockMvc.perform(get("/api/batches/{key}", unique("nope")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_FOUND"));
    }
}
