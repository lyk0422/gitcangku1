package com.example.starter.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 证物封存交接 API 主流程、失败分支与幂等边界的端到端测试（H2 内存库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvidenceApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM seal_check");
        jdbc.update("DELETE FROM evidence_transfer");
        jdbc.update("DELETE FROM evidence");
    }

    // ---------- 入库 ----------

    @Test
    void intakeCreatesSealedEvidence() throws Exception {
        intake("cmd-i-1", "EV-1", "CASE-1", "DOC", "SEAL-1", "alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.evidenceKey").value("EV-1"))
                .andExpect(jsonPath("$.custodianId").value("alice"))
                .andExpect(jsonPath("$.status").value("SEALED"));
    }

    @Test
    void intakeRejectsInvalidParams() throws Exception {
        Map<String, Object> body = intakeBody("cmd-i-2", "EV-2", "CASE-2", "DOC", "SEAL-2", "alice");
        body.remove("caseKey");
        postJson("/api/evidence", "alice", body).andExpect(status().isBadRequest());
    }

    @Test
    void intakeRequiresActorHeader() throws Exception {
        mockMvc.perform(post("/api/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(intakeBody("cmd-i-3", "EV-3", "CASE-3", "DOC", "SEAL-3", "alice"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void intakeRejectsDuplicateEvidenceKey() throws Exception {
        intake("cmd-i-4a", "EV-4", "CASE-4", "DOC", "SEAL-4", "alice").andExpect(status().isCreated());
        intake("cmd-i-4b", "EV-4", "CASE-4", "DOC", "SEAL-4", "alice")
                .andExpect(status().isConflict());
    }

    @Test
    void intakeIdempotentReplayAndChangedParams() throws Exception {
        Map<String, Object> body = intakeBody("cmd-i-5", "EV-5", "CASE-5", "DOC", "SEAL-5", "alice");
        postJson("/api/evidence", "alice", body).andExpect(status().isCreated());
        // 同键同参重放：返回首次结果
        postJson("/api/evidence", "alice", body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.evidenceKey").value("EV-5"));
        // 同键改参：409
        Map<String, Object> changed = intakeBody("cmd-i-5", "EV-5", "CASE-OTHER", "DOC", "SEAL-5", "alice");
        postJson("/api/evidence", "alice", changed).andExpect(status().isConflict());
        // 仅入库一次
        jdbc.queryForObject("SELECT COUNT(*) FROM evidence WHERE evidence_key = 'EV-5'", Integer.class);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM evidence WHERE evidence_key = 'EV-5'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
    }

    // ---------- 交接主流程 ----------

    @Test
    void transferAcceptSwitchesCustodianAtomically() throws Exception {
        intake("cmd-t1-i", "EV-T1", "CASE-T", "DOC", "SEAL-T1", "alice").andExpect(status().isCreated());

        initiate("cmd-t1-t", "EV-T1", "alice", "bob")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.fromCustodianId").value("alice"))
                .andExpect(jsonPath("$.toCustodianId").value("bob"));

        // 待接收期间：alice 不再拥有可交接证物
        mockMvc.perform(get("/api/evidence/transferable").param("actorId", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // 非指定接收人不能接受
        accept("cmd-t1-a1", "EV-T1", "alice").andExpect(status().isConflict());
        // 指定接收人接受
        accept("cmd-t1-a2", "EV-T1", "bob")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        // 保管人原子切换回 SEALED
        mockMvc.perform(get("/api/evidence/transferable").param("actorId", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].custodianId").value("bob"))
                .andExpect(jsonPath("$[0].status").value("SEALED"));

        // 保管链完整
        mockMvc.perform(get("/api/evidence/EV-T1/custody-chain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(3)))
                .andExpect(jsonPath("$.events[0].type").value("INTAKE"))
                .andExpect(jsonPath("$.events[1].type").value("TRANSFER_INITIATE"))
                .andExpect(jsonPath("$.events[2].type").value("TRANSFER_ACCEPT"));
    }

    @Test
    void transferCancelRestoresSealedAndOnlyInitiatorCanCancel() throws Exception {
        intake("cmd-t2-i", "EV-T2", "CASE-T", "DOC", "SEAL-T2", "alice").andExpect(status().isCreated());
        initiate("cmd-t2-t", "EV-T2", "alice", "bob").andExpect(status().isCreated());

        // 接收人不能自行取消
        cancel("cmd-t2-c1", "EV-T2", "bob").andExpect(status().isConflict());
        // 原保管人取消
        cancel("cmd-t2-c2", "EV-T2", "alice")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 已取消交接不能再被接受
        accept("cmd-t2-a", "EV-T2", "bob").andExpect(status().isConflict());

        // 取消后可重新发起交接
        initiate("cmd-t2-t2", "EV-T2", "alice", "carol").andExpect(status().isCreated());
    }

    @Test
    void transferPendingBlocksFurtherOperations() throws Exception {
        intake("cmd-t3-i", "EV-T3", "CASE-T", "DOC", "SEAL-T3", "alice").andExpect(status().isCreated());
        initiate("cmd-t3-t", "EV-T3", "alice", "bob").andExpect(status().isCreated());

        // 待接收期间禁止再发起交接
        initiate("cmd-t3-t2", "EV-T3", "alice", "carol").andExpect(status().isConflict());
        // 待接收期间禁止封条核验
        sealCheck("cmd-t3-s", "EV-T3", "alice", "PASS").andExpect(status().isConflict());
    }

    @Test
    void transferRequiresCustodianAndDifferentRecipient() throws Exception {
        intake("cmd-t4-i", "EV-T4", "CASE-T", "DOC", "SEAL-T4", "alice").andExpect(status().isCreated());
        // 非保管人发起
        initiate("cmd-t4-t1", "EV-T4", "bob", "carol").andExpect(status().isConflict());
        // 接收人与保管人相同
        initiate("cmd-t4-t2", "EV-T4", "alice", "alice").andExpect(status().isBadRequest());
    }

    // ---------- 封条核验 ----------

    @Test
    void sealCheckPassAppendsRecordAndFailBreaksSeal() throws Exception {
        intake("cmd-s1-i", "EV-S1", "CASE-S", "DOC", "SEAL-S1", "alice").andExpect(status().isCreated());

        sealCheck("cmd-s1-c1", "EV-S1", "alice", "PASS")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("PASS"));
        // 通过后仍为 SEALED
        mockMvc.perform(get("/api/evidence/transferable").param("actorId", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        sealCheck("cmd-s1-c2", "EV-S1", "alice", "FAIL").andExpect(status().isCreated());

        // SEAL_BROKEN 后：禁止发起/接受交接、禁止再核验，且不可恢复
        initiate("cmd-s1-t", "EV-S1", "alice", "bob").andExpect(status().isUnprocessableEntity());
        accept("cmd-s1-a", "EV-S1", "bob").andExpect(status().isUnprocessableEntity());
        sealCheck("cmd-s1-c3", "EV-S1", "alice", "PASS").andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/evidence/transferable").param("actorId", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // 核验历史只追加，两条记录均在
        mockMvc.perform(get("/api/evidence/EV-S1/custody-chain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(3)))
                .andExpect(jsonPath("$.events[1].type").value("SEAL_CHECK"))
                .andExpect(jsonPath("$.events[1].result").value("PASS"))
                .andExpect(jsonPath("$.events[2].type").value("SEAL_CHECK"))
                .andExpect(jsonPath("$.events[2].result").value("FAIL"));
    }

    @Test
    void sealCheckRequiresCustodian() throws Exception {
        intake("cmd-s2-i", "EV-S2", "CASE-S", "DOC", "SEAL-S2", "alice").andExpect(status().isCreated());
        sealCheck("cmd-s2-c", "EV-S2", "bob", "PASS").andExpect(status().isConflict());
    }

    // ---------- 不存在 ----------

    @Test
    void unknownEvidenceReturns404() throws Exception {
        initiate("cmd-n-1", "EV-NONE", "alice", "bob").andExpect(status().isNotFound());
        accept("cmd-n-2", "EV-NONE", "bob").andExpect(status().isNotFound());
        cancel("cmd-n-3", "EV-NONE", "alice").andExpect(status().isNotFound());
        sealCheck("cmd-n-4", "EV-NONE", "alice", "PASS").andExpect(status().isNotFound());
        mockMvc.perform(get("/api/evidence/EV-NONE/custody-chain"))
                .andExpect(status().isNotFound());
    }

    // ---------- 幂等（变更类命令） ----------

    @Test
    void transferCommandsAreIdempotent() throws Exception {
        intake("cmd-m1-i", "EV-M1", "CASE-M", "DOC", "SEAL-M1", "alice").andExpect(status().isCreated());
        Map<String, Object> initBody = Map.of("commandKey", "cmd-m1-t", "toCustodianId", "bob");
        postJson("/api/evidence/EV-M1/transfers", "alice", initBody).andExpect(status().isCreated());
        // 同键同参重放返回首次结果，不产生第二条交接
        postJson("/api/evidence/EV-M1/transfers", "alice", initBody)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
        Integer transferCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence_transfer", Integer.class);
        org.assertj.core.api.Assertions.assertThat(transferCount).isEqualTo(1);
        // 同键改参 409
        postJson("/api/evidence/EV-M1/transfers", "alice",
                Map.of("commandKey", "cmd-m1-t", "toCustodianId", "carol"))
                .andExpect(status().isConflict());

        Map<String, Object> acceptBody = Map.of("commandKey", "cmd-m1-a");
        postJson("/api/evidence/EV-M1/transfers/accept", "bob", acceptBody).andExpect(status().isOk());
        postJson("/api/evidence/EV-M1/transfers/accept", "bob", acceptBody)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        // 接受重放后保管人仍为 bob 且只有一条交接记录
        mockMvc.perform(get("/api/evidence/transferable").param("actorId", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    // ---------- 辅助 ----------

    private ResultActions intake(String commandKey, String evidenceKey, String caseKey,
                                 String category, String sealNo, String custodianId) throws Exception {
        return postJson("/api/evidence", custodianId,
                intakeBody(commandKey, evidenceKey, caseKey, category, sealNo, custodianId));
    }

    private Map<String, Object> intakeBody(String commandKey, String evidenceKey, String caseKey,
                                           String category, String sealNo, String custodianId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", caseKey);
        body.put("category", category);
        body.put("sealNo", sealNo);
        body.put("custodianId", custodianId);
        return body;
    }

    private ResultActions initiate(String commandKey, String evidenceKey, String actor, String toCustodian) throws Exception {
        return postJson("/api/evidence/" + evidenceKey + "/transfers", actor,
                Map.of("commandKey", commandKey, "toCustodianId", toCustodian));
    }

    private ResultActions accept(String commandKey, String evidenceKey, String actor) throws Exception {
        return postJson("/api/evidence/" + evidenceKey + "/transfers/accept", actor,
                Map.of("commandKey", commandKey));
    }

    private ResultActions cancel(String commandKey, String evidenceKey, String actor) throws Exception {
        return postJson("/api/evidence/" + evidenceKey + "/transfers/cancel", actor,
                Map.of("commandKey", commandKey));
    }

    private ResultActions sealCheck(String commandKey, String evidenceKey, String actor, String result) throws Exception {
        return postJson("/api/evidence/" + evidenceKey + "/seal-checks", actor,
                Map.of("commandKey", commandKey, "result", result));
    }

    private ResultActions postJson(String url, String actor, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .header("X-Actor-Id", actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }
}
