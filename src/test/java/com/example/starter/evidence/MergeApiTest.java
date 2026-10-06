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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 证物身份合并 API 测试：主流程、权限/前置/版本拒绝、幂等重放与别名写入口边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MergeApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private MvcResult intake(String actor, String evidenceKey, String sealNo) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", sealNo);
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult merge(String actor, String requestId, String primaryKey, String aliasKey,
                            long expectedPrimaryVersion, long expectedAliasVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("primaryKey", primaryKey);
        body.put("aliasKey", aliasKey);
        body.put("expectedPrimaryVersion", expectedPrimaryVersion);
        body.put("expectedAliasVersion", expectedAliasVersion);
        return mockMvc.perform(post("/api/evidence/merges")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult initiateTransfer(String actor, String evidenceKey, String toCustodian)
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

    private MvcResult decideTransfer(String actor, String evidenceKey, String action) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        return mockMvc.perform(post("/api/evidence/{key}/transfers/{action}", evidenceKey, action)
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

    private MvcResult borrow(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", "borrower-1");
        body.put("purpose", "court");
        body.put("dueAt", LocalDateTime.now(ZoneOffset.UTC).plusHours(1).toString());
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
    void mergeFreezesBothChainsAndTurnsAliasTerminal() throws Exception {
        String primary = uniqueKey("EV");
        String alias = uniqueKey("EV");
        intake("alice", primary, "SEAL-9");
        intake("alice", alias, "SEAL-9");
        // 主号先发起并取消一次交接，验证版本随原状态写入推进到 3
        assertThat(inspect("alice", primary, true).getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(primary).get("evidence").get("version").asLong()).isEqualTo(1);
        assertThat(initiateTransfer("alice", primary, "bob").getResponse().getStatus()).isEqualTo(200);
        assertThat(decideTransfer("alice", primary, "cancel").getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(primary).get("evidence").get("version").asLong()).isEqualTo(3);

        MvcResult merged = merge("alice", uniqueKey("REQ"), primary, alias, 3, 1);
        assertThat(merged.getResponse().getStatus()).isEqualTo(200);
        JsonNode event = objectMapper.readTree(merged.getResponse().getContentAsString());
        assertThat(event.get("primaryKey").asText()).isEqualTo(primary);
        assertThat(event.get("aliasKey").asText()).isEqualTo(alias);
        assertThat(event.get("custodianId").asText()).isEqualTo("alice");
        assertThat(event.get("primaryVersion").asLong()).isEqualTo(3);
        assertThat(event.get("aliasVersion").asLong()).isEqualTo(1);
        assertThat(event.get("primaryChain").asText()).contains(primary);
        assertThat(event.get("aliasChain").asText()).contains(alias);

        // 合并推进两证物版本：主号 3→4，副号 1→2 且转 ALIAS
        JsonNode primaryChain = chain(primary);
        assertThat(primaryChain.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(primaryChain.get("evidence").get("version").asLong()).isEqualTo(4);
        assertThat(primaryChain.get("merges")).hasSize(1);
        JsonNode aliasChain = chain(alias);
        assertThat(aliasChain.get("evidence").get("status").asText()).isEqualTo("ALIAS");
        assertThat(aliasChain.get("evidence").get("version").asLong()).isEqualTo(2);
        // 别名可查询并指向最终主号
        assertThat(aliasChain.get("merges")).hasSize(1);
        assertThat(aliasChain.get("merges").get(0).get("primaryKey").asText()).isEqualTo(primary);
    }

    @Test
    void allWriteEndpointsRejectAliasWith409() throws Exception {
        String primary = uniqueKey("EV");
        String alias = uniqueKey("EV");
        intake("alice", primary, "SEAL-9");
        intake("alice", alias, "SEAL-9");
        assertThat(merge("alice", uniqueKey("REQ"), primary, alias, 1, 1)
                .getResponse().getStatus()).isEqualTo(200);

        assertThat(initiateTransfer("alice", alias, "bob").getResponse().getStatus()).isEqualTo(409);
        assertThat(inspect("alice", alias, true).getResponse().getStatus()).isEqualTo(409);
        assertThat(borrow("alice", alias).getResponse().getStatus()).isEqualTo(409);
        // 别名仍是终态，未发生任何隐式重定向写入
        JsonNode aliasChain = chain(alias);
        assertThat(aliasChain.get("evidence").get("status").asText()).isEqualTo("ALIAS");
        assertThat(aliasChain.get("transfers")).isEmpty();
        assertThat(aliasChain.get("loans")).isEmpty();
    }

    @Test
    void frozenChainSurvivesLaterPrimaryTransfer() throws Exception {
        String primary = uniqueKey("EV");
        String alias = uniqueKey("EV");
        intake("alice", primary, "SEAL-9");
        intake("alice", alias, "SEAL-9");
        assertThat(merge("alice", uniqueKey("REQ"), primary, alias, 1, 1)
                .getResponse().getStatus()).isEqualTo(200);

        // 主号交接给 bob：副号冻结链仍显示合并前保管人 alice，不被改写
        assertThat(initiateTransfer("alice", primary, "bob").getResponse().getStatus()).isEqualTo(200);
        assertThat(decideTransfer("bob", primary, "accept").getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(primary).get("evidence").get("custodianId").asText()).isEqualTo("bob");

        JsonNode aliasChain = chain(alias);
        assertThat(aliasChain.get("evidence").get("custodianId").asText()).isEqualTo("alice");
        JsonNode event = aliasChain.get("merges").get(0);
        assertThat(event.get("custodianId").asText()).isEqualTo("alice");
        assertThat(event.get("aliasChain").asText()).contains("\"custodianId\":\"alice\"");
        assertThat(event.get("primaryChain").asText()).contains(primary);
        assertThat(event.get("aliasChain").asText()).contains(alias);
    }

    @Test
    void differentCustodiansOrNonCustodianActorReturns403() throws Exception {
        String primary = uniqueKey("EV");
        String alias = uniqueKey("EV");
        intake("alice", primary, "SEAL-9");
        intake("carol", alias, "SEAL-9");
        // 两证物保管人不同 → 403
        assertThat(merge("alice", uniqueKey("REQ"), primary, alias, 1, 1)
                .getResponse().getStatus()).isEqualTo(403);

        String other = uniqueKey("EV");
        intake("alice", other, "SEAL-9");
        // 操作人不是当前保管人 → 403
        assertThat(merge("mallory", uniqueKey("REQ"), primary, other, 1, 1)
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void unmetPreconditionsReturn422() throws Exception {
        // 封条不同 → 422
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, "SEAL-1");
        intake("alice", ev2, "SEAL-2");
        assertThat(merge("alice", uniqueKey("REQ"), ev1, ev2, 1, 1)
                .getResponse().getStatus()).isEqualTo(422);

        // 存在待接收交接 → 422（ev3 经发起交接版本推进到 2）
        String ev3 = uniqueKey("EV");
        intake("alice", ev3, "SEAL-2");
        assertThat(initiateTransfer("alice", ev3, "bob").getResponse().getStatus()).isEqualTo(200);
        assertThat(merge("alice", uniqueKey("REQ"), ev2, ev3, 1, 2)
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(decideTransfer("alice", ev3, "cancel").getResponse().getStatus()).isEqualTo(200);

        // 存在未归还借出 → 422（取消与借出各推进一次版本，当前为 4）
        assertThat(borrow("alice", ev3).getResponse().getStatus()).isEqualTo(200);
        assertThat(merge("alice", uniqueKey("REQ"), ev2, ev3, 1, 4)
                .getResponse().getStatus()).isEqualTo(422);

        // 封条异常（非 SEALED）→ 422
        String ev4 = uniqueKey("EV");
        String ev5 = uniqueKey("EV");
        intake("alice", ev4, "SEAL-3");
        intake("alice", ev5, "SEAL-3");
        assertThat(inspect("alice", ev5, false).getResponse().getStatus()).isEqualTo(200);
        assertThat(merge("alice", uniqueKey("REQ"), ev4, ev5, 1, 2)
                .getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void staleExpectedVersionReturns409AndFailureDoesNotBurnRequestId() throws Exception {
        String primary = uniqueKey("EV");
        String alias = uniqueKey("EV");
        intake("alice", primary, "SEAL-9");
        intake("alice", alias, "SEAL-9");
        String requestId = uniqueKey("REQ");
        // 期望版本过期 → 409，且两证物均不被改变
        assertThat(merge("alice", requestId, primary, alias, 5, 1)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(chain(primary).get("evidence").get("version").asLong()).isEqualTo(1);
        assertThat(chain(alias).get("evidence").get("version").asLong()).isEqualTo(1);
        assertThat(chain(alias).get("merges")).isEmpty();

        // 失败不占键：同一 requestId 修正参数后可成功
        assertThat(merge("alice", requestId, primary, alias, 1, 1)
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(alias).get("evidence").get("status").asText()).isEqualTo("ALIAS");
    }

    @Test
    void replayReturnsFirstResponseAndDifferentParamsReturn409() throws Exception {
        String primary = uniqueKey("EV");
        String alias = uniqueKey("EV");
        intake("alice", primary, "SEAL-9");
        intake("alice", alias, "SEAL-9");
        String requestId = uniqueKey("REQ");

        MvcResult first = merge("alice", requestId, primary, alias, 1, 1);
        MvcResult replay = merge("alice", requestId, primary, alias, 1, 1);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 重放不重复推进版本、不重复追加合并事件
        assertThat(chain(primary).get("evidence").get("version").asLong()).isEqualTo(2);
        assertThat(chain(primary).get("merges")).hasSize(1);

        // 同键异参 → 409
        String other = uniqueKey("EV");
        intake("alice", other, "SEAL-9");
        assertThat(merge("alice", requestId, primary, other, 2, 1)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void cannotMergeAliasOrCreateMultiLevelMerge() throws Exception {
        String primary = uniqueKey("EV");
        String alias = uniqueKey("EV");
        String third = uniqueKey("EV");
        intake("alice", primary, "SEAL-9");
        intake("alice", alias, "SEAL-9");
        intake("alice", third, "SEAL-9");
        assertThat(merge("alice", uniqueKey("REQ"), primary, alias, 1, 1)
                .getResponse().getStatus()).isEqualTo(200);

        // 已是 ALIAS 的对象不能再参与合并（写入口对别名 409）
        assertThat(merge("alice", uniqueKey("REQ"), third, alias, 1, 2)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(merge("alice", uniqueKey("REQ"), alias, third, 2, 1)
                .getResponse().getStatus()).isEqualTo(409);
        // 已是主号的证物不能再被并为他号副号，避免多层隐式追随 → 422
        assertThat(merge("alice", uniqueKey("REQ"), third, primary, 1, 2)
                .getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void mergeWithSameKeyReturns400AndMissingEvidenceReturns404() throws Exception {
        String evidence = uniqueKey("EV");
        intake("alice", evidence, "SEAL-9");
        assertThat(merge("alice", uniqueKey("REQ"), evidence, evidence, 1, 1)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(merge("alice", uniqueKey("REQ"), evidence, uniqueKey("EV"), 1, 1)
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void currentPermissionPrecedesSuccessfulReplay() throws Exception {
        String primary = uniqueKey("EV");
        String alias = uniqueKey("EV");
        intake("alice", primary, "SEAL-9");
        intake("alice", alias, "SEAL-9");
        String requestId = uniqueKey("REQ");
        assertThat(merge("alice", requestId, primary, alias, 1, 1)
                .getResponse().getStatus()).isEqualTo(200);

        // 主号交接给 bob 后，alice 不再是当前保管人：同键同参重放返回 403 而非首次成功响应
        assertThat(initiateTransfer("alice", primary, "bob").getResponse().getStatus()).isEqualTo(200);
        assertThat(decideTransfer("bob", primary, "accept").getResponse().getStatus()).isEqualTo(200);
        assertThat(merge("alice", requestId, primary, alias, 1, 1)
                .getResponse().getStatus()).isEqualTo(403);
    }
}
