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
 * 受试者替补：主流程、替补资格、区组配额守恒、盲态隔离与替补后流程。
 * 通过真实 H2 库校验分配行原地转移、处理代码继承与替补记录不可变内容。
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

    private void createExperiment(String expId, String requestId) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("c1", "COORDINATOR", requestId), "{\"blockCount\":2}")
                .getStatusCode().value());
    }

    private void register(String expId, String participantId, String requestId) {
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/" + participantId + "/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", requestId), null)
                .getStatusCode().value());
    }

    private void withdraw(String expId, String participantId, String requestId) {
        assertEquals(200, exchange(
                "/api/experiments/" + expId + "/participants/" + participantId + "/withdrawal",
                HttpMethod.POST, headers("c1", "COORDINATOR", requestId), null)
                .getStatusCode().value());
    }

    private ResponseEntity<String> replace(String expId, String participantId,
                                           String replaceKey, String newParticipantId,
                                           String requestId) {
        return exchange(
                "/api/experiments/" + expId + "/participants/" + participantId + "/replacement",
                HttpMethod.POST, headers("c1", "COORDINATOR", requestId),
                "{\"replaceKey\":\"" + replaceKey + "\","
                        + "\"newParticipantId\":\"" + newParticipantId + "\"}");
    }

    private JsonNode quota(String expId, int blockNo) throws Exception {
        ResponseEntity<String> resp = exchange(
                "/api/experiments/" + expId + "/blocks/" + blockNo + "/quota", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null);
        assertEquals(200, resp.getStatusCode().value());
        return json(resp);
    }

    private void assertQuotaConserved(JsonNode quota) {
        int total = quota.path("totalSeats").asInt();
        long active = quota.path("activeParticipants").asLong();
        long available = quota.path("availableSeats").asLong();
        assertEquals(total, active + available, "活跃数 + 可用名额必须等于总席位（守恒）");
        assertFalse(quota.has("treatment"), "名额统计不得包含处理代码");
    }

    private String treatmentOf(String expId, String participantId) {
        List<String> t = jdbc.queryForList(
                "SELECT s.treatment FROM allocation a JOIN seat s "
                        + "ON s.experiment_id = a.experiment_id "
                        + "AND s.block_no = a.block_no AND s.seat_no = a.seat_no "
                        + "WHERE a.experiment_id = ? AND a.participant_id = ?",
                String.class, expId, participantId);
        return t.isEmpty() ? null : t.get(0);
    }

    @Test
    void replace_withdrawnParticipant_inheritsBlockAndTreatment_quotaConserved() throws Exception {
        createExperiment("REP-1", "rep1-create");
        for (int i = 1; i <= 4; i++) {
            register("REP-1", "P" + i, "rep1-alloc-" + i);
        }
        // 满员：活跃 4，可用 0
        JsonNode quotaBefore = quota("REP-1", 1);
        assertEquals(4, quotaBefore.path("activeParticipants").asLong());
        assertEquals(0, quotaBefore.path("availableSeats").asLong());
        assertQuotaConserved(quotaBefore);

        // 退组释放一个可用名额
        withdraw("REP-1", "P1", "rep1-withdraw-1");
        JsonNode quotaAfterWithdraw = quota("REP-1", 1);
        assertEquals(3, quotaAfterWithdraw.path("activeParticipants").asLong());
        assertEquals(1, quotaAfterWithdraw.path("availableSeats").asLong());
        assertQuotaConserved(quotaAfterWithdraw);

        long allocationId = jdbc.queryForObject(
                "SELECT id FROM allocation WHERE experiment_id = 'REP-1' AND participant_id = 'P1'",
                Long.class);
        String originalTreatment = treatmentOf("REP-1", "P1");
        // 替补前区组内各处理代码的占位计数（退组不释放席位）
        int occupiedABefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation a JOIN seat s "
                        + "ON s.experiment_id = a.experiment_id AND s.block_no = a.block_no "
                        + "AND s.seat_no = a.seat_no "
                        + "WHERE a.experiment_id = 'REP-1' AND a.block_no = 1 AND s.treatment = 'A'",
                Integer.class);

        clock.advance(3_000L);
        ResponseEntity<String> resp = replace("REP-1", "P1", "RK-1", "N1", "rep1-replace-1");
        assertEquals(201, resp.getStatusCode().value());
        JsonNode body = json(resp);
        assertEquals("RK-1", body.path("replaceKey").asText());
        assertEquals("REP-1", body.path("experimentId").asText());
        assertEquals(1, body.path("blockNo").asInt());
        assertEquals(allocationId, body.path("allocationId").asLong(), "替补不新建分配序号");
        assertEquals("P1", body.path("originalParticipantId").asText());
        assertEquals("N1", body.path("newParticipantId").asText());
        assertEquals(1_700_000_003_000L, body.path("replacedAt").asLong());
        // 盲态隔离：响应不得包含处理代码、席位号与盲码
        assertFalse(body.has("treatment"));
        assertFalse(body.has("seatNo"));
        assertFalse(body.has("blindCode"));

        // 分配行原地转移：同一分配序号、同一席位，处理代码被继承
        assertEquals(allocationId, jdbc.queryForObject(
                "SELECT id FROM allocation WHERE experiment_id = 'REP-1' AND participant_id = 'N1'",
                Long.class));
        assertEquals(originalTreatment, treatmentOf("REP-1", "N1"), "替补参与者继承处理代码");
        assertEquals("ASSIGNED", jdbc.queryForObject(
                "SELECT status FROM allocation WHERE id = " + allocationId, String.class));
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'REP-1'", Integer.class),
                "替补不新增分配行");

        // 配额守恒：可用名额回落，活跃+可用恒等于总席位；各处理代码占位计数不变
        JsonNode quotaAfter = quota("REP-1", 1);
        assertEquals(4, quotaAfter.path("activeParticipants").asLong());
        assertEquals(0, quotaAfter.path("availableSeats").asLong());
        assertQuotaConserved(quotaAfter);
        assertEquals(occupiedABefore, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation a JOIN seat s "
                        + "ON s.experiment_id = a.experiment_id AND s.block_no = a.block_no "
                        + "AND s.seat_no = a.seat_no "
                        + "WHERE a.experiment_id = 'REP-1' AND a.block_no = 1 AND s.treatment = 'A'",
                Integer.class), "区组内各处理代码的配额计数不变");

        // 原参与者转为 REPLACED 终态；替补参与者在组
        ResponseEntity<String> originalView = exchange(
                "/api/experiments/REP-1/participants/P1", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null);
        assertEquals(200, originalView.getStatusCode().value());
        JsonNode originalNode = json(originalView);
        assertEquals("REPLACED", originalNode.path("status").asText());
        assertTrue(originalNode.path("blindCode").isNull(), "REPLACED 视图无盲码");
        assertFalse(originalNode.has("treatment"));
        assertFalse(originalNode.has("seatNo"));
        ResponseEntity<String> newView = exchange(
                "/api/experiments/REP-1/participants/N1", HttpMethod.GET,
                headers("r1", "REVIEWER", null), null);
        assertEquals("ASSIGNED", json(newView).path("status").asText());

        // 区组替补历史：固化原/新参与者、区组、分配序号与时刻，不含盲底
        ResponseEntity<String> history = exchange(
                "/api/experiments/REP-1/blocks/1/replacements", HttpMethod.GET,
                headers("r1", "REVIEWER", null), null);
        assertEquals(200, history.getStatusCode().value());
        JsonNode records = json(history);
        assertEquals(1, records.size());
        JsonNode record = records.get(0);
        assertEquals("RK-1", record.path("replaceKey").asText());
        assertEquals("P1", record.path("originalParticipantId").asText());
        assertEquals("N1", record.path("newParticipantId").asText());
        assertEquals(allocationId, record.path("allocationId").asLong());
        assertEquals(1_700_000_003_000L, record.path("replacedAt").asLong());
        assertFalse(record.has("treatment"));
        assertFalse(record.has("seatNo"));
        assertFalse(record.has("blindCode"));
        // 替补记录不落盲底列
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM replacement WHERE replace_key = 'RK-1' "
                        + "AND (original_participant_id <> 'P1' OR new_participant_id <> 'N1')",
                Integer.class));
    }

    @Test
    void replace_ineligibleParticipants_conflict() throws Exception {
        createExperiment("REP-2", "rep2-create");
        register("REP-2", "P1", "rep2-alloc-1");
        register("REP-2", "P2", "rep2-alloc-2");
        register("REP-2", "P3", "rep2-alloc-3");

        // 未退组：409
        assertEquals(409, replace("REP-2", "P1", "RK-A", "N1", "rep2-rep-assigned")
                .getStatusCode().value());

        // 已退组但已揭盲：409
        ResponseEntity<String> apply = exchange(
                "/api/experiments/REP-2/participants/P2/unblind-requests", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rep2-apply-2"), "{\"reason\":\"合成测试核对\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = json(apply).path("requestId").asText();
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                headers("r1", "REVIEWER", "rep2-approve-2"), null).getStatusCode().value());
        withdraw("REP-2", "P2", "rep2-withdraw-2");
        assertEquals(409, replace("REP-2", "P2", "RK-B", "N2", "rep2-rep-unblinded")
                .getStatusCode().value());

        // 正常替补 P3 -> N3
        withdraw("REP-2", "P3", "rep2-withdraw-3");
        assertEquals(201, replace("REP-2", "P3", "RK-C", "N3", "rep2-rep-ok")
                .getStatusCode().value());

        // 已替补的原参与者再次替补：409
        assertEquals(409, replace("REP-2", "P3", "RK-D", "N4", "rep2-rep-again")
                .getStatusCode().value());

        // 新参与者已存在于区组：409
        withdraw("REP-2", "P1", "rep2-withdraw-1");
        assertEquals(409, replace("REP-2", "P1", "RK-E", "N3", "rep2-rep-dup-new")
                .getStatusCode().value());
        assertEquals(409, replace("REP-2", "P1", "RK-F", "P2", "rep2-rep-dup-existing")
                .getStatusCode().value());

        // 新参与者是曾被替补掉的原参与者（拥有历史分配）：409
        assertEquals(409, replace("REP-2", "P1", "RK-G", "P3", "rep2-rep-dup-replaced")
                .getStatusCode().value());

        // replaceKey 重复（不同新参与者、不同 requestId）：409
        assertEquals(409, replace("REP-2", "P1", "RK-C", "N9", "rep2-rep-dup-key")
                .getStatusCode().value());

        // 原参与者不存在：404
        assertEquals(404, replace("REP-2", "NOPE", "RK-H", "N8", "rep2-rep-404")
                .getStatusCode().value());

        // 缺 replaceKey / newParticipantId：400
        assertEquals(400, exchange(
                "/api/experiments/REP-2/participants/P1/replacement", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rep2-rep-no-key"),
                "{\"newParticipantId\":\"N7\"}").getStatusCode().value());
        assertEquals(400, exchange(
                "/api/experiments/REP-2/participants/P1/replacement", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rep2-rep-no-new"),
                "{\"replaceKey\":\"RK-I\"}").getStatusCode().value());

        // 权限：REVIEWER 403；无身份头 401
        assertEquals(403, exchange(
                "/api/experiments/REP-2/participants/P1/replacement", HttpMethod.POST,
                headers("r1", "REVIEWER", "rep2-rep-reviewer"),
                "{\"replaceKey\":\"RK-J\",\"newParticipantId\":\"N7\"}")
                .getStatusCode().value());
        assertEquals(401, exchange(
                "/api/experiments/REP-2/participants/P1/replacement", HttpMethod.POST,
                headers(null, null, "rep2-rep-anon"),
                "{\"replaceKey\":\"RK-K\",\"newParticipantId\":\"N7\"}")
                .getStatusCode().value());

        // 失败不占业务数据：仅 RK-C 一条替补记录
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM replacement WHERE experiment_id = 'REP-2'", Integer.class));
    }

    @Test
    void replacement_blindIsolation_unblindFlowsStayWithIdentities() throws Exception {
        createExperiment("REP-3", "rep3-create");
        register("REP-3", "P1", "rep3-alloc-1");
        String originalTreatment = treatmentOf("REP-3", "P1");

        // 原参与者有既有待审揭盲申请（未揭盲，不阻断替补）
        ResponseEntity<String> apply = exchange(
                "/api/experiments/REP-3/participants/P1/unblind-requests", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rep3-apply-1"), "{\"reason\":\"合成测试核对\"}");
        assertEquals(201, apply.getStatusCode().value());
        String pendingId = json(apply).path("requestId").asText();

        withdraw("REP-3", "P1", "rep3-withdraw-1");
        assertEquals(201, replace("REP-3", "P1", "RK-3", "N1", "rep3-replace-1")
                .getStatusCode().value());

        // 替补先提交：原参与者的待审申请不得再批准（409），申请记录仍保留并指向原标识
        assertEquals(409, exchange("/api/unblind-requests/" + pendingId + "/approval",
                HttpMethod.POST, headers("r1", "REVIEWER", "rep3-approve-old"), null)
                .getStatusCode().value());
        ResponseEntity<String> kept = exchange("/api/unblind-requests/" + pendingId, HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null);
        assertEquals(200, kept.getStatusCode().value());
        assertEquals("P1", json(kept).path("participantId").asText());
        assertEquals("PENDING", json(kept).path("status").asText());

        // 对原参与者发起新揭盲申请：409（REPLACED 终态）
        assertEquals(409, exchange(
                "/api/experiments/REP-3/participants/P1/unblind-requests", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rep3-apply-replaced"),
                "{\"reason\":\"已被替补\"}").getStatusCode().value());

        // 对原参与者退组：409（终态不可再退）
        assertEquals(409, exchange(
                "/api/experiments/REP-3/participants/P1/withdrawal", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rep3-withdraw-replaced"), null)
                .getStatusCode().value());

        // 原参与者不得以新登记回到实验：409
        assertEquals(409, exchange(
                "/api/experiments/REP-3/participants/P1/allocations", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rep3-alloc-replaced"), null)
                .getStatusCode().value());

        // 替补参与者独立走揭盲流程，批准结果等于继承的处理代码
        ResponseEntity<String> applyNew = exchange(
                "/api/experiments/REP-3/participants/N1/unblind-requests", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rep3-apply-new"), "{\"reason\":\"替补后核对\"}");
        assertEquals(201, applyNew.getStatusCode().value());
        String newUbId = json(applyNew).path("requestId").asText();
        assertEquals(200, exchange("/api/unblind-requests/" + newUbId + "/approval",
                HttpMethod.POST, headers("r1", "REVIEWER", "rep3-approve-new"), null)
                .getStatusCode().value());
        ResponseEntity<String> result = exchange(
                "/api/unblind-requests/" + newUbId + "/result", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null);
        assertEquals(200, result.getStatusCode().value());
        assertEquals(originalTreatment, json(result).path("treatment").asText(),
                "替补参与者揭盲结果应为继承的处理代码");

        // 替补参与者独立退组
        assertEquals(200, exchange(
                "/api/experiments/REP-3/participants/N1/withdrawal", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rep3-withdraw-new"), null)
                .getStatusCode().value());
    }

    @Test
    void replacement_idempotency_replayAndFailureNotConsumingKey() throws Exception {
        createExperiment("REP-4", "rep4-create");
        register("REP-4", "P1", "rep4-alloc-1");

        // 失败不占键：未退组替补 409 后，同键换参（异参）也不应命中已占键
        assertEquals(409, replace("REP-4", "P1", "RK-4", "N1", "rep4-replace-1")
                .getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'rep4-replace-1'",
                Integer.class));

        // 退组后同一 requestId 同参成功
        withdraw("REP-4", "P1", "rep4-withdraw-1");
        ResponseEntity<String> first = replace("REP-4", "P1", "RK-4", "N1", "rep4-replace-1");
        assertEquals(201, first.getStatusCode().value());

        // 同键同参重放：原样返回，不产生第二条替补记录
        ResponseEntity<String> replay = replace("REP-4", "P1", "RK-4", "N1", "rep4-replace-1");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM replacement WHERE experiment_id = 'REP-4'", Integer.class));

        // 同键异参：409
        assertEquals(409, replace("REP-4", "P1", "RK-4", "N2", "rep4-replace-1")
                .getStatusCode().value());

        // 权限先于幂等：REVIEWER 持已成功的 requestId 仍 403
        assertEquals(403, exchange(
                "/api/experiments/REP-4/participants/P1/replacement", HttpMethod.POST,
                headers("r1", "REVIEWER", "rep4-replace-1"),
                "{\"replaceKey\":\"RK-4\",\"newParticipantId\":\"N1\"}")
                .getStatusCode().value());
    }

    @Test
    void blockQueries_unknownExperimentOrBlock_notFound() {
        createExperiment("REP-5", "rep5-create");
        assertEquals(404, exchange("/api/experiments/NOPE/blocks/1/quota", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null).getStatusCode().value());
        assertEquals(404, exchange("/api/experiments/REP-5/blocks/3/quota", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null).getStatusCode().value());
        assertEquals(404, exchange("/api/experiments/REP-5/blocks/0/replacements", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null).getStatusCode().value());
    }
}
