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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 阅检副本 API 测试：签发/读取/回收主流程、失败分支、幂等重放与 UTC 有效期边界。
 * 有效期边界使用固定时钟驱动（10:29 可读、10:30 失效），无需真实等待。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InspectionCopyApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    /** 测试基准时刻（UTC）：副本有效至 10:30。 */
    private static final Instant T1029 = Instant.parse("2026-10-04T10:29:00Z");
    private static final Instant T1030 = Instant.parse("2026-10-04T10:30:00Z");

    private static final String DIGEST = "a1b2c3d4e5f6";
    private static final String SYNTHETIC_TEXT = "synthetic-copy-text-001";

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

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private MvcResult intake(String actor, String commandKey, String evidenceKey,
                             boolean withMeta) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-1");
        if (withMeta) {
            body.put("digest", DIGEST);
            body.put("syntheticText", SYNTHETIC_TEXT);
        }
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult issue(String actor, String evidenceKey, String requestId, String reviewer,
                            LocalDateTime validFrom, LocalDateTime validTo,
                            Long expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("reviewerId", reviewer);
        if (validFrom != null) {
            body.put("validFrom", validFrom.toString());
        }
        body.put("validTo", validTo.toString());
        if (expectedVersion != null) {
            body.put("expectedVersion", expectedVersion);
        }
        return mockMvc.perform(post("/api/evidence/{key}/copies", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult read(String actor, String evidenceKey, String copyId) throws Exception {
        return mockMvc.perform(get("/api/evidence/{key}/copies/{copyId}", evidenceKey, copyId)
                        .header(ACTOR_HEADER, actor))
                .andReturn();
    }

    private MvcResult revoke(String actor, String evidenceKey, String copyId,
                             String requestId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        return mockMvc.perform(post("/api/evidence/{key}/copies/{copyId}/revoke",
                        evidenceKey, copyId)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult initiate(String actor, String evidenceKey, String commandKey,
                               String toCustodian) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("toCustodian", toCustodian);
        return mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult borrow(String actor, String evidenceKey, String commandKey,
                             String loanKey, String borrower, LocalDateTime dueAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("loanKey", loanKey);
        body.put("borrowerId", borrower);
        body.put("purpose", "lab-analysis");
        body.put("dueAt", dueAt.toString());
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult inspect(String actor, String evidenceKey, String commandKey,
                              boolean passed) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("passed", passed);
        body.put("note", "routine");
        return mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String issueCopy(String actor, String evidenceKey, String reviewer,
                             LocalDateTime validTo) throws Exception {
        MvcResult result = issue(actor, evidenceKey, uniqueKey("REQ"), reviewer, null,
                validTo, null);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return json(result).get("copyId").asText();
    }
    @Test
    void issueReadRevokeFullChain() throws Exception {
        fixClock(T1029);
        String evidenceKey = uniqueKey("EV");
        assertThat(intake("alice", uniqueKey("CMD"), evidenceKey, true).getResponse().getStatus())
                .isEqualTo(201);
        LocalDateTime validTo = LocalDateTime.ofInstant(T1030, ZoneOffset.UTC);

        MvcResult issued = issue("alice", evidenceKey, uniqueKey("REQ"), "carol", null, validTo, null);
        assertThat(issued.getResponse().getStatus()).isEqualTo(201);
        JsonNode content = json(issued);
        String copyId = content.get("copyId").asText();
        assertThat(content.get("reviewerId").asText()).isEqualTo("carol");
        assertThat(content.get("digest").asText()).isEqualTo(DIGEST);
        assertThat(content.get("syntheticText").asText()).isEqualTo(SYNTHETIC_TEXT);
        assertThat(content.get("watermark").asText()).contains(copyId).contains("carol");
        assertThat(content.get("validTo").asText()).startsWith("2026-10-04T10:30");

        // 阅检人有效期内可读；响应不含原件内部记录（保管人、封条号等）
        MvcResult readOk = read("carol", evidenceKey, copyId);
        assertThat(readOk.getResponse().getStatus()).isEqualTo(200);
        JsonNode readBody = json(readOk);
        assertThat(readBody.get("syntheticText").asText()).isEqualTo(SYNTHETIC_TEXT);
        assertThat(readBody.has("custodianId")).isFalse();
        assertThat(readBody.has("sealNo")).isFalse();

        // 越权读取 403；回收后读取 410；回收不删除签发历史（重放仍返回元数据）
        assertThat(read("dave", evidenceKey, copyId).getResponse().getStatus()).isEqualTo(403);
        assertThat(revoke("alice", evidenceKey, copyId, uniqueKey("REQ")).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(read("carol", evidenceKey, copyId).getResponse().getStatus()).isEqualTo(410);

        // 全部有效副本回收后交接放行
        assertThat(initiate("alice", evidenceKey, uniqueKey("CMD"), "bob").getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void issueByNonCustodianReturns403() throws Exception {
        fixClock(T1029);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, true);
        MvcResult result = issue("mallory", evidenceKey, uniqueKey("REQ"), "carol", null,
                LocalDateTime.ofInstant(T1030, ZoneOffset.UTC), null);
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void issueRequiresSealedNormalEvidence() throws Exception {
        fixClock(T1029);
        LocalDateTime validTo = LocalDateTime.ofInstant(T1030, ZoneOffset.UTC);
        // 待交接：422
        String pending = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), pending, true);
        initiate("alice", pending, uniqueKey("CMD"), "bob");
        assertThat(issue("alice", pending, uniqueKey("REQ"), "carol", null, validTo, null)
                .getResponse().getStatus()).isEqualTo(422);
        // 借出中：422
        String borrowed = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), borrowed, true);
        assertThat(borrow("alice", borrowed, uniqueKey("CMD"), uniqueKey("LN"), "dave",
                LocalDateTime.ofInstant(T1030, ZoneOffset.UTC)).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(issue("alice", borrowed, uniqueKey("REQ"), "carol", null, validTo, null)
                .getResponse().getStatus()).isEqualTo(422);
        // 封条异常：422
        String broken = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), broken, true);
        inspect("alice", broken, uniqueKey("CMD"), false);
        assertThat(issue("alice", broken, uniqueKey("REQ"), "carol", null, validTo, null)
                .getResponse().getStatus()).isEqualTo(422);
        // 未登记数字摘要/合成文本：422
        String noMeta = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), noMeta, false);
        assertThat(issue("alice", noMeta, uniqueKey("REQ"), "carol", null, validTo, null)
                .getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void issueValidityConstraints() throws Exception {
        fixClock(T1029);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, true);
        LocalDateTime from = LocalDateTime.ofInstant(T1029, ZoneOffset.UTC);
        // 区间超过一小时：400
        assertThat(issue("alice", evidenceKey, uniqueKey("REQ"), "carol", from,
                from.plusMinutes(61), null).getResponse().getStatus()).isEqualTo(400);
        // 终点不晚于起点：400
        assertThat(issue("alice", evidenceKey, uniqueKey("REQ"), "carol", from,
                from, null).getResponse().getStatus()).isEqualTo(400);
        // 终点不晚于当前时刻：400
        assertThat(issue("alice", evidenceKey, uniqueKey("REQ"), "carol", null,
                from.minusMinutes(1), null).getResponse().getStatus()).isEqualTo(400);
        // 恰好一小时：201
        assertThat(issue("alice", evidenceKey, uniqueKey("REQ"), "carol", from,
                from.plusMinutes(60), null).getResponse().getStatus()).isEqualTo(201);
    }
    @Test
    void replayIssueReturnsMetadataWithoutTextAndConflictOnDifferentParams() throws Exception {
        fixClock(T1029);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, true);
        String requestId = uniqueKey("REQ");
        LocalDateTime validTo = LocalDateTime.ofInstant(T1030, ZoneOffset.UTC);

        MvcResult first = issue("alice", evidenceKey, requestId, "carol", null, validTo, null);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(first).has("syntheticText")).isTrue();

        // 同键同参重放：仅元数据，不返回文本，水印不变
        MvcResult replay = issue("alice", evidenceKey, requestId, "carol", null, validTo, null);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        JsonNode replayBody = json(replay);
        assertThat(replayBody.has("syntheticText")).isFalse();
        assertThat(replayBody.get("copyId").asText()).isEqualTo(json(first).get("copyId").asText());
        assertThat(replayBody.get("watermark").asText())
                .isEqualTo(json(first).get("watermark").asText());

        // 同键异参：409
        assertThat(issue("alice", evidenceKey, requestId, "dave", null, validTo, null)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void permissionCheckPrecedesReplay() throws Exception {
        fixClock(T1029);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, true);
        String requestId = uniqueKey("REQ");
        LocalDateTime validTo = LocalDateTime.ofInstant(T1030, ZoneOffset.UTC);
        assertThat(issue("alice", evidenceKey, requestId, "carol", null, validTo, null)
                .getResponse().getStatus()).isEqualTo(201);
        // 非保管人使用同一 requestId 与相同参数：权限检查先于重放，返回 403 而非重放结果
        assertThat(issue("mallory", evidenceKey, requestId, "carol", null, validTo, null)
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void validityBoundaryLeftClosedRightOpen() throws Exception {
        fixClock(T1029);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, true);
        String requestId = uniqueKey("REQ");
        LocalDateTime validTo = LocalDateTime.ofInstant(T1030, ZoneOffset.UTC);

        MvcResult issued = issue("alice", evidenceKey, requestId, "carol", null, validTo, null);
        assertThat(issued.getResponse().getStatus()).isEqualTo(201);
        String copyId = json(issued).get("copyId").asText();
        String watermark = json(issued).get("watermark").asText();

        // 10:29 可读，但原件不能交接
        assertThat(read("carol", evidenceKey, copyId).getResponse().getStatus()).isEqualTo(200);
        assertThat(initiate("alice", evidenceKey, uniqueKey("CMD"), "bob")
                .getResponse().getStatus()).isEqualTo(409);

        // 10:30 读取 410（右开），自然到期视为失效，交接可进行
        fixClock(T1030);
        assertThat(read("carol", evidenceKey, copyId).getResponse().getStatus()).isEqualTo(410);
        assertThat(initiate("alice", evidenceKey, uniqueKey("CMD"), "bob")
                .getResponse().getStatus()).isEqualTo(200);

        // 旧签发水印不变：重放签发仍返回首次元数据
        MvcResult replay = issue("alice", evidenceKey, requestId, "carol", null, validTo, null);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(replay).get("watermark").asText()).isEqualTo(watermark);
        assertThat(json(replay).has("syntheticText")).isFalse();
    }

    @Test
    void sealAnomalyStopsAllCopyReadsImmediately() throws Exception {
        fixClock(T1029);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, true);
        String copyId = issueCopy("alice", evidenceKey, "carol",
                LocalDateTime.ofInstant(T1030, ZoneOffset.UTC));
        assertThat(read("carol", evidenceKey, copyId).getResponse().getStatus()).isEqualTo(200);
        // 封条异常登记后，全部副本读取立即停止
        assertThat(inspect("alice", evidenceKey, uniqueKey("CMD"), false)
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(read("carol", evidenceKey, copyId).getResponse().getStatus()).isEqualTo(410);
    }

    @Test
    void transferAndBorrowBlockedUntilCopiesRevoked() throws Exception {
        fixClock(T1029);
        LocalDateTime validTo = LocalDateTime.ofInstant(T1030, ZoneOffset.UTC);
        // 交接被有效副本拦截，回收后放行
        String ev1 = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), ev1, true);
        String copy1 = issueCopy("alice", ev1, "carol", validTo);
        assertThat(initiate("alice", ev1, uniqueKey("CMD"), "bob").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(revoke("alice", ev1, copy1, uniqueKey("REQ")).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(initiate("alice", ev1, uniqueKey("CMD"), "bob").getResponse().getStatus())
                .isEqualTo(200);
        // 借出被有效副本拦截，回收后放行
        String ev2 = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), ev2, true);
        String copy2 = issueCopy("alice", ev2, "carol", validTo);
        assertThat(borrow("alice", ev2, uniqueKey("CMD"), uniqueKey("LN"), "dave", validTo)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(revoke("alice", ev2, copy2, uniqueKey("REQ")).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(borrow("alice", ev2, uniqueKey("CMD"), uniqueKey("LN"), "dave", validTo)
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void expectedVersionGuard() throws Exception {
        fixClock(T1029);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, true);
        LocalDateTime validTo = LocalDateTime.ofInstant(T1030, ZoneOffset.UTC);
        // 期望版本不一致：409；一致（入库后版本为 0）：201
        assertThat(issue("alice", evidenceKey, uniqueKey("REQ"), "carol", null, validTo, 7L)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(issue("alice", evidenceKey, uniqueKey("REQ"), "carol", null, validTo, 0L)
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void readUnknownCopyReturns404() throws Exception {
        fixClock(T1029);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, true);
        assertThat(read("carol", evidenceKey, "CPY-nonexistent").getResponse().getStatus())
                .isEqualTo(404);
    }
}