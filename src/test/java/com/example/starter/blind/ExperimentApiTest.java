package com.example.starter.blind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 盲法实验 API 集成测试：覆盖主流程、失败分支、权限与幂等边界，使用 H2（MODE=MySQL）实测。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExperimentApiTest {

    private static final String COORD = "coord-1";
    private static final String COORD2 = "coord-2";
    private static final String REVIEWER = "rev-1";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper om;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM unblind_request");
        jdbc.update("DELETE FROM experiment_seat");
        jdbc.update("DELETE FROM experiment");
    }

    @AfterAll
    void releaseDatabase() {
        // 命名内存库使用 DB_CLOSE_DELAY=-1，测试上下文结束后显式释放
        jdbc.execute("SHUTDOWN");
    }

    private String rid() {
        return "REQ-" + UUID.randomUUID();
    }

    private MvcResult postJson(String url, String actor, String role, String body) throws Exception {
        return mvc.perform(post(url)
                        .header("X-Actor-Id", actor)
                        .header("X-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult getJson(String url, String actor, String role) throws Exception {
        return mvc.perform(get(url)
                        .header("X-Actor-Id", actor)
                        .header("X-Role", role))
                .andReturn();
    }

    private String createBody(String requestId, String experimentId, int blocks) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"requestId\":\"").append(requestId)
                .append("\",\"experimentId\":\"").append(experimentId).append("\",\"blocks\":[");
        for (int i = 0; i < blocks; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("[\"A\",\"B\",\"A\",\"B\"]");
        }
        return sb.append("]}").toString();
    }

    private void createExperiment(String experimentId, int blocks) throws Exception {
        postJson("/api/experiments", COORD, "COORDINATOR", createBody(rid(), experimentId, blocks));
    }

    private MvcResult assign(String experimentId, String participantId) throws Exception {
        return postJson("/api/experiments/" + experimentId + "/assignments", COORD, "COORDINATOR",
                "{\"requestId\":\"" + rid() + "\",\"participantId\":\"" + participantId + "\"}");
    }

    // ---------- 主流程 ----------

    @Test
    void fullLifecycle_assignQueryWithdrawClose() throws Exception {
        createExperiment("EXP-LC", 2);

        MvcResult assigned = assign("EXP-LC", "P-001");
        JsonNode body = om.readTree(assigned.getResponse().getContentAsString());
        assertThat(assigned.getResponse().getStatus()).isEqualTo(200);
        assertThat(body.get("blindCode").asText()).startsWith("BLD-");
        assertThat(body.get("blockNo").asInt()).isEqualTo(1);
        assertThat(body.get("status").asText()).isEqualTo("ASSIGNED");
        // 普通登记响应不得包含处理代码或席位序号
        assertThat(body.has("treatmentCode")).isFalse();
        assertThat(body.has("seatNo")).isFalse();

        // 普通查询只返回盲码、区组号、参与者编号与状态
        MvcResult queried = getJson("/api/experiments/EXP-LC/assignments/P-001", "anyone", "REVIEWER");
        JsonNode view = om.readTree(queried.getResponse().getContentAsString());
        assertThat(queried.getResponse().getStatus()).isEqualTo(200);
        assertThat(view.get("blindCode").asText()).isEqualTo(body.get("blindCode").asText());
        assertThat(view.has("treatmentCode")).isFalse();
        assertThat(view.has("seatNo")).isFalse();

        // 退组不释放席位，普通查询返回退组状态
        MvcResult withdrawn = postJson("/api/experiments/EXP-LC/assignments/P-001/withdraw",
                COORD, "COORDINATOR", "{\"requestId\":\"" + rid() + "\"}");
        assertThat(withdrawn.getResponse().getStatus()).isEqualTo(200);
        assertThat(om.readTree(withdrawn.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("WITHDRAWN");

        mvc.perform(get("/api/experiments/EXP-LC/assignments/P-001")
                        .header("X-Actor-Id", COORD).header("X-Role", "COORDINATOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));

        // 退组后席位不释放：再登记 7 人占满剩余席位，第 8 个新参与者应满额
        for (int i = 2; i <= 8; i++) {
            MvcResult r = assign("EXP-LC", "P-00" + i);
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
        }
        MvcResult overflow = assign("EXP-LC", "P-009");
        assertThat(overflow.getResponse().getStatus()).isEqualTo(422);

        // 关闭后拒绝新增分配
        MvcResult closed = postJson("/api/experiments/EXP-LC/close",
                COORD, "COORDINATOR", "{\"requestId\":\"" + rid() + "\"}");
        assertThat(closed.getResponse().getStatus()).isEqualTo(200);
        assertThat(om.readTree(closed.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("CLOSED");
    }

    @Test
    void assignFailsWhenClosedAndFull() throws Exception {
        createExperiment("EXP-FULL", 2);
        for (int i = 1; i <= 8; i++) {
            assertThat(assign("EXP-FULL", "P-" + i).getResponse().getStatus()).isEqualTo(200);
        }
        // 满额 422
        assertThat(assign("EXP-FULL", "P-9").getResponse().getStatus()).isEqualTo(422);

        postJson("/api/experiments/EXP-FULL/close", COORD, "COORDINATOR",
                "{\"requestId\":\"" + rid() + "\"}");
        // 关闭后拒绝新增分配（409）
        MvcResult after = assign("EXP-FULL", "P-10");
        assertThat(after.getResponse().getStatus()).isEqualTo(409);
        // 重复关闭 409
        MvcResult reclose = postJson("/api/experiments/EXP-FULL/close", COORD, "COORDINATOR",
                "{\"requestId\":\"" + rid() + "\"}");
        assertThat(reclose.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void duplicateParticipantAndUnknownResources() throws Exception {
        createExperiment("EXP-DUP", 2);
        assertThat(assign("EXP-DUP", "P-1").getResponse().getStatus()).isEqualTo(200);
        // 同实验同参与者只占一席
        assertThat(assign("EXP-DUP", "P-1").getResponse().getStatus()).isEqualTo(409);
        // 未知实验 / 未知参与者 404
        assertThat(assign("EXP-NONE", "P-1").getResponse().getStatus()).isEqualTo(404);
        assertThat(getJson("/api/experiments/EXP-DUP/assignments/P-X", COORD, "COORDINATOR")
                .getResponse().getStatus()).isEqualTo(404);
        // 重复退组 409
        postJson("/api/experiments/EXP-DUP/assignments/P-1/withdraw", COORD, "COORDINATOR",
                "{\"requestId\":\"" + rid() + "\"}");
        MvcResult again = postJson("/api/experiments/EXP-DUP/assignments/P-1/withdraw",
                COORD, "COORDINATOR", "{\"requestId\":\"" + rid() + "\"}");
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void createValidationAndDuplicate() throws Exception {
        // 区组数越界
        MvcResult tooFew = postJson("/api/experiments", COORD, "COORDINATOR",
                createBody(rid(), "EXP-B1", 1));
        assertThat(tooFew.getResponse().getStatus()).isEqualTo(400);
        MvcResult tooMany = postJson("/api/experiments", COORD, "COORDINATOR",
                createBody(rid(), "EXP-B9", 9));
        assertThat(tooMany.getResponse().getStatus()).isEqualTo(400);
        // 区组内非两个 A 两个 B
        MvcResult badSeats = postJson("/api/experiments", COORD, "COORDINATOR",
                "{\"requestId\":\"" + rid() + "\",\"experimentId\":\"EXP-BAD\","
                        + "\"blocks\":[[\"A\",\"A\",\"A\",\"B\"],[\"A\",\"B\",\"A\",\"B\"]]}");
        assertThat(badSeats.getResponse().getStatus()).isEqualTo(400);
        // 正常创建后 experimentId 冲突 409
        createExperiment("EXP-DUPID", 2);
        MvcResult dup = postJson("/api/experiments", COORD, "COORDINATOR",
                createBody(rid(), "EXP-DUPID", 2));
        assertThat(dup.getResponse().getStatus()).isEqualTo(409);
    }

    // ---------- 权限 ----------

    @Test
    void roleEnforcement() throws Exception {
        // 审核员不能创建实验
        assertThat(postJson("/api/experiments", REVIEWER, "REVIEWER",
                createBody(rid(), "EXP-R1", 2)).getResponse().getStatus()).isEqualTo(403);
        createExperiment("EXP-R2", 2);
        // 审核员不能登记
        MvcResult assignByReviewer = postJson("/api/experiments/EXP-R2/assignments",
                REVIEWER, "REVIEWER",
                "{\"requestId\":\"" + rid() + "\",\"participantId\":\"P-1\"}");
        assertThat(assignByReviewer.getResponse().getStatus()).isEqualTo(403);
        // 未知角色 403
        assertThat(getJson("/api/experiments/EXP-R2/assignments/P-1", COORD, "ADMIN")
                .getResponse().getStatus()).isEqualTo(403);
        // 缺少操作者头 403
        MvcResult noHeader = mvc.perform(get("/api/experiments/EXP-R2/assignments/P-1"))
                .andReturn();
        assertThat(noHeader.getResponse().getStatus()).isEqualTo(403);
    }

    // ---------- 揭盲流程 ----------

    private String applyUnblind(String experimentId, String participantId) throws Exception {
        MvcResult r = postJson("/api/experiments/" + experimentId + "/unblind-requests",
                COORD, "COORDINATOR",
                "{\"requestId\":\"" + rid() + "\",\"participantId\":\"" + participantId
                        + "\",\"reason\":\"safety review\"}");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return om.readTree(r.getResponse().getContentAsString()).get("unblindId").asText();
    }

    @Test
    void unblindFlow_applyApproveResult() throws Exception {
        createExperiment("EXP-UB", 2);
        assign("EXP-UB", "P-1");
        String unblindId = applyUnblind("EXP-UB", "P-1");

        // 未批准 409
        assertThat(getJson("/api/experiments/EXP-UB/unblind-requests/" + unblindId + "/result",
                COORD, "COORDINATOR").getResponse().getStatus()).isEqualTo(409);

        // 协调员不能批准
        assertThat(postJson("/api/experiments/EXP-UB/unblind-requests/" + unblindId + "/approve",
                COORD2, "COORDINATOR", "{\"requestId\":\"" + rid() + "\"}")
                .getResponse().getStatus()).isEqualTo(403);

        // 另一名审核员批准
        MvcResult approved = postJson(
                "/api/experiments/EXP-UB/unblind-requests/" + unblindId + "/approve",
                REVIEWER, "REVIEWER", "{\"requestId\":\"" + rid() + "\"}");
        assertThat(approved.getResponse().getStatus()).isEqualTo(200);
        assertThat(om.readTree(approved.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("APPROVED");

        // 重复批准 409
        assertThat(postJson("/api/experiments/EXP-UB/unblind-requests/" + unblindId + "/approve",
                "rev-2", "REVIEWER", "{\"requestId\":\"" + rid() + "\"}")
                .getResponse().getStatus()).isEqualTo(409);

        // 其他人查询结果 403
        assertThat(getJson("/api/experiments/EXP-UB/unblind-requests/" + unblindId + "/result",
                COORD2, "COORDINATOR").getResponse().getStatus()).isEqualTo(403);
        assertThat(getJson("/api/experiments/EXP-UB/unblind-requests/" + unblindId + "/result",
                REVIEWER, "REVIEWER").getResponse().getStatus()).isEqualTo(403);

        // 仅申请人可查，且包含处理代码
        MvcResult result = getJson("/api/experiments/EXP-UB/unblind-requests/" + unblindId + "/result",
                COORD, "COORDINATOR");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode view = om.readTree(result.getResponse().getContentAsString());
        assertThat(view.get("treatmentCode").asText()).isIn("A", "B");
        // 与库中处理映射一致
        String stored = jdbc.queryForObject(
                "SELECT treatment_code FROM experiment_seat WHERE experiment_id = 'EXP-UB'"
                        + " AND participant_id = 'P-1'", String.class);
        assertThat(view.get("treatmentCode").asText()).isEqualTo(stored);
    }

    @Test
    void unblindPendingUniquenessAndSurvivesCloseWithdraw() throws Exception {
        createExperiment("EXP-UB2", 2);
        assign("EXP-UB2", "P-1");
        String unblindId = applyUnblind("EXP-UB2", "P-1");

        // 同一分配至多一个待审申请
        MvcResult dup = postJson("/api/experiments/EXP-UB2/unblind-requests", COORD2, "COORDINATOR",
                "{\"requestId\":\"" + rid() + "\",\"participantId\":\"P-1\","
                        + "\"reason\":\"another reason\"}");
        assertThat(dup.getResponse().getStatus()).isEqualTo(409);

        // 未分配参与者不能申请
        MvcResult notAssigned = postJson("/api/experiments/EXP-UB2/unblind-requests",
                COORD, "COORDINATOR",
                "{\"requestId\":\"" + rid() + "\",\"participantId\":\"P-X\","
                        + "\"reason\":\"no seat\"}");
        assertThat(notAssigned.getResponse().getStatus()).isEqualTo(404);

        // 批准后：退组与关闭均不撤销已批准的揭盲
        postJson("/api/experiments/EXP-UB2/unblind-requests/" + unblindId + "/approve",
                REVIEWER, "REVIEWER", "{\"requestId\":\"" + rid() + "\"}");
        postJson("/api/experiments/EXP-UB2/assignments/P-1/withdraw", COORD, "COORDINATOR",
                "{\"requestId\":\"" + rid() + "\"}");
        postJson("/api/experiments/EXP-UB2/close", COORD, "COORDINATOR",
                "{\"requestId\":\"" + rid() + "\"}");

        MvcResult result = getJson("/api/experiments/EXP-UB2/unblind-requests/" + unblindId + "/result",
                COORD, "COORDINATOR");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(om.readTree(result.getResponse().getContentAsString())
                .get("treatmentCode").asText()).isIn("A", "B");
    }

    // ---------- 幂等 ----------

    @Test
    void idempotentReplaySameParamsAndConflictOnDifferent() throws Exception {
        createExperiment("EXP-IDEM", 2);
        String requestId = rid();
        String payload = "{\"requestId\":\"" + requestId + "\",\"participantId\":\"P-1\"}";

        MvcResult first = mvc.perform(post("/api/experiments/EXP-IDEM/assignments")
                        .header("X-Actor-Id", COORD).header("X-Role", "COORDINATOR")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        String firstBody = first.getResponse().getContentAsString();

        // 同键同参重放原成功结果
        MvcResult replay = mvc.perform(post("/api/experiments/EXP-IDEM/assignments")
                        .header("X-Actor-Id", COORD).header("X-Role", "COORDINATOR")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andReturn();
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(firstBody);
        // 重放不产生新分配
        Integer seats = jdbc.queryForObject(
                "SELECT COUNT(*) FROM experiment_seat WHERE experiment_id = 'EXP-IDEM'"
                        + " AND participant_id IS NOT NULL", Integer.class);
        assertThat(seats).isEqualTo(1);

        // 同键异参 409
        MvcResult conflict = mvc.perform(post("/api/experiments/EXP-IDEM/assignments")
                        .header("X-Actor-Id", COORD).header("X-Role", "COORDINATOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId
                                + "\",\"participantId\":\"P-2\"}"))
                .andReturn();
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);

        // 幂等参数含操作者与角色：同键不同操作者 409
        MvcResult otherActor = mvc.perform(post("/api/experiments/EXP-IDEM/assignments")
                        .header("X-Actor-Id", COORD2).header("X-Role", "COORDINATOR")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andReturn();
        assertThat(otherActor.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void failedOperationDoesNotConsumeRequestId() throws Exception {
        createExperiment("EXP-FAIL", 2);
        String requestId = rid();
        // 未分配参与者申请揭盲 → 404，失败不占键
        MvcResult failed = postJson("/api/experiments/EXP-FAIL/unblind-requests", COORD, "COORDINATOR",
                "{\"requestId\":\"" + requestId + "\",\"participantId\":\"P-X\","
                        + "\"reason\":\"r\"}");
        assertThat(failed.getResponse().getStatus()).isEqualTo(404);
        Integer keys = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = '" + requestId + "'",
                Integer.class);
        assertThat(keys).isZero();

        // 同键换有效参数可正常成功
        assign("EXP-FAIL", "P-1");
        MvcResult ok = postJson("/api/experiments/EXP-FAIL/unblind-requests", COORD, "COORDINATOR",
                "{\"requestId\":\"" + requestId + "\",\"participantId\":\"P-1\","
                        + "\"reason\":\"r\"}");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void permissionCheckedBeforeIdempotencyReplay() throws Exception {
        createExperiment("EXP-PERM", 2);
        String requestId = rid();
        String payload = "{\"requestId\":\"" + requestId + "\",\"participantId\":\"P-1\"}";
        mvc.perform(post("/api/experiments/EXP-PERM/assignments")
                        .header("X-Actor-Id", COORD).header("X-Role", "COORDINATOR")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk());
        // 同键同参但角色无权：权限校验先于幂等回放，返回 403 而非回放成功
        MvcResult denied = mvc.perform(post("/api/experiments/EXP-PERM/assignments")
                        .header("X-Actor-Id", COORD).header("X-Role", "REVIEWER")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void blindCodesAreRandomAndDistinctAcrossSeats() throws Exception {
        createExperiment("EXP-BLIND", 2);
        Set<String> codes = new HashSet<>();
        for (int i = 1; i <= 8; i++) {
            MvcResult r = assign("EXP-BLIND", "P-" + i);
            JsonNode body = om.readTree(r.getResponse().getContentAsString());
            codes.add(body.get("blindCode").asText());
        }
        assertThat(codes).hasSize(8);
        // 盲码不含处理代码信息
        assertThat(codes).allSatisfy(c -> assertThat(c).startsWith("BLD-").hasSize(16));
    }
}
