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
 * 证物身份合并 API 测试：主流程、冻结链、别名写入拒绝、权限/版本/前置条件与幂等边界。
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

    private MvcResult intake(String actor, String evidenceKey) throws Exception {
        return intake(actor, evidenceKey, "SEAL-1");
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

    private MvcResult merge(String actor, String commandKey, String primaryKey, long primaryVersion,
                            String aliasKey, long aliasVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("primaryKey", primaryKey);
        body.put("primaryExpectedVersion", primaryVersion);
        body.put("aliasKey", aliasKey);
        body.put("aliasExpectedVersion", aliasVersion);
        return mockMvc.perform(post("/api/evidence/merges")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult inspect(String actor, String evidenceKey, boolean passed) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", passed);
        body.put("note", "routine");
        return mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evidenceKey)
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

    private MvcResult decide(String actor, String evidenceKey, String action) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        return mockMvc.perform(post("/api/evidence/{key}/transfers/{action}", evidenceKey, action)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult borrow(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", "bob");
        body.put("purpose", "audit");
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
    void mergeFreezesBothChainsAndAliasRejectsAllWrites() throws Exception {
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        intake("alice", e1);
        intake("alice", e2);
        // 合并前各留一条核验记录作为历史来源（核验通过不推进版本）
        inspect("alice", e1, true);
        inspect("alice", e2, true);

        MvcResult merged = merge("alice", uniqueKey("CMD"), e1, 1, e2, 1);
        assertThat(merged.getResponse().getStatus()).isEqualTo(200);
        JsonNode event = objectMapper.readTree(merged.getResponse().getContentAsString());
        assertThat(event.get("primaryKey").asText()).isEqualTo(e1);
        assertThat(event.get("aliasKey").asText()).isEqualTo(e2);
        assertThat(event.get("actorId").asText()).isEqualTo("alice");
        assertThat(event.get("primaryVersion").asLong()).isEqualTo(2);
        assertThat(event.get("aliasVersion").asLong()).isEqualTo(2);

        // 别名可查询并指向最终主号
        JsonNode aliasChain = chain(e2);
        assertThat(aliasChain.get("evidence").get("status").asText()).isEqualTo("ALIAS");
        assertThat(aliasChain.get("evidence").get("aliasOf").asText()).isEqualTo(e1);
        assertThat(aliasChain.get("evidence").get("version").asLong()).isEqualTo(2);
        assertThat(aliasChain.get("mergeEvents")).hasSize(1);

        // 所有写入口对别名 409，不自动重定向
        assertThat(inspect("alice", e2, true).getResponse().getStatus()).isEqualTo(409);
        assertThat(initiate("alice", e2, "bob").getResponse().getStatus()).isEqualTo(409);
        assertThat(borrow("alice", e2).getResponse().getStatus()).isEqualTo(409);

        // 主号轨迹附合并事件，可继续正常交接
        JsonNode primaryChain = chain(e1);
        assertThat(primaryChain.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(primaryChain.get("evidence").get("version").asLong()).isEqualTo(2);
        assertThat(primaryChain.get("mergeEvents")).hasSize(1);
        assertThat(primaryChain.get("mergeEvents").get(0).get("aliasKey").asText()).isEqualTo(e2);

        assertThat(initiate("alice", e1, "bob").getResponse().getStatus()).isEqualTo(200);
        assertThat(decide("bob", e1, "accept").getResponse().getStatus()).isEqualTo(200);

        // 主号交接后，副号冻结链仍显示合并前两条来源：保管人不变、历史记录不变
        JsonNode frozen = chain(e2);
        assertThat(frozen.get("evidence").get("custodianId").asText()).isEqualTo("alice");
        assertThat(frozen.get("evidence").get("status").asText()).isEqualTo("ALIAS");
        assertThat(frozen.get("inspections")).hasSize(1);
        assertThat(frozen.get("inspections").get(0).get("inspectorId").asText()).isEqualTo("alice");
        assertThat(frozen.get("transfers")).isEmpty();
        assertThat(chain(e1).get("evidence").get("custodianId").asText()).isEqualTo("bob");
    }

    @Test
    void mergeAdvancesVersionsAndStatusWritesAdvanceToo() throws Exception {
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        intake("alice", e1);
        intake("alice", e2);
        // 原状态写入推进版本：发起交接 v2，取消 v3
        assertThat(initiate("alice", e1, "bob").getResponse().getStatus()).isEqualTo(200);
        assertThat(decide("alice", e1, "cancel").getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(e1).get("evidence").get("version").asLong()).isEqualTo(3);

        assertThat(merge("alice", uniqueKey("CMD"), e1, 3, e2, 1).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(chain(e1).get("evidence").get("version").asLong()).isEqualTo(4);
        assertThat(chain(e2).get("evidence").get("version").asLong()).isEqualTo(2);
    }

    @Test
    void mergeByNonCustodianReturns403() throws Exception {
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        intake("alice", e1);
        intake("alice", e2);
        // 非保管人操作 403
        assertThat(merge("mallory", uniqueKey("CMD"), e1, 1, e2, 1).getResponse().getStatus())
                .isEqualTo(403);

        // 两证物保管人不同，任何一方都无法同时是两证物保管人
        String e3 = uniqueKey("EV");
        intake("bob", e3);
        assertThat(merge("alice", uniqueKey("CMD"), e1, 1, e3, 1).getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    void mergeWithStaleVersionReturns409() throws Exception {
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        intake("alice", e1);
        intake("alice", e2);
        assertThat(merge("alice", uniqueKey("CMD"), e1, 2, e2, 1).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(merge("alice", uniqueKey("CMD"), e1, 1, e2, 9).getResponse().getStatus())
                .isEqualTo(409);
        // 旧版本失败后两证物均未被改变
        assertThat(chain(e1).get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(chain(e2).get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(chain(e1).get("mergeEvents")).isEmpty();
    }

    @Test
    void mergeWithDifferentSealReturns422() throws Exception {
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        intake("alice", e1, "SEAL-1");
        intake("alice", e2, "SEAL-2");
        assertThat(merge("alice", uniqueKey("CMD"), e1, 1, e2, 1).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void mergeWithPendingTransferOrActiveLoanReturns422() throws Exception {
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        intake("alice", e1);
        intake("alice", e2);
        assertThat(initiate("alice", e2, "bob").getResponse().getStatus()).isEqualTo(200);
        assertThat(merge("alice", uniqueKey("CMD"), e1, 1, e2, 2).getResponse().getStatus())
                .isEqualTo(422);
        assertThat(decide("alice", e2, "cancel").getResponse().getStatus()).isEqualTo(200);

        assertThat(borrow("alice", e2).getResponse().getStatus()).isEqualTo(200);
        assertThat(merge("alice", uniqueKey("CMD"), e1, 1, e2, 4).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void mergeInvolvingAliasReturns409() throws Exception {
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        String e3 = uniqueKey("EV");
        intake("alice", e1);
        intake("alice", e2);
        intake("alice", e3);
        assertThat(merge("alice", uniqueKey("CMD"), e1, 1, e2, 1).getResponse().getStatus())
                .isEqualTo(200);

        // 不能合并已是 ALIAS 的对象（无论作为主号还是副号），避免环或多层隐式追随
        assertThat(merge("alice", uniqueKey("CMD"), e3, 1, e2, 2).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(merge("alice", uniqueKey("CMD"), e2, 2, e3, 1).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(chain(e3).get("evidence").get("status").asText()).isEqualTo("SEALED");
    }

    @Test
    void mergeSameKeyReturns400AndMissingReturns404() throws Exception {
        String e1 = uniqueKey("EV");
        intake("alice", e1);
        assertThat(merge("alice", uniqueKey("CMD"), e1, 1, e1, 1).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(merge("alice", uniqueKey("CMD"), e1, 1, uniqueKey("EV"), 1)
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void mergeIdempotentReplayReturnsFirstResult() throws Exception {
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        intake("alice", e1);
        intake("alice", e2);
        String commandKey = uniqueKey("CMD");

        MvcResult first = merge("alice", commandKey, e1, 1, e2, 1);
        MvcResult replay = merge("alice", commandKey, e1, 1, e2, 1);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 重放不产生第二条合并事件，版本不被重复推进
        assertThat(chain(e1).get("mergeEvents")).hasSize(1);
        assertThat(chain(e1).get("evidence").get("version").asLong()).isEqualTo(2);
    }

    @Test
    void mergeSameCommandKeyWithDifferentParamsReturns409() throws Exception {
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        String e3 = uniqueKey("EV");
        intake("alice", e1);
        intake("alice", e2);
        intake("alice", e3);
        String commandKey = uniqueKey("CMD");
        assertThat(merge("alice", commandKey, e1, 1, e2, 1).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(merge("alice", commandKey, e1, 2, e3, 1).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void failedMergeDoesNotConsumeCommandKey() throws Exception {
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        intake("alice", e1);
        intake("alice", e2, "SEAL-2");
        String commandKey = uniqueKey("CMD");
        // 封条不同 422，失败不占键
        assertThat(merge("alice", commandKey, e1, 1, e2, 1).getResponse().getStatus())
                .isEqualTo(422);

        String e3 = uniqueKey("EV");
        intake("alice", e3);
        assertThat(merge("alice", commandKey, e1, 1, e3, 1).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(chain(e3).get("evidence").get("status").asText()).isEqualTo("ALIAS");
    }
}
