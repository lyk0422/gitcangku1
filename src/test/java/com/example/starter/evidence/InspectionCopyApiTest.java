package com.example.starter.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 阅检副本 API 测试：签发、读取、回收主流程与失败分支，
 * 有效期边界用固定时钟驱动（10:29 可读、10:30 失效）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InspectionCopyApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    /** 测试基准时刻（UTC），对应题干 10:00。 */
    private static final Instant BASE = Instant.parse("2026-10-04T10:00:00Z");
    private static final String DIGEST = "sha256:evidence-digest";
    private static final String TEXT = "合成文本-证物";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EvidenceClock evidenceClock;

    @BeforeEach
    void fixClock() {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
    }

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private void advanceTo(Instant instant) {
        evidenceClock.setClock(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
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
        body.put("digest", DIGEST);
        body.put("syntheticText", TEXT);
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult issue(String actor, String evidenceKey, String commandKey,
                            String copyKey, String reviewer, LocalDateTime validUntil)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("copyKey", copyKey);
        body.put("reviewerId", reviewer);
        body.put("validUntil", validUntil.toString());
        return mockMvc.perform(post("/api/evidence/{key}/inspection-copies", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult read(String actor, String evidenceKey, String copyKey) throws Exception {
        return mockMvc.perform(get("/api/evidence/{key}/inspection-copies/{copy}",
                        evidenceKey, copyKey)
                        .header(ACTOR_HEADER, actor))
                .andReturn();
    }

    private MvcResult revoke(String actor, String evidenceKey, String copyKey,
                             String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return mockMvc.perform(post("/api/evidence/{key}/inspection-copies/{copy}/revoke",
                        evidenceKey, copyKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }
    private MvcResult initiate(String actor, String evidenceKey, String toCustodian)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", toCustodian);
        return mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult inspect(String actor, String evidenceKey, boolean passed) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", passed);
        return mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult borrow(String actor, String evidenceKey, String borrower,
                             LocalDateTime dueAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", borrower);
        body.put("purpose", "鉴定用");
        body.put("dueAt", dueAt.toString());
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode chain(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void intakeFreezesDigestAndHidesSyntheticTextFromViews() throws Exception {
        String evidenceKey = uniqueKey("EV");
        MvcResult result = intake("alice", evidenceKey);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("digest").asText()).isEqualTo(DIGEST);
        assertThat(body.has("syntheticText")).isFalse();
        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("evidence").get("digest").asText()).isEqualTo(DIGEST);
        assertThat(chain.get("evidence").has("syntheticText")).isFalse();
    }

    @Test
    void issueReturnsFrozenMetadataWithoutTextAndKeepsCustody() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String copyKey = uniqueKey("COPY");
        intake("alice", evidenceKey);

        MvcResult issued = issue("alice", evidenceKey, uniqueKey("CMD"), copyKey, "reviewer1",
                utc(BASE.plusSeconds(1800)));
        assertThat(issued.getResponse().getStatus()).isEqualTo(201);
        JsonNode meta = objectMapper.readTree(issued.getResponse().getContentAsString());
        assertThat(meta.get("copyKey").asText()).isEqualTo(copyKey);
        assertThat(meta.get("evidenceKey").asText()).isEqualTo(evidenceKey);
        assertThat(meta.get("reviewerId").asText()).isEqualTo("reviewer1");
        assertThat(meta.get("digest").asText()).isEqualTo(DIGEST);
        assertThat(meta.get("watermark").asText()).contains(copyKey).contains("reviewer1");
        assertThat(meta.get("validFrom").asText()).isEqualTo("2026-10-04T10:00:00");
        assertThat(meta.get("validUntil").asText()).isEqualTo("2026-10-04T10:30:00");
        assertThat(meta.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(meta.has("syntheticText")).isFalse();

        // 副本授权不改变原件保管权与状态
        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("evidence").get("custodianId").asText()).isEqualTo("alice");
        assertThat(chain.get("evidence").get("status").asText()).isEqualTo("SEALED");
    }

    @Test
    void readWithinValidityReturnsSyntheticContent() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String copyKey = uniqueKey("COPY");
        intake("alice", evidenceKey);
        issue("alice", evidenceKey, uniqueKey("CMD"), copyKey, "reviewer1",
                utc(BASE.plusSeconds(1800)));

        MvcResult result = read("reviewer1", evidenceKey, copyKey);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(content.get("copyKey").asText()).isEqualTo(copyKey);
        assertThat(content.get("syntheticText").asText()).isEqualTo(TEXT);
        assertThat(content.get("digest").asText()).isEqualTo(DIGEST);
        assertThat(content.get("watermark").asText()).contains(copyKey).contains("reviewer1");
        // 不返回原件内部记录
        assertThat(content.has("custodianId")).isFalse();
        assertThat(content.has("status")).isFalse();
    }
    @Test
    void issueByNonCustodianReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        assertThat(issue("mallory", evidenceKey, uniqueKey("CMD"), uniqueKey("COPY"),
                "reviewer1", utc(BASE.plusSeconds(1800))).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void issueRequiresSealedEvidenceWithoutLoanOrPendingTransfer() throws Exception {
        String pending = uniqueKey("EV");
        intake("alice", pending);
        initiate("alice", pending, "bob");
        assertThat(issue("alice", pending, uniqueKey("CMD"), uniqueKey("COPY"),
                "reviewer1", utc(BASE.plusSeconds(1800))).getResponse().getStatus())
                .isEqualTo(422);

        String borrowed = uniqueKey("EV");
        intake("alice", borrowed);
        assertThat(borrow("alice", borrowed, "carol", utc(BASE.plusSeconds(7200)))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(issue("alice", borrowed, uniqueKey("CMD"), uniqueKey("COPY"),
                "reviewer1", utc(BASE.plusSeconds(1800))).getResponse().getStatus())
                .isEqualTo(422);

        String broken = uniqueKey("EV");
        intake("alice", broken);
        assertThat(inspect("alice", broken, false).getResponse().getStatus()).isEqualTo(200);
        assertThat(issue("alice", broken, uniqueKey("CMD"), uniqueKey("COPY"),
                "reviewer1", utc(BASE.plusSeconds(1800))).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void issueRejectsInvalidValidityWindow() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        // 失效时刻不晚于当前时刻
        assertThat(issue("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("COPY"),
                "reviewer1", utc(BASE)).getResponse().getStatus()).isEqualTo(400);
        // 超过最长一小时
        assertThat(issue("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("COPY"),
                "reviewer1", utc(BASE.plusSeconds(3601))).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void failedIssueDoesNotOccupyCommandKey() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String copyKey = uniqueKey("COPY");
        String commandKey = uniqueKey("CMD");
        intake("alice", evidenceKey);
        // 参数非法失败，不占幂等键
        assertThat(issue("alice", evidenceKey, commandKey, copyKey, "reviewer1",
                utc(BASE.plusSeconds(7200))).getResponse().getStatus()).isEqualTo(400);
        MvcResult ok = issue("alice", evidenceKey, commandKey, copyKey, "reviewer1",
                utc(BASE.plusSeconds(1800)));
        assertThat(ok.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void issueReplayReturnsSameMetadataWithoutText() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String copyKey = uniqueKey("COPY");
        String commandKey = uniqueKey("CMD");
        intake("alice", evidenceKey);

        MvcResult first = issue("alice", evidenceKey, commandKey, copyKey, "reviewer1",
                utc(BASE.plusSeconds(1800)));
        MvcResult replay = issue("alice", evidenceKey, commandKey, copyKey, "reviewer1",
                utc(BASE.plusSeconds(1800)));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(replay.getResponse().getContentAsString()).doesNotContain(TEXT);

        // 同键改参返回 409
        assertThat(issue("alice", evidenceKey, commandKey, copyKey, "reviewer2",
                utc(BASE.plusSeconds(1800))).getResponse().getStatus()).isEqualTo(409);
    }
    @Test
    void readAfterRevokeReturns410AndKeepsHistory() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String copyKey = uniqueKey("COPY");
        intake("alice", evidenceKey);
        issue("alice", evidenceKey, uniqueKey("CMD"), copyKey, "reviewer1",
                utc(BASE.plusSeconds(1800)));

        MvcResult revoked = revoke("alice", evidenceKey, copyKey, uniqueKey("CMD"));
        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        JsonNode meta = objectMapper.readTree(revoked.getResponse().getContentAsString());
        assertThat(meta.get("status").asText()).isEqualTo("REVOKED");
        assertThat(meta.get("revokedAt").isNull()).isFalse();
        assertThat(meta.has("syntheticText")).isFalse();

        // 回收后读取返回 410，签发历史仍保留（不是 404）
        assertThat(read("reviewer1", evidenceKey, copyKey).getResponse().getStatus())
                .isEqualTo(410);
    }

    @Test
    void readAfterNaturalExpiryReturns410() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String copyKey = uniqueKey("COPY");
        intake("alice", evidenceKey);
        issue("alice", evidenceKey, uniqueKey("CMD"), copyKey, "reviewer1",
                utc(BASE.plusSeconds(1800)));

        advanceTo(BASE.plusSeconds(1800));
        assertThat(read("reviewer1", evidenceKey, copyKey).getResponse().getStatus())
                .isEqualTo(410);
    }

    @Test
    void sealAnomalyStopsAllCopyReadsImmediately() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String copyKey = uniqueKey("COPY");
        intake("alice", evidenceKey);
        issue("alice", evidenceKey, uniqueKey("CMD"), copyKey, "reviewer1",
                utc(BASE.plusSeconds(1800)));
        assertThat(read("reviewer1", evidenceKey, copyKey).getResponse().getStatus())
                .isEqualTo(200);

        assertThat(inspect("alice", evidenceKey, false).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(read("reviewer1", evidenceKey, copyKey).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void revokeByNonCustodianReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String copyKey = uniqueKey("COPY");
        intake("alice", evidenceKey);
        issue("alice", evidenceKey, uniqueKey("CMD"), copyKey, "reviewer1",
                utc(BASE.plusSeconds(1800)));
        assertThat(revoke("mallory", evidenceKey, copyKey, uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void revokeTwiceReturns409AndReplayReturnsFirstResult() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String copyKey = uniqueKey("COPY");
        String commandKey = uniqueKey("CMD");
        intake("alice", evidenceKey);
        issue("alice", evidenceKey, uniqueKey("CMD"), copyKey, "reviewer1",
                utc(BASE.plusSeconds(1800)));

        MvcResult first = revoke("alice", evidenceKey, copyKey, commandKey);
        MvcResult replay = revoke("alice", evidenceKey, copyKey, commandKey);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 新命令键再次回收返回 409
        assertThat(revoke("alice", evidenceKey, copyKey, uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void readMissingCopyReturns404() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        assertThat(read("reviewer1", evidenceKey, uniqueKey("COPY"))
                .getResponse().getStatus()).isEqualTo(404);
    }
    @Test
    void transferAndBorrowBlockedUntilCopiesRevoked() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String copyKey = uniqueKey("COPY");
        intake("alice", evidenceKey);
        issue("alice", evidenceKey, uniqueKey("CMD"), copyKey, "reviewer1",
                utc(BASE.plusSeconds(1800)));

        // 有效副本未回收：交接与借出均 409
        assertThat(initiate("alice", evidenceKey, "bob").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(borrow("alice", evidenceKey, "carol", utc(BASE.plusSeconds(7200)))
                .getResponse().getStatus()).isEqualTo(409);

        // 回收后交接与借出恢复可用
        assertThat(revoke("alice", evidenceKey, copyKey, uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(initiate("alice", evidenceKey, "bob").getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void borrowAllowedAfterRevoke() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String copyKey = uniqueKey("COPY");
        intake("alice", evidenceKey);
        issue("alice", evidenceKey, uniqueKey("CMD"), copyKey, "reviewer1",
                utc(BASE.plusSeconds(1800)));
        assertThat(revoke("alice", evidenceKey, copyKey, uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(borrow("alice", evidenceKey, "carol", utc(BASE.plusSeconds(7200)))
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void timeBoundary1029Readable1030GoneAndTransferAllowed() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String copyKey = uniqueKey("COPY");
        String commandKey = uniqueKey("CMD");
        intake("alice", evidenceKey);
        MvcResult issued = issue("alice", evidenceKey, commandKey, copyKey, "reviewer1",
                utc(BASE.plusSeconds(1800)));
        assertThat(issued.getResponse().getStatus()).isEqualTo(201);
        String watermark = objectMapper.readTree(issued.getResponse().getContentAsString())
                .get("watermark").asText();

        // 10:29 可读，但原件不能交接
        advanceTo(BASE.plusSeconds(1740));
        assertThat(read("reviewer1", evidenceKey, copyKey).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(initiate("alice", evidenceKey, "bob").getResponse().getStatus())
                .isEqualTo(409);

        // 10:30 读取 410，自然到期视为失效，交接可进行
        advanceTo(BASE.plusSeconds(1800));
        assertThat(read("reviewer1", evidenceKey, copyKey).getResponse().getStatus())
                .isEqualTo(410);
        assertThat(initiate("alice", evidenceKey, "bob").getResponse().getStatus())
                .isEqualTo(200);

        // 旧签发水印不变：重放签发仍返回首次元数据
        MvcResult replay = issue("alice", evidenceKey, commandKey, copyKey, "reviewer1",
                utc(BASE.plusSeconds(1800)));
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(objectMapper.readTree(replay.getResponse().getContentAsString())
                .get("watermark").asText()).isEqualTo(watermark);
    }
}