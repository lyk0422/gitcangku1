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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 容器巡检双人确认与隔离复核 API 测试：主流程、失败分支、幂等边界与查询展示，
 * 全部使用自动建表的 H2 内存库（MODE=MySQL）真实落库验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContainerInspectionApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private MvcResult post(String path, String actor, Object body, Object... uriVars) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path, uriVars)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private Map<String, Object> intakeBody(String commandKey, String evidenceKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-C");
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-" + evidenceKey);
        return body;
    }

    private void intake(String actor, String evidenceKey) throws Exception {
        assertThat(post("/api/evidence", actor, intakeBody(uniqueKey("CMD"), evidenceKey))
                .getResponse().getStatus()).isEqualTo(201);
    }

    private String createContainer(String actor, String location) throws Exception {
        String containerKey = uniqueKey("CTR");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("containerKey", containerKey);
        body.put("location", location);
        assertThat(post("/api/containers", actor, body).getResponse().getStatus()).isEqualTo(201);
        return containerKey;
    }

    private void place(String actor, String evidenceKey, String containerKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("containerKey", containerKey);
        assertThat(post("/api/evidence/{key}/container-placement", actor, body, evidenceKey)
                .getResponse().getStatus()).isEqualTo(200);
    }

    private MvcResult witness(String actor, String containerKey, String requestId,
                              int version, String sealResult, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("witnessedVersion", version);
        body.put("sealResult", sealResult);
        body.put("note", note);
        return post("/api/containers/{key}/inspections", actor, body, containerKey);
    }

    private MvcResult witness(String actor, String containerKey, String requestId,
                              int version, String sealResult) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("witnessedVersion", version);
        body.put("sealResult", sealResult);
        return post("/api/containers/{key}/inspections", actor, body, containerKey);
    }

    private JsonNode inspectionView(String requestId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/containers/inspections/{requestId}", requestId))
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

    private MvcResult initiate(String actor, String evidenceKey, String toCustodian) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", toCustodian);
        return post("/api/evidence/{key}/transfers", actor, body, evidenceKey);
    }

    private MvcResult decide(String actor, String evidenceKey, String action) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        return post("/api/evidence/{key}/transfers/" + action, actor, body, evidenceKey);
    }

    private MvcResult review(String reviewer, String requestId, String commandKey, String note)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("note", note);
        return post("/api/containers/inspections/{requestId}/review", reviewer, body, requestId);
    }

    /**
     * 构造标准场景：容器 C（保管人甲/jia）内有证物 A、B，返回 [A, B, C]。
     */
    private String[] scenarioWithAB() throws Exception {
        String a = uniqueKey("EV-A");
        String b = uniqueKey("EV-B");
        intake("jia", a);
        intake("jia", b);
        String c = createContainer("jia", "VAULT-1");
        place("jia", a, c);
        place("jia", b, c);
        return new String[]{a, b, c};
    }

    @Test
    void twoWitnessesAnomalyQuarantinesAllInboxItemsAtOnce() throws Exception {
        String[] s = scenarioWithAB();
        String a = s[0];
        String b = s[1];
        String c = s[2];
        String requestId = uniqueKey("REQ");

        MvcResult first = witness("jia", c, requestId, 1, "ANOMALY", "seal cut");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        JsonNode awaiting = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(awaiting.get("status").asText()).isEqualTo("AWAITING_WITNESS");
        // 仅一人确认：证物状态不变，箱内清单尚未冻结
        assertThat(chain(a).get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(awaiting.get("items")).hasSize(0);
        assertThat(awaiting.get("witnesses")).hasSize(1);

        MvcResult second = witness("yi", c, requestId, 1, "ANOMALY", "seal cut");
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        JsonNode confirmed = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(confirmed.get("status").asText()).isEqualTo("CONFIRMED_ANOMALY");
        assertThat(confirmed.get("sealResult").asText()).isEqualTo("ANOMALY");
        assertThat(confirmed.get("sealVersion").asInt()).isEqualTo(1);
        assertThat(confirmed.get("witnesses")).hasSize(2);
        assertThat(confirmed.get("witnesses").findValuesAsText("witnessId"))
                .containsExactly("jia", "yi");

        // A、B 两件一次隔离，原位置快照保留为容器 C、原保管人 jia
        assertThat(chain(a).get("evidence").get("status").asText()).isEqualTo("QUARANTINED");
        assertThat(chain(b).get("evidence").get("status").asText()).isEqualTo("QUARANTINED");
        assertThat(confirmed.get("items")).hasSize(2);
        for (JsonNode item : confirmed.get("items")) {
            assertThat(item.get("snapshotContainerKey").asText()).isEqualTo(c);
            assertThat(item.get("snapshotCustodianId").asText()).isEqualTo("jia");
            assertThat(item.get("snapshotStatus").asText()).isEqualTo("SEALED");
            assertThat(item.get("disposition").asText()).isEqualTo("QUARANTINED");
            assertThat(item.get("releasedAt").isNull()).isTrue();
        }
        assertThat(confirmed.get("items").findValuesAsText("evidenceKey"))
                .containsExactlyInAnyOrder(a, b);
    }

    @Test
    void borrowAndTransferReturn409BeforeReview() throws Exception {
        String[] s = scenarioWithAB();
        String a = s[0];
        String b = s[1];
        String c = s[2];
        String requestId = uniqueKey("REQ");
        witness("jia", c, requestId, 1, "ANOMALY");
        assertThat(witness("yi", c, requestId, 1, "ANOMALY").getResponse().getStatus())
                .isEqualTo(200);

        // 复核前借出 A 返回 409
        Map<String, Object> loanBody = new LinkedHashMap<>();
        loanBody.put("commandKey", uniqueKey("CMD"));
        loanBody.put("loanKey", uniqueKey("LOAN"));
        loanBody.put("borrowerId", "dave");
        loanBody.put("purpose", "lab test");
        loanBody.put("dueAt", "2099-01-01T00:00:00");
        assertThat(post("/api/evidence/{key}/loans", "jia", loanBody, a).getResponse().getStatus())
                .isEqualTo(409);

        // B 也不得转移：发起 409；接受与取消同样 409（无待接收交接）
        assertThat(initiate("jia", b, "yi").getResponse().getStatus()).isEqualTo(409);
        assertThat(decide("yi", b, "accept").getResponse().getStatus()).isEqualTo(409);
        assertThat(decide("jia", b, "cancel").getResponse().getStatus()).isEqualTo(409);

        // 单人封条核验同样 409
        Map<String, Object> inspectBody = new LinkedHashMap<>();
        inspectBody.put("commandKey", uniqueKey("CMD"));
        inspectBody.put("passed", true);
        inspectBody.put("note", "try");
        assertThat(post("/api/evidence/{key}/seal-inspections", "jia", inspectBody, a)
                .getResponse().getStatus()).isEqualTo(409);

        // 证物与容器均保持隔离状态
        assertThat(chain(a).get("evidence").get("status").asText()).isEqualTo("QUARANTINED");
        assertThat(chain(b).get("evidence").get("status").asText()).isEqualTo("QUARANTINED");
    }

    @Test
    void reviewReleaseRestoresSealedAndKeepsHistoryWithTimestamp() throws Exception {
        String[] s = scenarioWithAB();
        String a = s[0];
        String b = s[1];
        String c = s[2];
        String requestId = uniqueKey("REQ");
        witness("jia", c, requestId, 1, "ANOMALY");
        witness("yi", c, requestId, 1, "ANOMALY");

        String reviewKey = uniqueKey("CMD");
        MvcResult reviewed = review("judge", requestId, reviewKey, "false alarm, resealed");
        assertThat(reviewed.getResponse().getStatus()).isEqualTo(200);
        JsonNode view = objectMapper.readTree(reviewed.getResponse().getContentAsString());
        assertThat(view.get("status").asText()).isEqualTo("CONFIRMED_ANOMALY");
        assertThat(view.get("review").get("reviewerId").asText()).isEqualTo("judge");
        assertThat(view.get("review").get("conclusion").asText()).isEqualTo("RELEASED");
        assertThat(view.get("review").get("createdAt").isNull()).isFalse();
        for (JsonNode item : view.get("items")) {
            assertThat(item.get("disposition").asText()).isEqualTo("RELEASED");
            assertThat(item.get("releasedAt").isNull()).isFalse();
            // 原位置快照不随复核改写
            assertThat(item.get("snapshotContainerKey").asText()).isEqualTo(c);
            assertThat(item.get("snapshotCustodianId").asText()).isEqualTo("jia");
        }

        // 证物恢复 SEALED，可正常交接
        assertThat(chain(a).get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(chain(b).get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(initiate("jia", a, "yi").getResponse().getStatus()).isEqualTo(200);
        assertThat(decide("yi", a, "accept").getResponse().getStatus()).isEqualTo(200);

        // 历史位置轨迹：隔离行仍保留原容器 C，放行与交接接受只追加新行
        JsonNode aChain = chain(a);
        assertThat(aChain.get("locations").findValuesAsText("reason"))
                .containsSubsequence("INTAKE", "PLACED", "INSPECTION_QUARANTINE",
                        "REVIEW_RELEASE", "TRANSFER_ACCEPT");
        int idx = aChain.get("locations").findValuesAsText("reason")
                .indexOf("INSPECTION_QUARANTINE");
        assertThat(aChain.get("locations").get(idx).get("containerKey").asText()).isEqualTo(c);
        assertThat(aChain.get("locations").get(idx).get("custodianId").asText()).isEqualTo("jia");        // 巡检与隔离历史仍可查询到
        JsonNode history = inspectionView(requestId);
        assertThat(history.get("witnesses")).hasSize(2);
        assertThat(history.get("items")).hasSize(2);
        assertThat(history.get("review").get("note").asText()).isEqualTo("false alarm, resealed");

        // 重复复核 409；同 commandKey 同参重放返回首次结果
        assertThat(review("judge", requestId, uniqueKey("CMD"), "again").getResponse().getStatus())
                .isEqualTo(409);
        MvcResult replay = review("judge", requestId, reviewKey, "false alarm, resealed");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(reviewed.getResponse().getContentAsString());
    }

    @Test
    void versionMismatchOrDisagreementDoesNotChangeEvidence() throws Exception {
        String[] s = scenarioWithAB();
        String a = s[0];
        String b = s[1];
        String c = s[2];

        // 版本不一致：建单版本 1，第二人按版本 2 见证
        String versionReq = uniqueKey("REQ");
        witness("jia", c, versionReq, 1, "ANOMALY");
        MvcResult mismatch = witness("yi", c, versionReq, 2, "ANOMALY");
        assertThat(mismatch.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(mismatch.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("CONFLICTED");
        assertThat(chain(a).get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(chain(b).get("evidence").get("status").asText()).isEqualTo("SEALED");

        // 结论分歧：一人异常一人完好
        String disagreeReq = uniqueKey("REQ");
        witness("jia", c, disagreeReq, 1, "ANOMALY");
        MvcResult disagreed = witness("yi", c, disagreeReq, 1, "INTACT");
        assertThat(objectMapper.readTree(disagreed.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("CONFLICTED");
        assertThat(chain(a).get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(chain(b).get("evidence").get("status").asText()).isEqualTo("SEALED");

        // 分歧作废的巡检不可再被补票复活
        assertThat(witness("jia", c, disagreeReq, 1, "INTACT").getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void onlyOneWitnessDoesNotChangeEvidenceAndSameCustodianCannotWitnessTwice() throws Exception {
        String[] s = scenarioWithAB();
        String a = s[0];
        String b = s[1];
        String c = s[2];
        String requestId = uniqueKey("REQ");
        witness("jia", c, requestId, 1, "ANOMALY");

        // 仅一人确认时证物始终 SEALED，容器仍 ACTIVE，可继续正常交接
        assertThat(chain(a).get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(initiate("jia", a, "yi").getResponse().getStatus()).isEqualTo(200);
        assertThat(decide("yi", a, "accept").getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(b).get("evidence").get("status").asText()).isEqualTo("SEALED");

        // 同一保管人不能充当两名见证人
        String requestId2 = uniqueKey("REQ");
        witness("jia", c, requestId2, 1, "ANOMALY");
        assertThat(witness("jia", c, requestId2, 1, "ANOMALY").getResponse().getStatus())
                .isEqualTo(409);
        witness("yi", c, requestId2, 1, "ANOMALY");
        // 第三人对已完成巡检提交 409
        assertThat(witness("bing", c, requestId2, 1, "ANOMALY").getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void requestIdReplayReturnsFirstQuarantineListButChangedSealResultReturns409() throws Exception {
        String[] s = scenarioWithAB();
        String a = s[0];
        String b = s[1];
        String c = s[2];
        String requestId = uniqueKey("REQ");
        witness("jia", c, requestId, 1, "ANOMALY");
        MvcResult completed = witness("yi", c, requestId, 1, "ANOMALY");
        assertThat(completed.getResponse().getStatus()).isEqualTo(200);
        String firstBody = completed.getResponse().getContentAsString();

        // 原见证人同参重放：返回首次完成响应（首次隔离清单）
        MvcResult replayJia = witness("jia", c, requestId, 1, "ANOMALY");
        assertThat(replayJia.getResponse().getStatus()).isEqualTo(200);
        assertThat(replayJia.getResponse().getContentAsString()).isEqualTo(firstBody);
        MvcResult replayYi = witness("yi", c, requestId, 1, "ANOMALY");
        assertThat(replayYi.getResponse().getContentAsString()).isEqualTo(firstBody);

        // 换封条结果（异常→完好）重放 409；换见证版本 409
        assertThat(witness("jia", c, requestId, 1, "INTACT").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(witness("yi", c, requestId, 2, "ANOMALY").getResponse().getStatus())
                .isEqualTo(409);

        // 重放未重复隔离：清单仍只有两件
        JsonNode view = inspectionView(requestId);
        assertThat(view.get("items")).hasSize(2);
        assertThat(chain(a).get("evidence").get("status").asText()).isEqualTo("QUARANTINED");
        assertThat(chain(b).get("evidence").get("status").asText()).isEqualTo("QUARANTINED");
        // 每件证物只有 INTAKE/PLACED/QUARANTINE 三条轨迹，重放未追加
        assertThat(chain(a).get("locations")).hasSize(3);
        assertThat(chain(b).get("locations")).hasSize(3);
    }

    @Test
    void intactInspectionFreezesListWithoutQuarantine() throws Exception {
        String[] s = scenarioWithAB();
        String a = s[0];
        String b = s[1];
        String c = s[2];
        String requestId = uniqueKey("REQ");
        witness("jia", c, requestId, 1, "INTACT");
        MvcResult completed = witness("yi", c, requestId, 1, "INTACT");
        JsonNode view = objectMapper.readTree(completed.getResponse().getContentAsString());
        assertThat(view.get("status").asText()).isEqualTo("CONFIRMED_INTACT");
        assertThat(view.get("items")).hasSize(2);
        for (JsonNode item : view.get("items")) {
            assertThat(item.get("disposition").asText()).isEqualTo("UNQUARANTINED");
            assertThat(item.get("snapshotContainerKey").asText()).isEqualTo(c);
        }
        assertThat(chain(a).get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(chain(b).get("evidence").get("status").asText()).isEqualTo("SEALED");

        // 完好巡检不产生复核入口
        assertThat(review("judge", requestId, uniqueKey("CMD"), "x").getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void borrowedEvidenceOutOfBoxIsNotQuarantinedAndReturnBlockedUntilReview() throws Exception {
        String[] s = scenarioWithAB();
        String a = s[0];
        String b = s[1];
        String c = s[2];

        // A 借出在外（container_key 仍登记为 C，物理不在箱）；期限 24 小时，符合 72 小时上限
        String loanKey = uniqueKey("LOAN");
        String dueAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(24)
                .toString();
        Map<String, Object> loanBody = new LinkedHashMap<>();
        loanBody.put("commandKey", uniqueKey("CMD"));
        loanBody.put("loanKey", loanKey);
        loanBody.put("borrowerId", "dave");
        loanBody.put("purpose", "lab");
        loanBody.put("dueAt", dueAt);
        assertThat(post("/api/evidence/{key}/loans", "jia", loanBody, a).getResponse().getStatus())
                .isEqualTo(200);

        String requestId = uniqueKey("REQ");
        witness("jia", c, requestId, 1, "ANOMALY");
        MvcResult completed = witness("yi", c, requestId, 1, "ANOMALY");
        JsonNode view = objectMapper.readTree(completed.getResponse().getContentAsString());

        int idxA = view.get("items").findValuesAsText("evidenceKey").indexOf(a);
        assertThat(view.get("items").get(idxA).get("disposition").asText())
                .isEqualTo("NOT_PRESENT");
        assertThat(view.get("items").get(idxA).get("snapshotStatus").asText())
                .isEqualTo("BORROWED");
        // A 借出在外不被隔离，保持 BORROWED；B 在箱被隔离
        assertThat(chain(a).get("evidence").get("status").asText()).isEqualTo("BORROWED");
        assertThat(chain(b).get("evidence").get("status").asText()).isEqualTo("QUARANTINED");

        // 复核前归还 A 返回 409（异常容器中的证物复核前归还被禁止）
        Map<String, Object> returnBody = new LinkedHashMap<>();
        returnBody.put("commandKey", uniqueKey("CMD"));
        returnBody.put("loanKey", loanKey);
        returnBody.put("sealIntact", true);
        returnBody.put("note", "returned");
        assertThat(post("/api/evidence/{key}/loans/return", "jia", returnBody, a)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(chain(a).get("evidence").get("status").asText()).isEqualTo("BORROWED");

        // 复核放行：B 恢复 SEALED；A 因不在箱不被放行，仍 BORROWED，之后容器已 ACTIVE 可正常归还
        review("judge", requestId, uniqueKey("CMD"), "ok");
        assertThat(chain(b).get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(chain(a).get("evidence").get("status").asText()).isEqualTo("BORROWED");
        assertThat(post("/api/evidence/{key}/loans/return", "jia", returnBody, a)
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(a).get("evidence").get("status").asText()).isEqualTo("SEALED");
    }

    @Test
    void sealReplacementBumpsVersionAndOldVersionWitnessConflicts() throws Exception {
        String[] s = scenarioWithAB();
        String a = s[0];
        String c = s[2];

        Map<String, Object> replaceBody = new LinkedHashMap<>();
        replaceBody.put("commandKey", uniqueKey("CMD"));
        replaceBody.put("note", "routine seal replacement");
        MvcResult replaced = post("/api/containers/{key}/seal-replacements", "jia", replaceBody, c);
        assertThat(replaced.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(replaced.getResponse().getContentAsString())
                .get("sealVersion").asInt()).isEqualTo(2);

        // 见证旧版本 1 与当前版本 2 不一致：409，且不建单、不动证物
        String requestId = uniqueKey("REQ");
        assertThat(witness("jia", c, requestId, 1, "ANOMALY").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(chain(a).get("evidence").get("status").asText()).isEqualTo("SEALED");

        // 两名见证人按新版本 2 一致确认异常，隔离生效
        witness("jia", c, requestId, 2, "ANOMALY");
        witness("yi", c, requestId, 2, "ANOMALY");
        assertThat(chain(a).get("evidence").get("status").asText()).isEqualTo("QUARANTINED");

        // 隔离期间换封条 409
        Map<String, Object> again = new LinkedHashMap<>();
        again.put("commandKey", uniqueKey("CMD"));
        again.put("note", "try while quarantined");
        assertThat(post("/api/containers/{key}/seal-replacements", "jia", again, c)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void placeIntoContainerRejectsNonCustodianAndQuarantinedContainer() throws Exception {
        String[] s = scenarioWithAB();
        String c = s[2];

        String other = uniqueKey("EV");
        intake("yi", other);
        // 非保管人放入 409
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("containerKey", c);
        assertThat(post("/api/evidence/{key}/container-placement", "jia", body, other)
                .getResponse().getStatus()).isEqualTo(409);

        String requestId = uniqueKey("REQ");
        witness("jia", c, requestId, 1, "ANOMALY");
        witness("yi", c, requestId, 1, "ANOMALY");
        // 容器隔离后禁止放入
        assertThat(post("/api/evidence/{key}/container-placement", "yi", body, other)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void pendingTransferInsideBoxIsVoidedByAnomalyQuarantine() throws Exception {
        String[] s = scenarioWithAB();
        String a = s[0];
        String c = s[2];

        // A 已发起交接（待接收），异常双人确认时交接同步作废，A 仍被隔离
        assertThat(initiate("jia", a, "yi").getResponse().getStatus()).isEqualTo(200);
        String requestId = uniqueKey("REQ");
        witness("jia", c, requestId, 1, "ANOMALY");
        witness("yi", c, requestId, 1, "ANOMALY");

        JsonNode aChain = chain(a);
        assertThat(aChain.get("evidence").get("status").asText()).isEqualTo("QUARANTINED");
        assertThat(aChain.get("evidence").get("custodianId").asText()).isEqualTo("jia");
        assertThat(aChain.get("transfers").get(0).get("status").asText()).isEqualTo("CANCELLED");
        // 快照保留待接收交接时的状态
        int idx = inspectionView(requestId).get("items").findValuesAsText("evidenceKey").indexOf(a);
        JsonNode item = inspectionView(requestId).get("items").get(idx);
        assertThat(item.get("snapshotStatus").asText()).isEqualTo("TRANSFER_PENDING");

        // 已作废交接接受/取消均 409
        assertThat(decide("yi", a, "accept").getResponse().getStatus()).isEqualTo(409);
        assertThat(decide("jia", a, "cancel").getResponse().getStatus()).isEqualTo(409);
    }
}
