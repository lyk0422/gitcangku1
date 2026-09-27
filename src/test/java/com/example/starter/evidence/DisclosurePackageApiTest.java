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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 披露包 API 主流程与失败分支测试（真实 H2 + MockMvc）：
 * 建包、批量增删原子性、交付/撤销终态、修订包、幂等重放、失败不占键与各类查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DisclosurePackageApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";
    // 取测试运行时真实时刻：入库 createdAt 为 JVM 墙钟（重放按 Asia/Shanghai 解释）。
    // asOf 在固定时钟基准上加 9 小时余量，保证无论运行机时区如何都晚于入库时刻；
    // 借出未归还/已归还按 loanAt/returnedAt(UTC) 与 asOf 比较，不受余量影响。
    private static final Instant BASE = Instant.now();
    private static final String AS_OF =
            LocalDateTime.ofInstant(BASE.plusSeconds(600).plusSeconds(9 * 3600), ZoneOffset.UTC)
                    .toString();
    private static final String OTHER_AS_OF =
            LocalDateTime.ofInstant(BASE.plusSeconds(720).plusSeconds(9 * 3600), ZoneOffset.UTC)
                    .toString();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EvidenceClock evidenceClock;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private MvcResult intake(String actor, String caseKey, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", caseKey);
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-" + evidenceKey);
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult borrow(String actor, String evidenceKey, String borrower, String loanKey)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", loanKey);
        body.put("borrowerId", borrower);
        body.put("purpose", "鉴定用");
        body.put("dueAt", LocalDateTime.ofInstant(BASE.plusSeconds(3600), ZoneOffset.UTC).toString());
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult returnLoan(String actor, String evidenceKey, String loanKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", loanKey);
        body.put("sealIntact", true);
        body.put("note", "鉴定完毕封签完好");
        return mockMvc.perform(post("/api/evidence/{key}/loans/return", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult createPackage(String actor, String caseKey, List<String> evidenceKeys,
                                    String asOf, String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("evidenceKeys", evidenceKeys);
        body.put("asOfUtc", asOf);
        return mockMvc.perform(post("/api/cases/{caseKey}/disclosure-packages", caseKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult updateItems(String actor, String caseKey, List<String> evidenceKeys,
                                  String asOf, String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("evidenceKeys", evidenceKeys);
        body.put("asOfUtc", asOf);
        return mockMvc.perform(post("/api/cases/{caseKey}/disclosure-packages/versions/latest/items",
                        caseKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult decide(String actor, String caseKey, String action, String commandKey,
                             String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        if (reason != null) {
            body.put("reason", reason);
        }
        return mockMvc.perform(post(
                        "/api/cases/{caseKey}/disclosure-packages/versions/latest/{action}",
                        caseKey, action)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult getVersion(String caseKey, int version) throws Exception {
        return mockMvc.perform(get(
                "/api/cases/{caseKey}/disclosure-packages/versions/{version}", caseKey, version))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void createPackageFreezesSortedItemsAndDelivers() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        assertThat(intake("alice", caseKey, e1).getResponse().getStatus()).isEqualTo(201);
        assertThat(intake("alice", caseKey, e2).getResponse().getStatus()).isEqualTo(201);

        // 请求故意乱序，固化后按字典序规范化。
        MvcResult created = createPackage("officer", caseKey, List.of(e2, e1), AS_OF, uniqueKey("CMD"));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode pkg = json(created);
        assertThat(pkg.get("packageVersion").asInt()).isEqualTo(1);
        assertThat(pkg.get("status").asText()).isEqualTo("GENERATED");
        assertThat(pkg.get("packageKey").asText()).hasSize(64);
        assertThat(pkg.get("items")).hasSize(2);
        assertThat(pkg.get("items").get(0).get("evidenceKey").asText()).isEqualTo(e1);
        assertThat(pkg.get("items").get(0).get("position").asInt()).isZero();
        assertThat(pkg.get("items").get(0).get("custodianAtAsOf").asText()).isEqualTo("alice");
        assertThat(pkg.get("items").get(0).get("diagnosis").asText()).isEqualTo("OK");
        assertThat(pkg.get("frozenSummary").get("items")).hasSize(2);

        // 逐件链诊断查询。
        MvcResult item = mockMvc.perform(get(
                "/api/cases/{caseKey}/disclosure-packages/versions/1/items/{evidenceKey}",
                caseKey, e2)).andReturn();
        assertThat(item.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(item).get("chainVersion").asInt()).isZero();

        // 交付。
        MvcResult delivered = decide("officer", caseKey, "deliver", uniqueKey("CMD"), "当庭交付");
        assertThat(delivered.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(delivered).get("status").asText()).isEqualTo("DELIVERED");
        assertThat(json(delivered).get("deliveredBy").asText()).isEqualTo("officer");
        assertThat(json(delivered).get("deliverReason").asText()).isEqualTo("当庭交付");
    }

    @Test
    void activeLoanAtAsOfRejectsWholePackageWithDistinct422() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        intake("alice", caseKey, e1);
        intake("alice", caseKey, e2);
        assertThat(borrow("alice", e1, "bob", uniqueKey("LOAN")).getResponse().getStatus()).isEqualTo(200);

        MvcResult result = createPackage("officer", caseKey, List.of(e1, e2), AS_OF, uniqueKey("CMD"));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        JsonNode body = json(result);
        assertThat(body.get("diagnoses")).hasSize(1);
        assertThat(body.get("diagnoses").get(0).get("evidenceKey").asText()).isEqualTo(e1);
        assertThat(body.get("diagnoses").get(0).get("code").asText())
                .isEqualTo("ACTIVE_LOAN_AT_AS_OF");

        // 整包不生成：版本列表为空。
        MvcResult list = mockMvc.perform(get(
                "/api/cases/{caseKey}/disclosure-packages", caseKey)).andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(list)).isEmpty();
    }

    @Test
    void failedCommandKeyCanBeReusedAfterFix() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        String e1 = uniqueKey("EV");
        intake("alice", caseKey, e1);
        String loanKey = uniqueKey("LOAN");
        borrow("alice", e1, "bob", loanKey);
        String commandKey = uniqueKey("CMD");

        // 借出未归还时建包失败 422，失败不写命令日志（不占键）。
        assertThat(createPackage("officer", caseKey, List.of(e1), AS_OF, commandKey)
                .getResponse().getStatus()).isEqualTo(422);

        // 归还使证物恢复可披露状态后，同一命令键、同一参数重试成功（键未被失败占用）。
        assertThat(returnLoan("alice", e1, loanKey).getResponse().getStatus()).isEqualTo(200);
        MvcResult retried = createPackage("officer", caseKey, List.of(e1), AS_OF, commandKey);
        assertThat(retried.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(retried).get("packageVersion").asInt()).isEqualTo(1);

        // 成功后同键同参重放，返回首次结果，不生成版本 2。
        MvcResult replay = createPackage("officer", caseKey, List.of(e1), AS_OF, commandKey);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(retried.getResponse().getContentAsString());
    }

    @Test
    void updateItemsValidatesFinalSetAtomicallyAndKeepsOldVersion() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        String e3 = uniqueKey("EV");
        intake("alice", caseKey, e1);
        intake("alice", caseKey, e2);
        intake("bob", uniqueKey("CASE-OTHER"), e3);
        assertThat(createPackage("officer", caseKey, List.of(e1, e2), AS_OF, uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(201);

        // 最终集合含跨案件证物：422，已有版本 1 不变，不产生版本 2。
        MvcResult failed = updateItems("officer", caseKey, List.of(e1, e2, e3), AS_OF, uniqueKey("CMD"));
        assertThat(failed.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(failed).get("diagnoses").get(0).get("code").asText()).isEqualTo("NOT_IN_CASE");
        MvcResult latest = mockMvc.perform(get(
                "/api/cases/{caseKey}/disclosure-packages/versions/latest", caseKey)).andReturn();
        assertThat(json(latest).get("packageVersion").asInt()).isEqualTo(1);
        assertThat(json(latest).get("items")).hasSize(2);

        // 合法增删：只保留 e2，生成版本 2。
        MvcResult v2 = updateItems("officer", caseKey, List.of(e2), AS_OF, uniqueKey("CMD"));
        assertThat(v2.getResponse().getStatus()).isEqualTo(200);
        JsonNode v2Body = json(v2);
        assertThat(v2Body.get("packageVersion").asInt()).isEqualTo(2);
        assertThat(v2Body.get("items")).hasSize(1);
        // 版本 1 仍可查且内容不变。
        MvcResult v1 = getVersion(caseKey, 1);
        assertThat(v1.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(v1).get("items")).hasSize(2);
    }

    @Test
    void deliveredPackageCannotRevokeButRevisionCan() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        intake("alice", caseKey, e1);
        intake("alice", caseKey, e2);
        createPackage("officer", caseKey, List.of(e1), AS_OF, uniqueKey("CMD"));
        assertThat(decide("officer", caseKey, "deliver", uniqueKey("CMD"), null)
                .getResponse().getStatus()).isEqualTo(200);

        // 已交付不可撤销。
        MvcResult revokeDelivered = decide("officer", caseKey, "revoke", uniqueKey("CMD"), "发现问题");
        assertThat(revokeDelivered.getResponse().getStatus()).isEqualTo(409);

        // 已交付后仍可建更高修订包（增删证物），新版本为未交付。
        MvcResult v2 = updateItems("officer", caseKey, List.of(e1, e2), AS_OF, uniqueKey("CMD"));
        assertThat(v2.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(v2).get("packageVersion").asInt()).isEqualTo(2);
        assertThat(json(v2).get("status").asText()).isEqualTo("GENERATED");

        // 新版本可撤销且原因留痕；再次撤销 409。
        MvcResult revoked = decide("officer", caseKey, "revoke", uniqueKey("CMD"), "申请方撤回");
        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(revoked).get("status").asText()).isEqualTo("REVOKED");
        assertThat(json(revoked).get("revokeReason").asText()).isEqualTo("申请方撤回");
        assertThat(decide("officer", caseKey, "revoke", uniqueKey("CMD"), "再次撤销")
                .getResponse().getStatus()).isEqualTo(409);

        // 撤销记录查询；版本 1 的撤销记录 404。
        MvcResult revocation = mockMvc.perform(get(
                "/api/cases/{caseKey}/disclosure-packages/versions/2/revocation", caseKey))
                .andReturn();
        assertThat(revocation.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(revocation).get("revokedBy").asText()).isEqualTo("officer");
        MvcResult noRevocation = mockMvc.perform(get(
                "/api/cases/{caseKey}/disclosure-packages/versions/1/revocation", caseKey))
                .andReturn();
        assertThat(noRevocation.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void createIsIdempotentOnSameCommandKey() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        String e1 = uniqueKey("EV");
        intake("alice", caseKey, e1);
        String commandKey = uniqueKey("CMD");

        MvcResult first = createPackage("officer", caseKey, List.of(e1), AS_OF, commandKey);
        MvcResult replay = createPackage("officer", caseKey, List.of(e1), AS_OF, commandKey);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 同键改参（截至时刻变化）409。
        assertThat(createPackage("officer", caseKey, List.of(e1), OTHER_AS_OF, commandKey)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void updateOnCaseWithoutPackageReturns404AndValidationReturns400() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        MvcResult noPackage = updateItems("officer", caseKey, List.of("EV-X"), AS_OF, uniqueKey("CMD"));
        assertThat(noPackage.getResponse().getStatus()).isEqualTo(404);

        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("commandKey", uniqueKey("CMD"));
        bad.put("evidenceKeys", List.of());
        bad.put("asOfUtc", AS_OF);
        MvcResult invalid = mockMvc.perform(post("/api/cases/{caseKey}/disclosure-packages", caseKey)
                        .header(ACTOR_HEADER, "officer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andReturn();
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void itemNotInVersionReturns404() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        intake("alice", caseKey, e1);
        intake("alice", caseKey, e2);
        createPackage("officer", caseKey, List.of(e1), AS_OF, uniqueKey("CMD"));

        MvcResult result = mockMvc.perform(get(
                "/api/cases/{caseKey}/disclosure-packages/versions/1/items/{evidenceKey}",
                caseKey, e2)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }
}
