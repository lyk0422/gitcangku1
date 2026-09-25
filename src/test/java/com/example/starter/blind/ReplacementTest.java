package com.example.starter.blind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受试者替补：主流程（继承分配序号与处理代码、原参与者 REPLACED 终态）、
 * 资格失败分支（未退组/已揭盲/已替补/重复参与者）、区组配额守恒、
 * 盲态隔离（响应不含处理代码）、替补后揭盲与退组走向、幂等回放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReplacementTest extends AbstractBlindIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpHeaders headers(String actor, String role, String requestId) {
        HttpHeaders h = new HttpHeaders();
        if (actor != null) {
            h.set("X-Actor-Id", actor);
        }
        if (role != null) {
            h.set("X-Role", role);
        }
        if (requestId != null) {
            h.set("X-Request-Id", requestId);
        }
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method,
                                            HttpHeaders headers, String body) {
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        assertNotNull(response.getBody());
        return mapper.readTree(response.getBody());
    }

    private void createExperiment(String expId, int blockCount, String requestId) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("c1", "COORDINATOR", requestId),
                "{\"blockCount\":" + blockCount + "}").getStatusCode().value());
    }

    private void register(String expId, String pid, String requestId) {
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", requestId), null)
                .getStatusCode().value());
    }

    private void withdraw(String expId, String pid, String requestId) {
        assertEquals(200, exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/withdrawal",
                HttpMethod.POST, headers("c1", "COORDINATOR", requestId), null)
                .getStatusCode().value());
    }

    private ResponseEntity<String> replace(String expId, String pid, String newPid,
                                           String replaceKey, String requestId) {
        return exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/replacement",
                HttpMethod.POST, headers("c1", "COORDINATOR", requestId),
                "{\"replaceKey\":\"" + replaceKey + "\",\"newParticipantId\":\"" + newPid + "\"}");
    }

    private String treatmentOf(String expId, String pid) {
        List<String> t = jdbc.queryForList(
                "SELECT s.treatment FROM allocation a JOIN seat s "
                        + "ON s.experiment_id = a.experiment_id "
                        + "AND s.block_no = a.block_no AND s.seat_no = a.seat_no "
                        + "WHERE a.experiment_id = ? AND a.participant_id = ?",
                String.class, expId, pid);
        return t.isEmpty() ? null : t.get(0);
    }

    private JsonNode quota(String expId, int blockNo) throws Exception {
        ResponseEntity<String> resp = exchange(
                "/api/experiments/" + expId + "/blocks/" + blockNo + "/quota",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null);
        assertEquals(200, resp.getStatusCode().value());
        return json(resp);
    }

    // ---------------- 主流程与配额守恒 ----------------

    @Test
    void replace_inheritsSlotAndTreatment_originalBecomesReplaced_quotaConserved()
            throws Exception {
        createExperiment("REP-1", 2, "rep1-create");
        for (int i = 1; i <= 4; i++) {
            register("REP-1", "P" + i, "rep1-alloc-" + i);
        }
        String p2Treatment = treatmentOf("REP-1", "P2");
        assertNotNull(p2Treatment);
        long p2AllocationId = jdbc.queryForObject(
                "SELECT id FROM allocation WHERE experiment_id = 'REP-1' AND participant_id = 'P2'",
                Long.class);
        String p2BlindCode = jdbc.queryForObject(
                "SELECT blind_code FROM allocation WHERE experiment_id = 'REP-1' "
                        + "AND participant_id = 'P2'", String.class);

        clock.advance(1_000L);
        withdraw("REP-1", "P2", "rep1-withdraw-p2");

        // 替补前区组 1 名额：4 席全占，1 人退组，可用名额 0
        JsonNode before = quota("REP-1", 1);
        assertEquals(4, before.path("capacity").asInt());
        assertEquals(4, before.path("allocatedSlots").asInt());
        assertEquals(0, before.path("availableSlots").asInt());
        assertEquals(3, before.path("activeParticipants").asInt());
        assertEquals(1, before.path("withdrawnParticipants").asInt());
        assertEquals(0, before.path("replacedParticipants").asInt());
        assertFalse(before.has("treatment"), "名额统计不得包含处理代码");

        clock.advance(2_000L);
        ResponseEntity<String> resp = replace("REP-1", "P2", "P5", "RK-1", "rep1-replace-p2");
        assertEquals(201, resp.getStatusCode().value());
        JsonNode body = json(resp);
        assertEquals("REP-1", body.path("experimentId").asText());
        assertEquals("RK-1", body.path("replaceKey").asText());
        assertEquals(p2AllocationId, body.path("allocationId").asLong(), "替补不新建分配序号");
        assertEquals(1, body.path("blockNo").asInt());
        assertEquals("P2", body.path("originalParticipantId").asText());
        assertEquals("P5", body.path("newParticipantId").asText());
        assertEquals("c1", body.path("operatorActor").asText());
        assertEquals(1_700_000_003_000L, body.path("replacedAt").asLong());
        assertFalse(body.has("treatment"), "替补响应不得包含处理代码");
        assertFalse(body.has("seatNo"), "替补响应不得包含席位号");
        assertFalse(body.has("blindCode"), "替补响应不得包含盲码");

        // 新参与者：继承同一分配序号、同一盲码、同一处理代码（库内校验），状态在组
        assertEquals(p2AllocationId, jdbc.queryForObject(
                "SELECT id FROM allocation WHERE experiment_id = 'REP-1' AND participant_id = 'P5'",
                Long.class));
        assertEquals(p2BlindCode, jdbc.queryForObject(
                "SELECT blind_code FROM allocation WHERE experiment_id = 'REP-1' "
                        + "AND participant_id = 'P5'", String.class));
        assertEquals(p2Treatment, treatmentOf("REP-1", "P5"), "新参与者继承原处理代码");
        assertEquals("ASSIGNED", jdbc.queryForObject(
                "SELECT status FROM allocation WHERE experiment_id = 'REP-1' "
                        + "AND participant_id = 'P5'", String.class));
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'REP-1'", Integer.class),
                "替补不产生新分配行");

        // 原参与者：分配表中已无其行，替补记录固化
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'REP-1' "
                        + "AND participant_id = 'P2'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM replacement WHERE experiment_id = 'REP-1' "
                        + "AND original_participant_id = 'P2' AND new_participant_id = 'P5' "
                        + "AND allocation_id = " + p2AllocationId, Integer.class));

        // 原参与者查询返回 REPLACED 终态记录
        ResponseEntity<String> p2View = exchange("/api/experiments/REP-1/participants/P2",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null);
        assertEquals(200, p2View.getStatusCode().value());
        JsonNode p2Node = json(p2View);
        assertEquals("REPLACED", p2Node.path("status").asText());
        assertEquals(1, p2Node.path("blockNo").asInt());
        assertEquals(1_700_000_003_000L, p2Node.path("replacedAt").asLong());
        assertFalse(p2Node.has("treatment"));
        assertFalse(p2Node.has("seatNo"));

        // 新参与者普通查询：在组，同区组同盲码
        ResponseEntity<String> p5View = exchange("/api/experiments/REP-1/participants/P5",
                HttpMethod.GET, headers("r1", "REVIEWER", null), null);
        JsonNode p5Node = json(p5View);
        assertEquals("ASSIGNED", p5Node.path("status").asText());
        assertEquals(p2BlindCode, p5Node.path("blindCode").asText());
        assertEquals(1, p5Node.path("blockNo").asInt());

        // 替补后名额统计：可用名额守恒（仍为 0），已占用分配序号不变，在组人数恢复
        JsonNode after = quota("REP-1", 1);
        assertEquals(before.path("allocatedSlots").asLong(), after.path("allocatedSlots").asLong());
        assertEquals(before.path("availableSlots").asLong(), after.path("availableSlots").asLong(),
                "替补前后可用名额必须守恒");
        assertEquals(4, after.path("activeParticipants").asInt());
        assertEquals(0, after.path("withdrawnParticipants").asInt());
        assertEquals(1, after.path("replacedParticipants").asInt());

        // 区组各处理代码配额计数不变（库内校验：仍两个 A 两个 B）
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation a JOIN seat s "
                        + "ON s.experiment_id = a.experiment_id AND s.block_no = a.block_no "
                        + "AND s.seat_no = a.seat_no "
                        + "WHERE a.experiment_id = 'REP-1' AND a.block_no = 1 "
                        + "AND s.treatment = 'A'", Long.class));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation a JOIN seat s "
                        + "ON s.experiment_id = a.experiment_id AND s.block_no = a.block_no "
                        + "AND s.seat_no = a.seat_no "
                        + "WHERE a.experiment_id = 'REP-1' AND a.block_no = 1 "
                        + "AND s.treatment = 'B'", Long.class));

        // 替补历史：含本记录，不含处理代码
        ResponseEntity<String> history = exchange("/api/experiments/REP-1/replacements",
                HttpMethod.GET, headers("r1", "REVIEWER", null), null);
        assertEquals(200, history.getStatusCode().value());
        JsonNode records = json(history);
        assertEquals(1, records.size());
        JsonNode rec = records.get(0);
        assertEquals("P2", rec.path("originalParticipantId").asText());
        assertEquals("P5", rec.path("newParticipantId").asText());
        assertEquals(p2AllocationId, rec.path("allocationId").asLong());
        assertEquals(1, rec.path("blockNo").asInt());
        assertFalse(rec.has("treatment"));
        assertFalse(rec.has("seatNo"));
        // 按区组过滤
        ResponseEntity<String> block2History = exchange(
                "/api/experiments/REP-1/replacements?blockNo=2",
                HttpMethod.GET, headers("r1", "REVIEWER", null), null);
        assertEquals(0, json(block2History).size());
    }

    @Test
    void replace_chainedReplacement_allowed_andOriginalsAllReplaced() throws Exception {
        createExperiment("REP-CHAIN", 2, "chain-create");
        register("REP-CHAIN", "P1", "chain-alloc-1");
        withdraw("REP-CHAIN", "P1", "chain-withdraw-1");
        assertEquals(201, replace("REP-CHAIN", "P1", "P2", "RK-C1", "chain-replace-1")
                .getStatusCode().value());
        withdraw("REP-CHAIN", "P2", "chain-withdraw-2");
        // 替补参与者退组后也可被再替补（链式），分配序号始终不变
        ResponseEntity<String> second = replace("REP-CHAIN", "P2", "P3", "RK-C2", "chain-replace-2");
        assertEquals(201, second.getStatusCode().value());
        long allocationId = json(second).path("allocationId").asLong();
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'REP-CHAIN'", Integer.class));
        assertEquals(allocationId, jdbc.queryForObject(
                "SELECT id FROM allocation WHERE experiment_id = 'REP-CHAIN'", Long.class));
        assertEquals("P3", jdbc.queryForObject(
                "SELECT participant_id FROM allocation WHERE experiment_id = 'REP-CHAIN'",
                String.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM replacement WHERE experiment_id = 'REP-CHAIN'",
                Integer.class));
        // 两代原参与者均为 REPLACED 终态
        assertEquals("REPLACED", json(exchange("/api/experiments/REP-CHAIN/participants/P1",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null)).path("status").asText());
        assertEquals("REPLACED", json(exchange("/api/experiments/REP-CHAIN/participants/P2",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null)).path("status").asText());
    }

    // ---------------- 资格失败分支 ----------------

    @Test
    void replace_notWithdrawn_replaced_unblinded_conflicts() throws Exception {
        createExperiment("REP-2", 2, "rep2-create");
        register("REP-2", "P1", "rep2-alloc-1");
        register("REP-2", "P2", "rep2-alloc-2");
        register("REP-2", "P3", "rep2-alloc-3");

        // 未退组：409
        assertEquals(409, replace("REP-2", "P1", "P9", "RK-2", "rep2-replace-active")
                .getStatusCode().value());

        // 已退组且已揭盲（批准）：409
        ResponseEntity<String> apply = exchange(
                "/api/experiments/REP-2/participants/P2/unblind-requests", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rep2-apply-p2"), "{\"reason\":\"核对处理\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = json(apply).path("requestId").asText();
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-1", "REVIEWER", "rep2-approve-p2"), null)
                .getStatusCode().value());
        withdraw("REP-2", "P2", "rep2-withdraw-p2");
        assertEquals(409, replace("REP-2", "P2", "P9", "RK-2", "rep2-replace-unblinded")
                .getStatusCode().value());

        // 正常替补 P3 -> P8 后，再次对 P3 替补：已替补 409
        withdraw("REP-2", "P3", "rep2-withdraw-p3");
        assertEquals(201, replace("REP-2", "P3", "P8", "RK-3", "rep2-replace-p3")
                .getStatusCode().value());
        assertEquals(409, replace("REP-2", "P3", "P10", "RK-4", "rep2-replace-p3-again")
                .getStatusCode().value());

        // 未登记参与者：404；不存在实验：404
        assertEquals(404, replace("REP-2", "NOBODY", "P11", "RK-5", "rep2-replace-nobody")
                .getStatusCode().value());
        assertEquals(404, replace("REP-MISSING", "P1", "P11", "RK-6", "rep2-replace-missing")
                .getStatusCode().value());
    }

    @Test
    void replace_duplicateNewParticipant_conflicts() throws Exception {
        createExperiment("REP-3", 2, "rep3-create");
        createExperiment("REP-3B", 2, "rep3b-create");
        register("REP-3", "P1", "rep3-alloc-1");
        register("REP-3", "P2", "rep3-alloc-2");
        register("REP-3B", "PX", "rep3b-alloc-x");
        withdraw("REP-3", "P1", "rep3-withdraw-1");

        // 新参与者已在本实验登记：409
        assertEquals(409, replace("REP-3", "P1", "P2", "RK-D1", "rep3-replace-dup1")
                .getStatusCode().value());
        // 新参与者已在其他实验登记（任何区组）：409
        assertEquals(409, replace("REP-3", "P1", "PX", "RK-D2", "rep3-replace-dup2")
                .getStatusCode().value());
        // 新参与者与原参与者相同：409
        assertEquals(409, replace("REP-3", "P1", "P1", "RK-D3", "rep3-replace-self")
                .getStatusCode().value());

        // 成功替补 P1 -> P5
        assertEquals(201, replace("REP-3", "P1", "P5", "RK-D4", "rep3-replace-p1")
                .getStatusCode().value());

        // 新参与者曾是别人的替补新参与者（拥有历史分配）：409
        withdraw("REP-3", "P2", "rep3-withdraw-2");
        assertEquals(409, replace("REP-3", "P2", "P5", "RK-D5", "rep3-replace-dup3")
                .getStatusCode().value());
        // 新参与者曾是被替补的原参与者（拥有历史分配）：409
        assertEquals(409, replace("REP-3", "P2", "P1", "RK-D6", "rep3-replace-dup4")
                .getStatusCode().value());
    }

    @Test
    void replace_blankParams_badRequest() {
        createExperiment("REP-4", 2, "rep4-create");
        register("REP-4", "P1", "rep4-alloc-1");
        withdraw("REP-4", "P1", "rep4-withdraw-1");

        assertEquals(400, exchange("/api/experiments/REP-4/participants/P1/replacement",
                HttpMethod.POST, headers("c1", "COORDINATOR", "rep4-blank-key"),
                "{\"replaceKey\":\"\",\"newParticipantId\":\"P5\"}").getStatusCode().value());
        assertEquals(400, exchange("/api/experiments/REP-4/participants/P1/replacement",
                HttpMethod.POST, headers("c1", "COORDINATOR", "rep4-blank-new"),
                "{\"replaceKey\":\"RK\",\"newParticipantId\":\"\"}").getStatusCode().value());
        assertEquals(400, exchange("/api/experiments/REP-4/participants/P1/replacement",
                HttpMethod.POST, headers("c1", "COORDINATOR", "rep4-no-body"), null)
                .getStatusCode().value());
    }

    // ---------------- 替补后的揭盲 / 退组 / 登记走向 ----------------

    @Test
    void afterReplacement_originalUnblind409_pendingRequestSurvives_newParticipantIndependent()
            throws Exception {
        createExperiment("REP-5", 2, "rep5-create");
        register("REP-5", "P1", "rep5-alloc-1");
        // 替补前原参与者已有待审揭盲申请
        ResponseEntity<String> apply = exchange(
                "/api/experiments/REP-5/participants/P1/unblind-requests", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rep5-apply-p1"), "{\"reason\":\"替补前申请\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = json(apply).path("requestId").asText();

        withdraw("REP-5", "P1", "rep5-withdraw-1");
        assertEquals(201, replace("REP-5", "P1", "P5", "RK-5", "rep5-replace-p1")
                .getStatusCode().value());

        // 替补先提交：后续对原参与者的揭盲申请 409
        assertEquals(409, exchange("/api/experiments/REP-5/participants/P1/unblind-requests",
                HttpMethod.POST, headers("c1", "COORDINATOR", "rep5-apply-p1-after"),
                "{\"reason\":\"替补后申请\"}").getStatusCode().value());

        // 既有待审申请保留并继续指向原标识：仍可批准，结果仍属于 P1
        String expectedTreatment = jdbc.queryForObject(
                "SELECT s.treatment FROM allocation a JOIN seat s "
                        + "ON s.experiment_id = a.experiment_id AND s.block_no = a.block_no "
                        + "AND s.seat_no = a.seat_no "
                        + "WHERE a.experiment_id = 'REP-5' AND a.participant_id = 'P5'",
                String.class);
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-1", "REVIEWER", "rep5-approve-old"), null)
                .getStatusCode().value());
        ResponseEntity<String> result = exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null);
        assertEquals(200, result.getStatusCode().value());
        JsonNode resultBody = json(result);
        assertEquals("P1", resultBody.path("participantId").asText(), "既有申请继续指向原标识");
        assertEquals(expectedTreatment, resultBody.path("treatment").asText());

        // 原参与者退组：409（REPLACED 终态）；重新登记：409
        assertEquals(409, exchange("/api/experiments/REP-5/participants/P1/withdrawal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "rep5-withdraw-p1-again"), null)
                .getStatusCode().value());
        assertEquals(409, exchange("/api/experiments/REP-5/participants/P1/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", "rep5-alloc-p1-again"), null)
                .getStatusCode().value());

        // 新参与者独立走揭盲流程
        ResponseEntity<String> p5Apply = exchange(
                "/api/experiments/REP-5/participants/P5/unblind-requests", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rep5-apply-p5"), "{\"reason\":\"新参与者申请\"}");
        assertEquals(201, p5Apply.getStatusCode().value());
        String p5UbId = json(p5Apply).path("requestId").asText();
        assertEquals(200, exchange("/api/unblind-requests/" + p5UbId + "/approval",
                HttpMethod.POST, headers("rev-1", "REVIEWER", "rep5-approve-p5"), null)
                .getStatusCode().value());
        ResponseEntity<String> p5Result = exchange("/api/unblind-requests/" + p5UbId + "/result",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null);
        assertEquals(expectedTreatment, json(p5Result).path("treatment").asText(),
                "新参与者继承的处理代码与原参与者一致");

        // 新参与者独立退组
        assertEquals(200, exchange("/api/experiments/REP-5/participants/P5/withdrawal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "rep5-withdraw-p5"), null)
                .getStatusCode().value());
    }

    // ---------------- 幂等与权限 ----------------

    @Test
    void replace_idempotency_replayAndConflictAndFailureNotConsumingKey() throws Exception {
        createExperiment("REP-6", 2, "rep6-create");
        register("REP-6", "P1", "rep6-alloc-1");
        withdraw("REP-6", "P1", "rep6-withdraw-1");

        // 首次成功
        ResponseEntity<String> first = replace("REP-6", "P1", "P5", "RK-I1", "rep6-replace-key");
        assertEquals(201, first.getStatusCode().value());
        // 同键同参重放：原样返回，不产生第二条替补记录
        ResponseEntity<String> replay = replace("REP-6", "P1", "P5", "RK-I1", "rep6-replace-key");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM replacement WHERE experiment_id = 'REP-6'", Integer.class));
        // 同键异参：409
        assertEquals(409, replace("REP-6", "P1", "P6", "RK-I1", "rep6-replace-key")
                .getStatusCode().value());
        assertEquals(409, replace("REP-6", "P1", "P5", "RK-OTHER", "rep6-replace-key")
                .getStatusCode().value());

        // 失败不占键：对未退组参与者替补 409 后，同一 requestId 换合法参数可成功
        register("REP-6", "P2", "rep6-alloc-2");
        assertEquals(409, replace("REP-6", "P2", "P7", "RK-I2", "rep6-replace-key-2")
                .getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'rep6-replace-key-2'",
                Integer.class));
        withdraw("REP-6", "P2", "rep6-withdraw-2");
        assertEquals(201, replace("REP-6", "P2", "P7", "RK-I2", "rep6-replace-key-2")
                .getStatusCode().value());
    }

    @Test
    void replace_permissionPrecedesIdempotency() {
        createExperiment("REP-7", 2, "rep7-create");
        register("REP-7", "P1", "rep7-alloc-1");
        withdraw("REP-7", "P1", "rep7-withdraw-1");

        // REVIEWER 不能替补：403
        assertEquals(403, exchange("/api/experiments/REP-7/participants/P1/replacement",
                HttpMethod.POST, headers("r1", "REVIEWER", "rep7-replace-reviewer"),
                "{\"replaceKey\":\"RK\",\"newParticipantId\":\"P5\"}").getStatusCode().value());
        // 缺身份头：401
        assertEquals(401, exchange("/api/experiments/REP-7/participants/P1/replacement",
                HttpMethod.POST, headers(null, null, "rep7-replace-noauth"),
                "{\"replaceKey\":\"RK\",\"newParticipantId\":\"P5\"}").getStatusCode().value());

        // 协调员成功占用 requestId
        assertEquals(201, replace("REP-7", "P1", "P5", "RK-P1", "rep7-replace-key")
                .getStatusCode().value());
        // 同一 requestId 由 REVIEWER 重放：权限先于幂等，403 而非回放
        assertEquals(403, exchange("/api/experiments/REP-7/participants/P1/replacement",
                HttpMethod.POST, headers("r1", "REVIEWER", "rep7-replace-key"),
                "{\"replaceKey\":\"RK-P1\",\"newParticipantId\":\"P5\"}").getStatusCode().value());
    }

    // ---------------- 查询边界 ----------------

    @Test
    void quotaAndHistory_invalidScopes_notFound() {
        createExperiment("REP-8", 2, "rep8-create");
        // 区组号越界：404
        assertEquals(404, exchange("/api/experiments/REP-8/blocks/0/quota",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null).getStatusCode().value());
        assertEquals(404, exchange("/api/experiments/REP-8/blocks/3/quota",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null).getStatusCode().value());
        // 实验不存在：404
        assertEquals(404, exchange("/api/experiments/REP-NOPE/blocks/1/quota",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null).getStatusCode().value());
        assertEquals(404, exchange("/api/experiments/REP-NOPE/replacements",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null).getStatusCode().value());
        assertEquals(404, exchange("/api/experiments/REP-8/replacements?blockNo=9",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null).getStatusCode().value());
    }
}
