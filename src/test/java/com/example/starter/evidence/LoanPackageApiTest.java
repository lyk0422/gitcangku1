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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 组合借出包 API 测试：组合借出、分批归还、整体回滚、自动关闭、
 * 借出撤销、案件权限与幂等边界（固定时钟驱动，真实 H2）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoanPackageApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    /** 测试基准时刻（UTC）。 */
    private static final Instant BASE = Instant.parse("2026-09-22T08:00:00Z");

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

    private void fixClock(Instant instant) {
        evidenceClock.setClock(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private MvcResult intake(String actor, String evidenceKey, String caseKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", caseKey);
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-1");
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private long evidenceVersion(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        JsonNode chain = objectMapper.readTree(result.getResponse().getContentAsString());
        return chain.get("evidence").get("version").asLong();
    }

    private MvcResult grant(String actor, String caseKey, String userId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", uniqueKey("REQ"));
        body.put("caseKey", caseKey);
        body.put("userId", userId);
        return mockMvc.perform(post("/api/evidence/packages/case-permissions")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult createPackage(String actor, String requestId, String packageKey,
                                    String borrower, List<Map<String, Object>> items,
                                    LocalDateTime dueAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("packageKey", packageKey);
        body.put("borrowerId", borrower);
        body.put("purpose", "庭审核验");
        body.put("dueAt", dueAt == null ? null : dueAt.toString());
        body.put("items", items);
        return mockMvc.perform(post("/api/evidence/packages")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private Map<String, Object> item(String evidenceKey, long expectedVersion) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("evidenceKey", evidenceKey);
        item.put("expectedVersion", expectedVersion);
        return item;
    }

    private MvcResult returnBatch(String actor, String packageKey, String requestId,
                                  String receiver, String reviewer,
                                  List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("receiverId", receiver);
        body.put("reviewerId", reviewer);
        body.put("items", items);
        return mockMvc.perform(post("/api/evidence/packages/{key}/returns", packageKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private Map<String, Object> returnItem(String evidenceKey, long sealVersion) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("evidenceKey", evidenceKey);
        item.put("sealVersion", sealVersion);
        return item;
    }

    private MvcResult cancelPackage(String actor, String packageKey, String requestId)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        return mockMvc.perform(post("/api/evidence/packages/{key}/cancel", packageKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode getPackage(String packageKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/packages/{key}", packageKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getRemaining(String packageKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/packages/{key}/remaining", packageKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getChain(String packageKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/packages/{key}/chain", packageKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * 准备同一案件下 alice 保管的 n 件证物，返回证物键列表（入库顺序）。
     */
    private List<String> prepareEvidence(String caseKey, int count) throws Exception {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String key = uniqueKey("EV");
            assertThat(intake("alice", key, caseKey).getResponse().getStatus()).isEqualTo(201);
            keys.add(key);
        }
        return keys;
    }

    private List<Map<String, Object>> itemsOf(List<String> keys) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String key : keys) {
            items.add(item(key, evidenceVersion(key)));
        }
        return items;
    }

    @Test
    void createPackageLocksAllEvidenceAsBorrowed() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 3);
        String packageKey = uniqueKey("PKG");

        MvcResult result = createPackage("alice", uniqueKey("REQ"), packageKey, "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600)));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode view = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(view.get("packageKey").asText()).isEqualTo(packageKey);
        assertThat(view.get("caseKey").asText()).isEqualTo(caseKey);
        assertThat(view.get("custodianId").asText()).isEqualTo("alice");
        assertThat(view.get("borrowerId").asText()).isEqualTo("bob");
        assertThat(view.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(view.get("items")).hasSize(3);
        // 明细按包内稳定排序（证物主键序），sealVersion 冻结借出时版本
        for (int i = 0; i < 3; i++) {
            JsonNode itemNode = view.get("items").get(i);
            assertThat(itemNode.get("evidenceKey").asText()).isEqualTo(keys.get(i));
            assertThat(itemNode.get("sealVersion").asLong()).isEqualTo(1);
            assertThat(itemNode.get("status").asText()).isEqualTo("OUT");
        }
        // 全部证物进入 BORROWED，保管人不变
        for (String key : keys) {
            MvcResult chainResult = mockMvc.perform(get("/api/evidence/{key}/custody-chain", key))
                    .andReturn();
            JsonNode evidence = objectMapper
                    .readTree(chainResult.getResponse().getContentAsString()).get("evidence");
            assertThat(evidence.get("status").asText()).isEqualTo("BORROWED");
            assertThat(evidence.get("custodianId").asText()).isEqualTo("alice");
        }
        // 剩余集合为全部证物
        assertThat(getRemaining(packageKey).get("remaining")).hasSize(3);
    }

    @Test
    void createWithOneItemReturns400() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 1);
        assertThat(createPackage("alice", uniqueKey("REQ"), uniqueKey("PKG"), "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600))).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void createWithMixedCasesFailsAndNoPartialLoan() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 2);
        String otherCase = uniqueKey("CASE");
        String foreign = uniqueKey("EV");
        intake("alice", foreign, otherCase);

        List<Map<String, Object>> items = itemsOf(keys);
        items.add(item(foreign, evidenceVersion(foreign)));
        assertThat(createPackage("alice", uniqueKey("REQ"), uniqueKey("PKG"), "bob",
                items, utc(BASE.plusSeconds(3600))).getResponse().getStatus()).isEqualTo(409);

        // 整包失败：不得形成部分借出记录，全部证物仍 SEALED
        for (String key : keys) {
            MvcResult chainResult = mockMvc.perform(get("/api/evidence/{key}/custody-chain", key))
                    .andReturn();
            JsonNode evidence = objectMapper
                    .readTree(chainResult.getResponse().getContentAsString()).get("evidence");
            assertThat(evidence.get("status").asText()).isEqualTo("SEALED");
        }
    }

    @Test
    void createWithMixedCustodiansFails() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 1);
        String other = uniqueKey("EV");
        intake("carol", other, caseKey);

        List<Map<String, Object>> items = itemsOf(keys);
        items.add(item(other, evidenceVersion(other)));
        assertThat(createPackage("alice", uniqueKey("REQ"), uniqueKey("PKG"), "bob",
                items, utc(BASE.plusSeconds(3600))).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void createByNonCustodianFails() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 2);
        assertThat(createPackage("mallory", uniqueKey("REQ"), uniqueKey("PKG"), "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600))).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void createWithStaleExpectedVersionFails() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 2);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item(keys.get(0), evidenceVersion(keys.get(0))));
        items.add(item(keys.get(1), 99L));

        assertThat(createPackage("alice", uniqueKey("REQ"), uniqueKey("PKG"), "bob",
                items, utc(BASE.plusSeconds(3600))).getResponse().getStatus()).isEqualTo(409);
        // 版本不符整包失败，无部分借出
        MvcResult chainResult = mockMvc.perform(get("/api/evidence/{key}/custody-chain", keys.get(0)))
                .andReturn();
        assertThat(objectMapper.readTree(chainResult.getResponse().getContentAsString())
                .get("evidence").get("status").asText()).isEqualTo("SEALED");
    }

    @Test
    void createWithAlreadyBorrowedEvidenceFails() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 2);
        // 先单件借出其中一件
        Map<String, Object> loanBody = new LinkedHashMap<>();
        loanBody.put("commandKey", uniqueKey("CMD"));
        loanBody.put("loanKey", uniqueKey("LOAN"));
        loanBody.put("borrowerId", "bob");
        loanBody.put("purpose", "鉴定用");
        loanBody.put("dueAt", utc(BASE.plusSeconds(3600)).toString());
        mockMvc.perform(post("/api/evidence/{key}/loans", keys.get(0))
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanBody)))
                .andReturn();

        assertThat(createPackage("alice", uniqueKey("REQ"), uniqueKey("PKG"), "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600))).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void createWithSealBrokenEvidenceReturns422() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 2);
        Map<String, Object> inspectBody = new LinkedHashMap<>();
        inspectBody.put("commandKey", uniqueKey("CMD"));
        inspectBody.put("passed", false);
        inspectBody.put("note", "封条破损");
        mockMvc.perform(post("/api/evidence/{key}/seal-inspections", keys.get(1))
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(inspectBody)))
                .andReturn();

        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item(keys.get(0), evidenceVersion(keys.get(0))));
        items.add(item(keys.get(1), evidenceVersion(keys.get(1))));
        assertThat(createPackage("alice", uniqueKey("REQ"), uniqueKey("PKG"), "bob",
                items, utc(BASE.plusSeconds(3600))).getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void createWithDuplicatePackageKeyReturns409() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 2);
        String packageKey = uniqueKey("PKG");
        assertThat(createPackage("alice", uniqueKey("REQ"), packageKey, "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600))).getResponse().getStatus())
                .isEqualTo(201);

        List<String> more = prepareEvidence(caseKey, 2);
        assertThat(createPackage("alice", uniqueKey("REQ"), packageKey, "bob",
                itemsOf(more), utc(BASE.plusSeconds(3600))).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void createWithDuplicateEvidenceInRequestReturns400() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 1);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item(keys.get(0), 1));
        items.add(item(keys.get(0), 1));
        assertThat(createPackage("alice", uniqueKey("REQ"), uniqueKey("PKG"), "bob",
                items, utc(BASE.plusSeconds(3600))).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void createIdempotentReplayAndReorderedSubsetSameParams() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 3);
        String requestId = uniqueKey("REQ");
        String packageKey = uniqueKey("PKG");
        List<Map<String, Object>> items = itemsOf(keys);

        MvcResult first = createPackage("alice", requestId, packageKey, "bob",
                items, utc(BASE.plusSeconds(3600)));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        // 子集换序视为同参：重放首次完整响应
        List<Map<String, Object>> reordered = new ArrayList<>();
        reordered.add(items.get(2));
        reordered.add(items.get(0));
        reordered.add(items.get(1));
        MvcResult replay = createPackage("alice", requestId, packageKey, "bob",
                reordered, utc(BASE.plusSeconds(3600)));
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 只创建了一个包
        assertThat(getPackage(packageKey).get("items")).hasSize(3);
    }

    @Test
    void createSameRequestIdWithDifferentParamsReturns409() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 2);
        String requestId = uniqueKey("REQ");
        assertThat(createPackage("alice", requestId, uniqueKey("PKG"), "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600))).getResponse().getStatus())
                .isEqualTo(201);

        List<String> more = prepareEvidence(caseKey, 2);
        assertThat(createPackage("alice", requestId, uniqueKey("PKG"), "carol",
                itemsOf(more), utc(BASE.plusSeconds(3600))).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void failedCreateDoesNotOccupyRequestId() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 2);
        String requestId = uniqueKey("REQ");
        // 首次应还时刻非法失败
        assertThat(createPackage("alice", requestId, uniqueKey("PKG"), "bob",
                itemsOf(keys), utc(BASE.minusSeconds(60))).getResponse().getStatus())
                .isEqualTo(400);
        // 同键合法参数重试成功
        assertThat(createPackage("alice", requestId, uniqueKey("PKG"), "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600))).getResponse().getStatus())
                .isEqualTo(201);
    }

    @Test
    void partialReturnUpdatesStateAndRemaining() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 3);
        String packageKey = uniqueKey("PKG");
        grant("alice", caseKey, "recv");
        grant("alice", caseKey, "revw");
        createPackage("alice", uniqueKey("REQ"), packageKey, "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600)));

        MvcResult result = returnBatch("alice", packageKey, uniqueKey("REQ"), "recv", "revw",
                List.of(returnItem(keys.get(0), 1)));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode view = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(view.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(view.get("batches")).hasSize(1);
        JsonNode batch = view.get("batches").get(0);
        assertThat(batch.get("receiverId").asText()).isEqualTo("recv");
        assertThat(batch.get("reviewerId").asText()).isEqualTo("revw");
        assertThat(batch.get("closedPackage").asBoolean()).isFalse();
        assertThat(batch.get("items")).hasSize(1);
        assertThat(batch.get("items").get(0).get("evidenceKey").asText()).isEqualTo(keys.get(0));
        assertThat(batch.get("items").get(0).get("sealVersion").asLong()).isEqualTo(1);

        // 已归还证物恢复 SEALED，其余仍 BORROWED
        MvcResult chain0 = mockMvc.perform(get("/api/evidence/{key}/custody-chain", keys.get(0)))
                .andReturn();
        assertThat(objectMapper.readTree(chain0.getResponse().getContentAsString())
                .get("evidence").get("status").asText()).isEqualTo("SEALED");
        MvcResult chain1 = mockMvc.perform(get("/api/evidence/{key}/custody-chain", keys.get(1)))
                .andReturn();
        assertThat(objectMapper.readTree(chain1.getResponse().getContentAsString())
                .get("evidence").get("status").asText()).isEqualTo("BORROWED");

        // 剩余集合只剩两件
        JsonNode remaining = getRemaining(packageKey);
        assertThat(remaining.get("remaining")).hasSize(2);
        List<String> remainingKeys = new ArrayList<>();
        remaining.get("remaining").forEach(node -> remainingKeys.add(node.asText()));
        assertThat(remainingKeys).containsExactly(keys.get(1), keys.get(2));
    }

    @Test
    void lastReturnAutoClosesPackageWithSnapshot() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 2);
        String packageKey = uniqueKey("PKG");
        grant("alice", caseKey, "recv");
        grant("alice", caseKey, "revw");
        createPackage("alice", uniqueKey("REQ"), packageKey, "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600)));

        returnBatch("alice", packageKey, uniqueKey("REQ"), "recv", "revw",
                List.of(returnItem(keys.get(0), 1)));
        MvcResult last = returnBatch("alice", packageKey, uniqueKey("REQ"), "recv", "revw",
                List.of(returnItem(keys.get(1), 1)));
        assertThat(last.getResponse().getStatus()).isEqualTo(200);
        JsonNode view = objectMapper.readTree(last.getResponse().getContentAsString());
        assertThat(view.get("status").asText()).isEqualTo("CLOSED");
        assertThat(view.get("closedAt").isNull()).isFalse();
        assertThat(view.get("batches")).hasSize(2);
        assertThat(view.get("batches").get(1).get("closedPackage").asBoolean()).isTrue();

        // 关闭快照包含全部借出与归还批次
        JsonNode chain = getChain(packageKey);
        assertThat(chain.get("status").asText()).isEqualTo("CLOSED");
        String snapshot = chain.get("closeSnapshot").asText();
        JsonNode snapshotNode = objectMapper.readTree(snapshot);
        assertThat(snapshotNode.get("packageKey").asText()).isEqualTo(packageKey);
        assertThat(snapshotNode.get("items")).hasSize(2);
        assertThat(snapshotNode.get("batches")).hasSize(2);
        assertThat(snapshotNode.get("batchCount").asInt()).isEqualTo(2);

        // 剩余集合为空
        assertThat(getRemaining(packageKey).get("remaining")).isEmpty();

        // 已关闭后禁止再归还
        assertThat(returnBatch("alice", packageKey, uniqueKey("REQ"), "recv", "revw",
                List.of(returnItem(keys.get(0), 1))).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void returnWithSameReceiverAndReviewerReturns400() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 2);
        String packageKey = uniqueKey("PKG");
        grant("alice", caseKey, "recv");
        createPackage("alice", uniqueKey("REQ"), packageKey, "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600)));

        assertThat(returnBatch("alice", packageKey, uniqueKey("REQ"), "recv", "recv",
                List.of(returnItem(keys.get(0), 1))).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void returnWithoutCasePermissionReturns409() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 2);
        String packageKey = uniqueKey("PKG");
        grant("alice", caseKey, "recv");
        createPackage("alice", uniqueKey("REQ"), packageKey, "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600)));

        // 复核人无案件权限
        assertThat(returnBatch("alice", packageKey, uniqueKey("REQ"), "recv", "stranger",
                List.of(returnItem(keys.get(0), 1))).getResponse().getStatus()).isEqualTo(409);
        // 接收人无案件权限
        assertThat(returnBatch("alice", packageKey, uniqueKey("REQ"), "stranger", "recv",
                List.of(returnItem(keys.get(0), 1))).getResponse().getStatus()).isEqualTo(409);
        // 失败不占键：补齐权限后同 requestId 可重试
        grant("alice", caseKey, "revw");
        assertThat(returnBatch("alice", packageKey, uniqueKey("REQ"), "recv", "revw",
                List.of(returnItem(keys.get(0), 1))).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void returnWithDuplicateEvidenceInSubsetReturns400() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 2);
        String packageKey = uniqueKey("PKG");
        grant("alice", caseKey, "recv");
        grant("alice", caseKey, "revw");
        createPackage("alice", uniqueKey("REQ"), packageKey, "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600)));

        assertThat(returnBatch("alice", packageKey, uniqueKey("REQ"), "recv", "revw",
                List.of(returnItem(keys.get(0), 1), returnItem(keys.get(0), 1)))
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void returnWithAlreadyReturnedEvidenceRollsBackWholeBatch() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 3);
        String packageKey = uniqueKey("PKG");
        grant("alice", caseKey, "recv");
        grant("alice", caseKey, "revw");
        createPackage("alice", uniqueKey("REQ"), packageKey, "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600)));
        returnBatch("alice", packageKey, uniqueKey("REQ"), "recv", "revw",
                List.of(returnItem(keys.get(0), 1)));

        // 子集中包含已归还证物：整批回滚，另一件也不落账
        assertThat(returnBatch("alice", packageKey, uniqueKey("REQ"), "recv", "revw",
                List.of(returnItem(keys.get(0), 1), returnItem(keys.get(1), 1)))
                .getResponse().getStatus()).isEqualTo(409);
        JsonNode view = getPackage(packageKey);
        assertThat(view.get("batches")).hasSize(1);
        JsonNode remaining = getRemaining(packageKey);
        List<String> remainingKeys = new ArrayList<>();
        remaining.get("remaining").forEach(node -> remainingKeys.add(node.asText()));
        assertThat(remainingKeys).containsExactly(keys.get(1), keys.get(2));
    }

    @Test
    void returnWithForeignEvidenceReturns404AndRollsBack() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 2);
        String packageKey = uniqueKey("PKG");
        grant("alice", caseKey, "recv");
        grant("alice", caseKey, "revw");
        createPackage("alice", uniqueKey("REQ"), packageKey, "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600)));
        String foreign = uniqueKey("EV");
        intake("alice", foreign, caseKey);

        assertThat(returnBatch("alice", packageKey, uniqueKey("REQ"), "recv", "revw",
                List.of(returnItem(keys.get(0), 1), returnItem(foreign, 1)))
                .getResponse().getStatus()).isEqualTo(404);
        // 整批回滚：本批一件都不落账
        assertThat(getPackage(packageKey).get("batches")).isEmpty();
        assertThat(getRemaining(packageKey).get("remaining")).hasSize(2);
    }

    @Test
    void returnWithWrongSealVersionRollsBackWholeBatch() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 2);
        String packageKey = uniqueKey("PKG");
        grant("alice", caseKey, "recv");
        grant("alice", caseKey, "revw");
        createPackage("alice", uniqueKey("REQ"), packageKey, "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600)));

        assertThat(returnBatch("alice", packageKey, uniqueKey("REQ"), "recv", "revw",
                List.of(returnItem(keys.get(0), 1), returnItem(keys.get(1), 7)))
                .getResponse().getStatus()).isEqualTo(409);
        // 整批回滚
        assertThat(getPackage(packageKey).get("batches")).isEmpty();
        assertThat(getRemaining(packageKey).get("remaining")).hasSize(2);
        MvcResult chain0 = mockMvc.perform(get("/api/evidence/{key}/custody-chain", keys.get(0)))
                .andReturn();
        assertThat(objectMapper.readTree(chain0.getResponse().getContentAsString())
                .get("evidence").get("status").asText()).isEqualTo("BORROWED");
    }

    @Test
    void returnIdempotentReplayAndReorderedSubsetSameParams() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 3);
        String packageKey = uniqueKey("PKG");
        grant("alice", caseKey, "recv");
        grant("alice", caseKey, "revw");
        createPackage("alice", uniqueKey("REQ"), packageKey, "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600)));

        String requestId = uniqueKey("REQ");
        List<Map<String, Object>> subset = List.of(
                returnItem(keys.get(0), 1), returnItem(keys.get(1), 1));
        MvcResult first = returnBatch("alice", packageKey, requestId, "recv", "revw", subset);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        // 子集换序视为同参：重放首次完整响应，不重复落账
        MvcResult replay = returnBatch("alice", packageKey, requestId, "recv", "revw",
                List.of(returnItem(keys.get(1), 1), returnItem(keys.get(0), 1)));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(getPackage(packageKey).get("batches")).hasSize(1);
    }

    @Test
    void returnSameRequestIdWithDifferentSubsetReturns409() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 3);
        String packageKey = uniqueKey("PKG");
        grant("alice", caseKey, "recv");
        grant("alice", caseKey, "revw");
        createPackage("alice", uniqueKey("REQ"), packageKey, "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600)));

        String requestId = uniqueKey("REQ");
        assertThat(returnBatch("alice", packageKey, requestId, "recv", "revw",
                List.of(returnItem(keys.get(0), 1))).getResponse().getStatus()).isEqualTo(200);
        // 同键不同子集
        assertThat(returnBatch("alice", packageKey, requestId, "recv", "revw",
                List.of(returnItem(keys.get(1), 1))).getResponse().getStatus()).isEqualTo(409);
        // 同键不同接收人
        assertThat(returnBatch("alice", packageKey, requestId, "other", "revw",
                List.of(returnItem(keys.get(0), 1))).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void cancelBeforeAnyReturnRestoresAllEvidence() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 3);
        String packageKey = uniqueKey("PKG");
        createPackage("alice", uniqueKey("REQ"), packageKey, "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600)));

        MvcResult result = cancelPackage("alice", packageKey, uniqueKey("REQ"));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode view = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(view.get("cancelled").asBoolean()).isTrue();
        assertThat(view.get("restoredEvidenceKeys")).hasSize(3);

        // 全部证物恢复 SEALED，组合包不再存在
        for (String key : keys) {
            MvcResult chainResult = mockMvc.perform(get("/api/evidence/{key}/custody-chain", key))
                    .andReturn();
            assertThat(objectMapper.readTree(chainResult.getResponse().getContentAsString())
                    .get("evidence").get("status").asText()).isEqualTo("SEALED");
        }
        MvcResult getResult = mockMvc.perform(get("/api/evidence/packages/{key}", packageKey))
                .andReturn();
        assertThat(getResult.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void cancelAfterAnyReturnReturns409() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 2);
        String packageKey = uniqueKey("PKG");
        grant("alice", caseKey, "recv");
        grant("alice", caseKey, "revw");
        createPackage("alice", uniqueKey("REQ"), packageKey, "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600)));
        returnBatch("alice", packageKey, uniqueKey("REQ"), "recv", "revw",
                List.of(returnItem(keys.get(0), 1)));

        assertThat(cancelPackage("alice", packageKey, uniqueKey("REQ"))
                .getResponse().getStatus()).isEqualTo(409);
        // 包状态不变
        assertThat(getPackage(packageKey).get("status").asText()).isEqualTo("PARTIAL");
    }

    @Test
    void cancelByNonCustodianReturns409() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 2);
        String packageKey = uniqueKey("PKG");
        createPackage("alice", uniqueKey("REQ"), packageKey, "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600)));

        assertThat(cancelPackage("mallory", packageKey, uniqueKey("REQ"))
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(getPackage(packageKey).get("status").asText()).isEqualTo("PARTIAL");
    }

    @Test
    void cancelIdempotentReplayReturnsFirstResult() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 2);
        String packageKey = uniqueKey("PKG");
        createPackage("alice", uniqueKey("REQ"), packageKey, "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600)));

        String requestId = uniqueKey("REQ");
        MvcResult first = cancelPackage("alice", packageKey, requestId);
        MvcResult replay = cancelPackage("alice", packageKey, requestId);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void queriesOnMissingPackageReturn404() throws Exception {
        String missing = uniqueKey("MISSING");
        assertThat(mockMvc.perform(get("/api/evidence/packages/{key}", missing))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/evidence/packages/{key}/remaining", missing))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/evidence/packages/{key}/chain", missing))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(returnBatch("alice", missing, uniqueKey("REQ"), "recv", "revw",
                List.of(returnItem("EV-X", 1))).getResponse().getStatus()).isEqualTo(404);
        assertThat(cancelPackage("alice", missing, uniqueKey("REQ"))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void chainViewStableOrderingAcrossBatches() throws Exception {
        fixClock(BASE);
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 4);
        String packageKey = uniqueKey("PKG");
        grant("alice", caseKey, "recv");
        grant("alice", caseKey, "revw");
        createPackage("alice", uniqueKey("REQ"), packageKey, "bob",
                itemsOf(keys), utc(BASE.plusSeconds(3600)));

        // 分批归还：第一批还第 3 件，第二批还第 1、2 件，第三批还第 4 件（自动关闭）
        returnBatch("alice", packageKey, uniqueKey("REQ"), "recv", "revw",
                List.of(returnItem(keys.get(2), 1)));
        returnBatch("alice", packageKey, uniqueKey("REQ"), "recv", "revw",
                List.of(returnItem(keys.get(0), 1), returnItem(keys.get(1), 1)));
        returnBatch("alice", packageKey, uniqueKey("REQ"), "recv", "revw",
                List.of(returnItem(keys.get(3), 1)));

        JsonNode chain = getChain(packageKey);
        assertThat(chain.get("status").asText()).isEqualTo("CLOSED");
        // 借出明细按包内稳定排序（不随归还批次变化）
        JsonNode items = chain.get("items");
        assertThat(items).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat(items.get(i).get("evidenceKey").asText()).isEqualTo(keys.get(i));
            assertThat(items.get(i).get("status").asText()).isEqualTo("RETURNED");
        }
        // 归还批次按提交顺序
        JsonNode batches = chain.get("batches");
        assertThat(batches).hasSize(3);
        assertThat(batches.get(0).get("batchSeq").asInt()).isEqualTo(1);
        assertThat(batches.get(0).get("items").get(0).get("evidenceKey").asText())
                .isEqualTo(keys.get(2));
        assertThat(batches.get(1).get("batchSeq").asInt()).isEqualTo(2);
        assertThat(batches.get(1).get("items")).hasSize(2);
        assertThat(batches.get(2).get("batchSeq").asInt()).isEqualTo(3);
        assertThat(batches.get(2).get("closedPackage").asBoolean()).isTrue();
    }
}
