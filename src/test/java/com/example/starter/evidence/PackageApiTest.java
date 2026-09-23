package com.example.starter.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
 * 组合借出包 API 主流程与失败分支测试（真实 H2 MySQL 兼容库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PackageApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";
    private static final Instant BASE = Instant.parse("2026-09-23T08:00:00Z");
    private static final String CASE = "CASE-PKG-" + UUID.randomUUID().toString().substring(0, 8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PackageService packageService;

    @Autowired
    private EvidenceClock evidenceClock;

    @Autowired
    private LoanPackageRepository packageRepository;

    @Autowired
    private PackageSnapshotRepository snapshotRepository;

    @BeforeEach
    void setUp() {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        packageService.grantCase(CASE, "recv-r1");
        packageService.grantCase(CASE, "recv-r2");
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 10);
    }

    private String dueIn(long hours) {
        return LocalDateTime.ofInstant(BASE.plusSeconds(hours * 3600), ZoneOffset.UTC).toString();
    }

    private String intake(String actor, String evidenceKey, String caseKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", caseKey);
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-1");
        MvcResult result = mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return evidenceKey;
    }

    private String intake(String actor, String evidenceKey) throws Exception {
        return intake(actor, evidenceKey, CASE);
    }

    private Map<String, Object> item(String key, long version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("evidenceKey", key);
        m.put("expectedVersion", version);
        return m;
    }

    private Map<String, Object> returnItem(String key, long sealVersion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("evidenceKey", key);
        m.put("sealVersion", sealVersion);
        return m;
    }

    private MvcResult createPackage(String actor, String packageKey, String borrower,
                                    List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("packageKey", packageKey);
        body.put("purpose", "组合鉴定用");
        body.put("borrowerId", borrower);
        body.put("dueAt", dueIn(24));
        body.put("items", items);
        return mockMvc.perform(post("/api/evidence/packages")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult createPackage(String actor, String commandKey, String packageKey,
                                    String borrower, List<Map<String, Object>> items)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("packageKey", packageKey);
        body.put("purpose", "组合鉴定用");
        body.put("borrowerId", borrower);
        body.put("dueAt", dueIn(24));
        body.put("items", items);
        return mockMvc.perform(post("/api/evidence/packages")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult returnBatch(String actor, String packageKey,
                                  String receiver, String reviewer,
                                  List<Map<String, Object>> items) throws Exception {
        return returnBatch(actor, uniqueKey("CMD"), packageKey, receiver, reviewer, items);
    }

    private MvcResult returnBatch(String actor, String commandKey, String packageKey,
                                  String receiver, String reviewer,
                                  List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("receiverId", receiver);
        body.put("reviewerId", reviewer);
        body.put("note", "分批归还");
        body.put("items", items);
        return mockMvc.perform(post("/api/evidence/packages/{key}/returns", packageKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult cancel(String actor, String packageKey) throws Exception {
        return cancel(actor, uniqueKey("CMD"), packageKey);
    }

    private MvcResult cancel(String actor, String commandKey, String packageKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
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

    private List<String> threeEvidence() throws Exception {
        String e1 = uniqueKey("ev");
        String e2 = uniqueKey("ev");
        String e3 = uniqueKey("ev");
        // 乱序入库，验证查询时按证物键稳定排序。
        intake("alice", e3);
        intake("alice", e1);
        intake("alice", e2);
        return new ArrayList<>(List.of(e1, e2, e3));
    }

    @Test
    void createPackageLendsWholeSetAtomically() throws Exception {
        List<String> evs = threeEvidence();
        String packageKey = uniqueKey("pkg");

        MvcResult result = createPackage("alice", packageKey, "bob",
                List.of(item(evs.get(0), 1), item(evs.get(1), 1), item(evs.get(2), 1)));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(body.get("caseKey").asText()).isEqualTo(CASE);
        assertThat(body.get("custodianId").asText()).isEqualTo("alice");
        assertThat(body.get("borrowerId").asText()).isEqualTo("bob");
        List<String> remaining = objectMapper.convertValue(body.get("remaining"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        assertThat(remaining).containsExactlyElementsOf(evs.stream().sorted().toList());

        for (String ev : evs) {
            JsonNode chain = objectMapper.readTree(mockMvc.perform(
                            get("/api/evidence/{key}/custody-chain", ev)).andReturn()
                    .getResponse().getContentAsString());
            assertThat(chain.get("evidence").get("status").asText()).isEqualTo("BORROWED");
        }
    }

    @Test
    void createRejectsMixedCase() throws Exception {
        String e1 = intake("alice", uniqueKey("ev"), CASE);
        String e2 = intake("alice", uniqueKey("ev"), "CASE-OTHER-" + uniqueKey("x"));
        String packageKey = uniqueKey("pkg");

        MvcResult result = createPackage("alice", packageKey, "bob",
                List.of(item(e1, 1), item(e2, 1)));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(packageRepository.findByKey(packageKey)).isEmpty();
    }

    @Test
    void createRejectsDifferentCustodyPoint() throws Exception {
        String aliceEv = intake("alice", "ev-a-" + uniqueKey("z"));
        String bobEv = intake("bob", "ev-b-" + uniqueKey("z"));
        String packageKey = uniqueKey("pkg");

        MvcResult result = createPackage("alice", packageKey, "carol",
                List.of(item(aliceEv, 1), item(bobEv, 1)));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(packageRepository.findByKey(packageKey)).isEmpty();
    }

    @Test
    void createByNonCustodianReturns409() throws Exception {
        List<String> evs = threeEvidence();
        String packageKey = uniqueKey("pkg");

        MvcResult result = createPackage("mallory", packageKey, "bob",
                List.of(item(evs.get(0), 1), item(evs.get(1), 1)));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(packageRepository.findByKey(packageKey)).isEmpty();
    }

    @Test
    void createRejectsBorrowedEvidenceWithNoPartialLoan() throws Exception {
        List<String> evs = threeEvidence();
        // 其中一件已被单件借出
        Map<String, Object> borrowBody = new LinkedHashMap<>();
        borrowBody.put("commandKey", uniqueKey("CMD"));
        borrowBody.put("loanKey", uniqueKey("loan"));
        borrowBody.put("borrowerId", "bob");
        borrowBody.put("purpose", "单件鉴定");
        borrowBody.put("dueAt", dueIn(5));
        MvcResult borrowed = mockMvc.perform(post("/api/evidence/{key}/loans", evs.get(0))
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(borrowBody)))
                .andReturn();
        assertThat(borrowed.getResponse().getStatus()).isEqualTo(200);

        String packageKey = uniqueKey("pkg");
        MvcResult result = createPackage("alice", packageKey, "carol",
                List.of(item(evs.get(0), 2), item(evs.get(1), 1), item(evs.get(2), 1)));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        // 整包失败：无组合包、其余证物仍 SEALED
        assertThat(packageRepository.findByKey(packageKey)).isEmpty();
        JsonNode chain = objectMapper.readTree(mockMvc.perform(
                        get("/api/evidence/{key}/custody-chain", evs.get(1))).andReturn()
                .getResponse().getContentAsString());
        assertThat(chain.get("evidence").get("status").asText()).isEqualTo("SEALED");
    }

    @Test
    void createRejectsSealBrokenEvidenceWith422() throws Exception {
        List<String> evs = threeEvidence();
        Map<String, Object> inspectBody = new LinkedHashMap<>();
        inspectBody.put("commandKey", uniqueKey("CMD"));
        inspectBody.put("passed", false);
        inspectBody.put("note", "封条破损");
        MvcResult inspected = mockMvc.perform(
                        post("/api/evidence/{key}/seal-inspections", evs.get(0))
                                .header(ACTOR_HEADER, "alice")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(inspectBody)))
                .andReturn();
        assertThat(inspected.getResponse().getStatus()).isEqualTo(200);

        String packageKey = uniqueKey("pkg");
        MvcResult result = createPackage("alice", packageKey, "bob",
                List.of(item(evs.get(0), 2), item(evs.get(1), 1)));

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(packageRepository.findByKey(packageKey)).isEmpty();
    }

    @Test
    void createRejectsVersionMismatch() throws Exception {
        List<String> evs = threeEvidence();
        String packageKey = uniqueKey("pkg");

        MvcResult result = createPackage("alice", packageKey, "bob",
                List.of(item(evs.get(0), 1), item(evs.get(1), 99), item(evs.get(2), 1)));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(packageRepository.findByKey(packageKey)).isEmpty();
    }

    @Test
    void createRejectsOutOfRangeSizeAndDuplicateAndSelfBorrower() throws Exception {
        String e1 = uniqueKey("ev");
        String e2 = uniqueKey("ev");
        intake("alice", e1);
        intake("alice", e2);

        // 少于 2 件：400
        assertThat(createPackage("alice", uniqueKey("pkg"), "bob", List.of(item(e1, 1)))
                .getResponse().getStatus()).isEqualTo(400);

        // 包内证物重复：400
        assertThat(createPackage("alice", uniqueKey("pkg"), "bob",
                List.of(item(e1, 1), item(e1, 1))).getResponse().getStatus()).isEqualTo(400);

        // 借用人就是经办人本人：400
        assertThat(createPackage("alice", uniqueKey("pkg"), "alice",
                List.of(item(e1, 1), item(e2, 1))).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void duplicatePackageKeyReturns409() throws Exception {
        List<String> evs = threeEvidence();
        String packageKey = uniqueKey("pkg");
        assertThat(createPackage("alice", packageKey, "bob",
                List.of(item(evs.get(0), 1), item(evs.get(1), 1), item(evs.get(2), 1)))
                .getResponse().getStatus()).isEqualTo(200);

        String a = uniqueKey("ev");
        String b = uniqueKey("ev");
        intake("alice", a);
        intake("alice", b);
        assertThat(createPackage("alice", packageKey, "bob",
                List.of(item(a, 1), item(b, 1))).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void partialReturnsThenFinalReturnClosesPackageWithSnapshots() throws Exception {
        List<String> evs = threeEvidence();
        String packageKey = uniqueKey("pkg");
        assertThat(createPackage("alice", packageKey, "bob",
                List.of(item(evs.get(0), 1), item(evs.get(1), 1), item(evs.get(2), 1)))
                .getResponse().getStatus()).isEqualTo(200);

        MvcResult first = returnBatch("alice", packageKey, "recv-r1", "recv-r2",
                List.of(returnItem(evs.get(0), 1), returnItem(evs.get(1), 1)));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(firstBody.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(firstBody.get("batches")).hasSize(1);
        assertThat(firstBody.get("batches").get(0).get("items")).hasSize(2);
        List<String> remaining = objectMapper.convertValue(firstBody.get("remaining"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        assertThat(remaining).containsExactly(evs.get(2));
        assertThat(firstBody.get("items").get(0).get("returned").asBoolean()).isTrue();

        MvcResult second = returnBatch("alice", packageKey, "recv-r1", "recv-r2",
                List.of(returnItem(evs.get(2), 1)));
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        JsonNode closed = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.get("closedAt").isNull()).isFalse();
        assertThat(closed.get("remaining")).isEmpty();
        assertThat(closed.get("batches")).hasSize(2);
        assertThat(closed.get("batches").get(0).get("items")).hasSize(2);
        assertThat(closed.get("batches").get(1).get("items")).hasSize(1);

        // 全部证物回到 SEALED
        for (String ev : evs) {
            JsonNode chain = objectMapper.readTree(mockMvc.perform(
                            get("/api/evidence/{key}/custody-chain", ev)).andReturn()
                    .getResponse().getContentAsString());
            assertThat(chain.get("evidence").get("status").asText()).isEqualTo("SEALED");
        }

        // 不可变快照：两条批次快照 + 一条关闭快照；关闭快照含全部借出与全部批次
        long packageId = packageRepository.findByKey(packageKey).orElseThrow().id();
        List<PackageSnapshot> snapshots = snapshotRepository.findByPackageIdOrdered(packageId);
        assertThat(snapshots).hasSize(3);
        assertThat(snapshots.get(0).snapshotType()).isEqualTo(PackageSnapshot.TYPE_RETURN);
        assertThat(snapshots.get(1).snapshotType()).isEqualTo(PackageSnapshot.TYPE_RETURN);
        assertThat(snapshots.get(2).snapshotType()).isEqualTo(PackageSnapshot.TYPE_CLOSE);
        JsonNode closeJson = objectMapper.readTree(snapshots.get(2).snapshotJson());
        assertThat(closeJson.get("loan").get("items")).hasSize(3);
        assertThat(closeJson.get("returnBatches")).hasSize(2);
    }

    @Test
    void returnRejectsDuplicateAlreadyReturnedForeignAndVersionMismatch() throws Exception {
        List<String> evs = threeEvidence();
        String packageKey = uniqueKey("pkg");
        createPackage("alice", packageKey, "bob",
                List.of(item(evs.get(0), 1), item(evs.get(1), 1), item(evs.get(2), 1)));

        // 同批重复证物：400
        assertThat(returnBatch("alice", packageKey, "recv-r1", "recv-r2",
                List.of(returnItem(evs.get(0), 1), returnItem(evs.get(0), 1)))
                .getResponse().getStatus()).isEqualTo(400);

        // 封条版本不符：409，整批不落账
        assertThat(returnBatch("alice", packageKey, "recv-r1", "recv-r2",
                List.of(returnItem(evs.get(0), 1), returnItem(evs.get(1), 77)))
                .getResponse().getStatus()).isEqualTo(409);

        // 非本包证物：400
        String foreign = uniqueKey("ev");
        intake("alice", foreign);
        assertThat(returnBatch("alice", packageKey, "recv-r1", "recv-r2",
                List.of(returnItem(foreign, 1))).getResponse().getStatus()).isEqualTo(400);

        // 第一批合法归还成功：此前失败批次均未落账
        MvcResult ok = returnBatch("alice", packageKey, "recv-r1", "recv-r2",
                List.of(returnItem(evs.get(0), 1)));
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(ok.getResponse().getContentAsString());
        assertThat(body.get("batches")).hasSize(1);

        // 已归还证物再次归还：409，整批不落账（evs[1] 仍未归还）
        assertThat(returnBatch("alice", packageKey, "recv-r1", "recv-r2",
                List.of(returnItem(evs.get(0), 1), returnItem(evs.get(1), 1)))
                .getResponse().getStatus()).isEqualTo(409);
        JsonNode after = getPackage(packageKey);
        assertThat(after.get("batches")).hasSize(1);
        List<String> remaining = objectMapper.convertValue(after.get("remaining"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        assertThat(remaining).containsExactlyInAnyOrder(evs.get(1), evs.get(2));
    }

    @Test
    void returnRejectsSamePersonMissingGrantAndClosedPackage() throws Exception {
        List<String> evs = threeEvidence();
        String packageKey = uniqueKey("pkg");
        createPackage("alice", packageKey, "bob",
                List.of(item(evs.get(0), 1), item(evs.get(1), 1), item(evs.get(2), 1)));

        // 接收人与复核人相同：400
        assertThat(returnBatch("alice", packageKey, "recv-r1", "recv-r1",
                List.of(returnItem(evs.get(0), 1))).getResponse().getStatus()).isEqualTo(400);

        // 无案件权限：403
        assertThat(returnBatch("alice", packageKey, "recv-r1", "nobody",
                List.of(returnItem(evs.get(0), 1))).getResponse().getStatus()).isEqualTo(403);

        // 全部归还关闭后再归还：409
        returnBatch("alice", packageKey, "recv-r1", "recv-r2",
                List.of(returnItem(evs.get(0), 1), returnItem(evs.get(1), 1),
                        returnItem(evs.get(2), 1)));
        assertThat(returnBatch("alice", packageKey, "recv-r1", "recv-r2",
                List.of(returnItem(evs.get(0), 1))).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void cancelOnlyWithoutReturnsAndRestoresSealed() throws Exception {
        List<String> evs = threeEvidence();
        String packageKey = uniqueKey("pkg");
        createPackage("alice", packageKey, "bob",
                List.of(item(evs.get(0), 1), item(evs.get(1), 1), item(evs.get(2), 1)));

        // 非经办人不能撤销
        assertThat(cancel("bob", packageKey).getResponse().getStatus()).isEqualTo(409);

        MvcResult result = cancel("alice", packageKey);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("remaining")).isEmpty();
        for (String ev : evs) {
            JsonNode chain = objectMapper.readTree(mockMvc.perform(
                            get("/api/evidence/{key}/custody-chain", ev)).andReturn()
                    .getResponse().getContentAsString());
            assertThat(chain.get("evidence").get("status").asText()).isEqualTo("SEALED");
        }

        // 撤销后不能再撤销/归还
        assertThat(cancel("alice", packageKey).getResponse().getStatus()).isEqualTo(409);
        assertThat(returnBatch("alice", packageKey, "recv-r1", "recv-r2",
                List.of(returnItem(evs.get(0), 1))).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void cancelAfterAnyReturnReturns409() throws Exception {
        List<String> evs = threeEvidence();
        String packageKey = uniqueKey("pkg");
        createPackage("alice", packageKey, "bob",
                List.of(item(evs.get(0), 1), item(evs.get(1), 1), item(evs.get(2), 1)));
        returnBatch("alice", packageKey, "recv-r1", "recv-r2",
                List.of(returnItem(evs.get(0), 1)));

        assertThat(cancel("alice", packageKey).getResponse().getStatus()).isEqualTo(409);
        assertThat(getPackage(packageKey).get("status").asText()).isEqualTo("PARTIAL");
    }

    @Test
    void createAndReturnIdempotentReplayAndReorderSameParams() throws Exception {
        List<String> evs = threeEvidence();
        String packageKey = uniqueKey("pkg");
        String commandKey = uniqueKey("CMD");
        MvcResult first = createPackage("alice", commandKey, packageKey, "bob",
                List.of(item(evs.get(2), 1), item(evs.get(0), 1), item(evs.get(1), 1)));
        // 子集换序视为同参：重放返回首次完整响应
        MvcResult replay = createPackage("alice", commandKey, packageKey, "bob",
                List.of(item(evs.get(0), 1), item(evs.get(1), 1), item(evs.get(2), 1)));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 同键异参：409
        Map<String, Object> other = new LinkedHashMap<>();
        other.put("commandKey", commandKey);
        other.put("packageKey", uniqueKey("pkg"));
        other.put("purpose", "组合鉴定用");
        other.put("borrowerId", "carol");
        other.put("dueAt", dueIn(24));
        other.put("items", List.of(item(evs.get(0), 1), item(evs.get(1), 1)));
        MvcResult conflict = mockMvc.perform(post("/api/evidence/packages")
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(other)))
                .andReturn();
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);

        // 分批归还幂等重放（换序同参），不产生第二个批次
        String returnCommand = uniqueKey("CMD");
        MvcResult r1 = returnBatch("alice", returnCommand, packageKey, "recv-r1", "recv-r2",
                List.of(returnItem(evs.get(1), 1), returnItem(evs.get(0), 1)));
        MvcResult r2 = returnBatch("alice", returnCommand, packageKey, "recv-r1", "recv-r2",
                List.of(returnItem(evs.get(0), 1), returnItem(evs.get(1), 1)));
        assertThat(r1.getResponse().getStatus()).isEqualTo(200);
        assertThat(r2.getResponse().getStatus()).isEqualTo(200);
        assertThat(r2.getResponse().getContentAsString()).isEqualTo(r1.getResponse().getContentAsString());
        assertThat(getPackage(packageKey).get("batches")).hasSize(1);
    }

    @Test
    void failedCommandDoesNotOccupyKey() throws Exception {
        List<String> evs = threeEvidence();
        String packageKey = uniqueKey("pkg");
        String commandKey = uniqueKey("CMD");
        // 首次版本不符失败（409），不占键
        assertThat(createPackage("alice", commandKey, packageKey, "bob",
                List.of(item(evs.get(0), 99), item(evs.get(1), 1))).getResponse().getStatus())
                .isEqualTo(409);
        // 同 commandKey 改正参数后成功
        MvcResult retry = createPackage("alice", commandKey, packageKey, "bob",
                List.of(item(evs.get(0), 1), item(evs.get(1), 1), item(evs.get(2), 1)));
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void getUnknownPackageReturns404() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/packages/{key}", uniqueKey("nope")))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void grantCaseEndpointIsIdempotentAndEnablesReturn() throws Exception {
        String caseKey = "CASE-GRANT-" + uniqueKey("x");
        String e1 = intake("alice", uniqueKey("ev"), caseKey);
        String e2 = intake("alice", uniqueKey("ev"), caseKey);
        String packageKey = uniqueKey("pkg");
        // 尚未授权：归还接收人无权限
        createPackage("alice", uniqueKey("CMD"), packageKey, "bob",
                List.of(item(e1, 1), item(e2, 1)));
        assertThat(returnBatch("alice", packageKey, "granted-a", "granted-b",
                List.of(returnItem(e1, 1), returnItem(e2, 1))).getResponse().getStatus())
                .isEqualTo(403);

        for (int i = 0; i < 2; i++) {
            MvcResult granted = mockMvc.perform(post(
                            "/api/evidence/packages/cases/{caseKey}/grants/{userId}",
                            caseKey, "granted-a"))
                    .andReturn();
            assertThat(granted.getResponse().getStatus()).isEqualTo(200);
        }
        mockMvc.perform(post("/api/evidence/packages/cases/{caseKey}/grants/{userId}",
                        caseKey, "granted-b")).andReturn();

        MvcResult ok = returnBatch("alice", packageKey, "granted-a", "granted-b",
                List.of(returnItem(e1, 1), returnItem(e2, 1)));
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(ok.getResponse().getContentAsString()).get("status").asText())
                .isEqualTo("CLOSED");
    }
}
