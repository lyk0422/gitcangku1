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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 盲法协议修订与区组/中心序列版本隔离：
 * 初始版本、修订创建校验、到期原子生效、中心剩余容量预留、序列发放、
 * 撤销、暂停恢复版本选择、既有盲码/揭盲永久归属旧版本，以及失败不留半成品。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProtocolAmendmentFlowTest extends AbstractBlindIntegrationTest {

    static final long BASE = 1_700_000_000_000L;

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpHeaders headers(String actor, String role, String requestId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Actor-Id", actor);
        h.set("X-Role", role);
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

    private void createExperiment(String expId, String prefix) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("coord-1", "COORDINATOR", prefix + "-create"),
                "{\"blockCount\":2}").getStatusCode().value());
    }

    private void createCenter(String expId, String centerId, int cap, String reqId) {
        assertEquals(201, exchange("/api/experiments/" + expId + "/centers/" + centerId,
                HttpMethod.POST, headers("coord-1", "COORDINATOR", reqId),
                "{\"targetCap\":" + cap + "}").getStatusCode().value());
    }

    private JsonNode createAmendment(String expId, int a, int b, long effectiveAt, String reqId)
            throws Exception {
        ResponseEntity<String> resp = exchange("/api/experiments/" + expId + "/protocol-versions",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", reqId),
                "{\"ratioA\":" + a + ",\"ratioB\":" + b + ",\"effectiveAt\":" + effectiveAt + "}");
        return json(resp);
    }

    private long sequenceCount(String expId, String centerId, Integer version, String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM center_code_sequence WHERE ")
                .append("experiment_id = '").append(expId).append("'");
        if (centerId != null) {
            sql.append(" AND center_id = '").append(centerId).append("'");
        }
        if (version != null) {
            sql.append(" AND version_no = ").append(version);
        }
        if (status != null) {
            sql.append(" AND status = '").append(status).append("'");
        }
        return jdbc.queryForObject(sql.toString(), Long.class);
    }

    @Test
    void experimentBootstrapsInitialVersion_andCenterReservesIndependentSequences() throws Exception {
        createExperiment("PA-1", "pa1");

        JsonNode versions = json(exchange("/api/experiments/PA-1/protocol-versions",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null));
        assertTrue(versions.isArray());
        assertEquals(1, versions.size());
        JsonNode v1 = versions.get(0);
        assertEquals(1, v1.path("versionNo").asInt());
        assertEquals(50, v1.path("ratioA").asInt());
        assertEquals(50, v1.path("ratioB").asInt());
        assertEquals("ACTIVE", v1.path("status").asText());
        assertEquals(BASE, v1.path("effectiveAt").asLong());
        assertEquals(BASE, v1.path("effectiveEventAt").asLong());
        assertNull(v1.path("revokedAt").asText(null));

        // 激活中心，容量 10：为 v1 预留 10 条独立序列
        ResponseEntity<String> centerResp = exchange("/api/experiments/PA-1/centers/C1",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "pa1-center"),
                "{\"targetCap\":10}");
        assertEquals(201, centerResp.getStatusCode().value());
        JsonNode center = json(centerResp);
        assertEquals("C1", center.path("centerId").asText());
        assertEquals(10, center.path("targetCap").asInt());
        assertEquals(0, center.path("allocated").asLong());
        assertEquals(10, center.path("remaining").asLong());
        assertEquals("ACTIVE", center.path("status").asText());
        assertEquals(10, sequenceCount("PA-1", "C1", 1, "RESERVED"));

        // 序列仅存于库内，含处理映射，普通接口不暴露
        List<String> exposed = jdbc.queryForList(
                "SELECT blind_code FROM center_code_sequence WHERE experiment_id = 'PA-1'",
                String.class);
        assertEquals(10, exposed.size());
        assertEquals(10, new HashSet<>(exposed).size(), "序列盲码互不相同");

        // 登记第一名受试者：领取 seq_no=1，归属 v1，视图不含 treatment/seatNo
        ResponseEntity<String> alloc = exchange(
                "/api/experiments/PA-1/centers/C1/participants/S1/allocations", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa1-alloc-1"), null);
        assertEquals(201, alloc.getStatusCode().value());
        JsonNode allocBody = json(alloc);
        assertEquals("S1", allocBody.path("participantId").asText());
        assertEquals("C1", allocBody.path("centerId").asText());
        assertEquals(1, allocBody.path("versionNo").asInt());
        assertTrue(allocBody.path("blockNo").isNull());
        assertFalse(allocBody.has("treatment"));
        assertFalse(allocBody.has("seatNo"));

        // 库内：seq 1 已发放并绑定分配，处理代码已固化到分配
        assertEquals(1, sequenceCount("PA-1", "C1", 1, "ISSUED"));
        assertEquals(9, sequenceCount("PA-1", "C1", 1, "RESERVED"));
        Map<String, Object> seqRow = jdbc.queryForMap(
                "SELECT s.blind_code, s.treatment, s.allocation_id, a.treatment AS alloc_t, "
                        + "a.version_no, a.center_id FROM center_code_sequence s "
                        + "JOIN allocation a ON a.id = s.allocation_id "
                        + "WHERE s.experiment_id = 'PA-1' AND s.seq_no = 1");
        assertNotNull(seqRow.get("allocation_id"));
        assertEquals(seqRow.get("treatment"), seqRow.get("alloc_t"),
                "发放时处理代码固化到分配，版本隔离依据");

        // 中心视图剩余容量随分配递减
        JsonNode centerView = json(exchange("/api/experiments/PA-1/centers/C1", HttpMethod.GET,
                headers("rev-9", "REVIEWER", null), null));
        assertEquals(1, centerView.path("allocated").asLong());
        assertEquals(9, centerView.path("remaining").asLong());

        // 查询受试者归属
        JsonNode attribution = json(exchange(
                "/api/experiments/PA-1/participants/S1", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null));
        assertEquals("C1", attribution.path("centerId").asText());
        assertEquals(1, attribution.path("versionNo").asInt());
        assertTrue(attribution.path("blockNo").isNull());
    }

    @Test
    void amendmentValidation_ratiosAndEffectiveTime() throws Exception {
        createExperiment("PA-2", "pa2");
        createCenter("PA-2", "C1", 5, "pa2-center");

        // 比例和不为 100：400
        ResponseEntity<String> badSum = exchange("/api/experiments/PA-2/protocol-versions",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "pa2-bad-sum"),
                "{\"ratioA\":60,\"ratioB\":30,\"effectiveAt\":" + (BASE + 10_000) + "}");
        assertEquals(400, badSum.getStatusCode().value());

        // 比例为 0：400（Bean Validation）
        ResponseEntity<String> zero = exchange("/api/experiments/PA-2/protocol-versions",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "pa2-zero"),
                "{\"ratioA\":0,\"ratioB\":100,\"effectiveAt\":" + (BASE + 10_000) + "}");
        assertEquals(400, zero.getStatusCode().value());

        // 生效时刻早于当前：422
        ResponseEntity<String> past = exchange("/api/experiments/PA-2/protocol-versions",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "pa2-past"),
                "{\"ratioA\":70,\"ratioB\":30,\"effectiveAt\":" + (BASE - 1) + "}");
        assertEquals(422, past.getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'pa2-past'",
                Integer.class), "失败不占幂等键");

        // 合法修订：201 PENDING
        JsonNode v2 = createAmendment("PA-2", 70, 30, BASE + 10_000, "pa2-v2");
        assertEquals(2, v2.path("versionNo").asInt());
        assertEquals("PENDING", v2.path("status").asText());
        assertEquals("coord-1", v2.path("createdByActor").asText());

        // 只允许一条最终待生效版本
        ResponseEntity<String> second = exchange("/api/experiments/PA-2/protocol-versions",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "pa2-v3"),
                "{\"ratioA\":80,\"ratioB\":20,\"effectiveAt\":" + (BASE + 20_000) + "}");
        assertEquals(409, second.getStatusCode().value());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM protocol_version WHERE experiment_id = 'PA-2'",
                Integer.class));
    }

    @Test
    void effectuation_reservesRemainingCapacityPerActiveCenter_andOnlyAffectsNewSubjects()
            throws Exception {
        createExperiment("PA-3", "pa3");
        createCenter("PA-3", "C1", 100, "pa3-c1");
        // 生效前在 v1 登记 10 人
        for (int i = 1; i <= 10; i++) {
            assertEquals(201, exchange(
                    "/api/experiments/PA-3/centers/C1/participants/B" + i + "/allocations",
                    HttpMethod.POST, headers("coord-1", "COORDINATOR", "pa3-b" + i), null)
                    .getStatusCode().value());
        }
        assertEquals(10, sequenceCount("PA-3", "C1", 1, "ISSUED"));

        // v2 比例 70:30，10 秒后生效
        JsonNode v2 = createAmendment("PA-3", 70, 30, BASE + 10_000, "pa3-v2");
        assertEquals("PENDING", v2.path("status").asText());

        // 生效时刻之前的登记仍归属 v1
        clock.setTime(BASE + 5_000);
        assertEquals(1, json(exchange(
                "/api/experiments/PA-3/centers/C1/participants/B11/allocations", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa3-b11"), null))
                .path("versionNo").asInt());

        // 到生效时刻后的首次写操作（新中心激活）原子触发裁决
        clock.setTime(BASE + 10_000);
        createCenter("PA-3", "C2", 100, "pa3-c2");

        // v2 已生效；C1 剩余容量 = 100-11 = 89 条 v2 序列
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM protocol_version WHERE experiment_id = 'PA-3' AND version_no = 2",
                String.class));
        assertEquals(89, sequenceCount("PA-3", "C1", 2, "RESERVED"));
        assertEquals(0, sequenceCount("PA-3", "C1", 2, "ISSUED"));
        // 新中心 C2 在生效后激活：只有 v2 序列，无 v1 序列
        assertEquals(0, sequenceCount("PA-3", "C2", 1, null));
        assertEquals(100, sequenceCount("PA-3", "C2", 2, "RESERVED"));

        // v2 登记归属版本 2；既有受试者仍归属版本 1
        JsonNode newAlloc = json(exchange(
                "/api/experiments/PA-3/centers/C1/participants/B12/allocations", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa3-b12"), null));
        assertEquals(2, newAlloc.path("versionNo").asInt());
        assertEquals(1, json(exchange("/api/experiments/PA-3/participants/B1", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null)).path("versionNo").asInt());

        // 旧受试者揭盲：结果取自其 v1 序列固化处理代码，v2 生效不改变归属
        String oldTreatment = jdbc.queryForObject(
                "SELECT treatment FROM allocation WHERE experiment_id = 'PA-3' "
                        + "AND participant_id = 'B1'", String.class);
        ResponseEntity<String> apply = exchange(
                "/api/experiments/PA-3/participants/B1/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa3-ub-apply"),
                "{\"reason\":\"核对旧版本归属\"}");
        String ubId = json(apply).path("requestId").asText();
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "pa3-ub-approve"), null).getStatusCode().value());
        JsonNode result = json(exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null));
        assertEquals(oldTreatment, result.path("treatment").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT version_no FROM allocation WHERE participant_id = 'B1'", Integer.class));

        // 中心序列计数查询
        JsonNode summaries = json(exchange("/api/experiments/PA-3/center-sequences", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null));
        Map<String, JsonNode> byKey = new HashMap<>();
        summaries.forEach(s -> byKey.put(s.path("centerId").asText() + "#"
                + s.path("versionNo").asInt(), s));
        assertEquals(11, byKey.get("C1#1").path("issued").asLong());
        assertEquals(89, byKey.get("C1#1").path("reserved").asLong());
        assertEquals(1, byKey.get("C1#2").path("issued").asLong());
        assertEquals(88, byKey.get("C1#2").path("reserved").asLong());
        assertEquals(100, byKey.get("C2#2").path("reserved").asLong());
    }

    @Test
    void effectuationFails_whenAnyActiveCenterFull_orPendingUnblind_rollsBackEverything()
            throws Exception {
        createExperiment("PA-4", "pa4");
        createCenter("PA-4", "FULL", 2, "pa4-full");
        createCenter("PA-4", "OKC", 5, "pa4-okc");
        // FULL 中心登记到满
        assertEquals(201, exchange("/api/experiments/PA-4/centers/FULL/participants/F1/allocations",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "pa4-f1"), null)
                .getStatusCode().value());
        assertEquals(201, exchange("/api/experiments/PA-4/centers/FULL/participants/F2/allocations",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "pa4-f2"), null)
                .getStatusCode().value());

        createAmendment("PA-4", 70, 30, BASE + 10_000, "pa4-v2");
        clock.setTime(BASE + 10_000);
        // 到期后任意写操作触发裁决：FULL 剩余容量 0 -> 422
        ResponseEntity<String> trigger = exchange(
                "/api/experiments/PA-4/participants/L1/allocations", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa4-trigger"), null);
        assertEquals(422, trigger.getStatusCode().value());

        // 版本仍 PENDING；两个中心都没有 v2 序列（OKC 也不得半成品预留）
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM protocol_version WHERE experiment_id = 'PA-4' AND version_no = 2",
                String.class));
        assertEquals(0, sequenceCount("PA-4", null, 2, null));
        // 既有 v1 序列与分配不变
        assertEquals(2, sequenceCount("PA-4", "FULL", 1, "ISSUED"));
        assertEquals(5, sequenceCount("PA-4", "OKC", 1, "RESERVED"));

        // 待处理揭盲申请同样阻断：新实验，留有 PENDING 揭盲
        clock.setTime(BASE);
        createExperiment("PA-5", "pa5");
        createCenter("PA-5", "C1", 5, "pa5-c1");
        assertEquals(201, exchange("/api/experiments/PA-5/centers/C1/participants/U1/allocations",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "pa5-u1-alloc"), null)
                .getStatusCode().value());
        assertEquals(201, exchange("/api/experiments/PA-5/participants/U1/unblind-requests",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "pa5-ub"),
                "{\"reason\":\"待处理中\"}").getStatusCode().value());
        createAmendment("PA-5", 60, 40, BASE + 10_000, "pa5-v2");
        clock.setTime(BASE + 10_000);
        ResponseEntity<String> blocked = exchange(
                "/api/experiments/PA-5/centers/C1/participants/U2/allocations", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa5-blocked"), null);
        assertEquals(422, blocked.getStatusCode().value());
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM protocol_version WHERE experiment_id = 'PA-5' AND version_no = 2",
                String.class));
        assertEquals(0, sequenceCount("PA-5", "C1", 2, null));

        // 揭盲批准后再触发：生效成功，按剩余容量 4 预留
        String ubId = jdbc.queryForObject(
                "SELECT id FROM unblind_request WHERE experiment_id = 'PA-5'", String.class);
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "pa5-approve"), null).getStatusCode().value());
        ResponseEntity<String> after = exchange(
                "/api/experiments/PA-5/centers/C1/participants/U2/allocations", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa5-after"), null);
        assertEquals(201, after.getStatusCode().value());
        assertEquals(2, json(after).path("versionNo").asInt());
        assertEquals(4, sequenceCount("PA-5", "C1", 2, null));
    }

    @Test
    void revokePendingKeepsRecord_effectiveCannotRevoke() throws Exception {
        createExperiment("PA-6", "pa6");
        createCenter("PA-6", "C1", 5, "pa6-c1");
        createAmendment("PA-6", 70, 30, BASE + 10_000, "pa6-v2");

        // 撤销未生效版本：200，状态 REVOKED，记录保留
        ResponseEntity<String> revoked = exchange(
                "/api/experiments/PA-6/protocol-versions/2/revocation", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa6-revoke"), null);
        assertEquals(200, revoked.getStatusCode().value());
        assertEquals("REVOKED", json(revoked).path("status").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM protocol_version WHERE experiment_id = 'PA-6' "
                        + "AND version_no = 2 AND status = 'REVOKED'", Integer.class));

        // 重复撤销 409
        assertEquals(409, exchange(
                "/api/experiments/PA-6/protocol-versions/2/revocation", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa6-revoke-again"), null)
                .getStatusCode().value());

        // 撤销后可再建修订（去重占位已释放），立即生效
        JsonNode v3 = createAmendment("PA-6", 80, 20, BASE, "pa6-v3");
        assertEquals(3, v3.path("versionNo").asInt());
        assertEquals("ACTIVE", v3.path("status").asText());

        // 已生效版本不可撤销（v1 与 v3）
        assertEquals(409, exchange(
                "/api/experiments/PA-6/protocol-versions/3/revocation", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa6-revoke-v3"), null)
                .getStatusCode().value());
        assertEquals(409, exchange(
                "/api/experiments/PA-6/protocol-versions/1/revocation", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa6-revoke-v1"), null)
                .getStatusCode().value());
        assertEquals(404, exchange(
                "/api/experiments/PA-6/protocol-versions/9/revocation", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa6-revoke-missing"), null)
                .getStatusCode().value());
    }

    @Test
    void suspendAndResume_excludesFromEffectuation_resumeUsesEffectiveVersion() throws Exception {
        createExperiment("PA-7", "pa7");
        createCenter("PA-7", "C1", 10, "pa7-c1");

        // 暂停：重复暂停 409，暂停期间登记 422
        assertEquals(200, exchange("/api/experiments/PA-7/centers/C1/suspension", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa7-suspend"), null).getStatusCode().value());
        assertEquals(409, exchange("/api/experiments/PA-7/centers/C1/suspension", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa7-suspend-2"), null).getStatusCode().value());
        ResponseEntity<String> suspendedAlloc = exchange(
                "/api/experiments/PA-7/centers/C1/participants/S1/allocations", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa7-s-alloc"), null);
        assertEquals(422, suspendedAlloc.getStatusCode().value());

        createAmendment("PA-7", 60, 40, BASE + 10_000, "pa7-v2");
        clock.setTime(BASE + 10_000);
        // 实验级区组登记触发裁决：无 ACTIVE 中心，版本正常生效且不为暂停中心预留
        assertEquals(201, exchange("/api/experiments/PA-7/participants/L1/allocations",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "pa7-l1"), null)
                .getStatusCode().value());
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM protocol_version WHERE experiment_id = 'PA-7' AND version_no = 2",
                String.class));
        assertEquals(0, sequenceCount("PA-7", "C1", 2, null), "暂停中心不参与生效预留");

        // 恢复：按当时有效版本 v2 补足剩余容量 10 条；重复恢复 409
        ResponseEntity<String> resumed = exchange(
                "/api/experiments/PA-7/centers/C1/resumption", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa7-resume"), null);
        assertEquals(200, resumed.getStatusCode().value());
        assertEquals("ACTIVE", json(resumed).path("status").asText());
        assertEquals(10, sequenceCount("PA-7", "C1", 2, "RESERVED"));
        assertEquals(409, exchange("/api/experiments/PA-7/centers/C1/resumption", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa7-resume-2"), null).getStatusCode().value());

        // 恢复后登记使用 v2
        JsonNode alloc = json(exchange(
                "/api/experiments/PA-7/centers/C1/participants/S1/allocations", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa7-s1-alloc"), null));
        assertEquals(2, alloc.path("versionNo").asInt());
        assertEquals(1, sequenceCount("PA-7", "C1", 2, "ISSUED"));
    }

    @Test
    void withdrawalDoesNotReleaseCenterCapacity() throws Exception {
        createExperiment("PA-8", "pa8");
        createCenter("PA-8", "C1", 3, "pa8-c1");
        for (int i = 1; i <= 3; i++) {
            assertEquals(201, exchange(
                    "/api/experiments/PA-8/centers/C1/participants/W" + i + "/allocations",
                    HttpMethod.POST, headers("coord-1", "COORDINATOR", "pa8-w" + i), null)
                    .getStatusCode().value());
        }
        // 退组第 1 人：序列不回收
        assertEquals(200, exchange("/api/experiments/PA-8/participants/W1/withdrawal",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "pa8-withdraw"), null)
                .getStatusCode().value());
        assertEquals(3, sequenceCount("PA-8", "C1", 1, "ISSUED"));
        assertEquals(0, sequenceCount("PA-8", "C1", 1, "RESERVED"));
        assertEquals(422, exchange(
                "/api/experiments/PA-8/centers/C1/participants/W4/allocations", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa8-w4"), null).getStatusCode().value());
        JsonNode view = json(exchange("/api/experiments/PA-8/centers/C1", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null));
        assertEquals(3, view.path("allocated").asLong(), "累计已分配含退组");
        assertEquals(0, view.path("remaining").asLong());
    }

    @Test
    void amendmentAndCenterWrites_areIdempotent_failuresDoNotConsumeKey() throws Exception {
        createExperiment("PA-9", "pa9");
        createCenter("PA-9", "C1", 5, "pa9-c1");

        // 非法修订失败不占键：同一 requestId 换成合法参数成功
        ResponseEntity<String> bad = exchange("/api/experiments/PA-9/protocol-versions",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "pa9-amend-key"),
                "{\"ratioA\":10,\"ratioB\":20,\"effectiveAt\":" + (BASE + 10_000) + "}");
        assertEquals(400, bad.getStatusCode().value());
        ResponseEntity<String> good = exchange("/api/experiments/PA-9/protocol-versions",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "pa9-amend-key"),
                "{\"ratioA\":70,\"ratioB\":30,\"effectiveAt\":" + (BASE + 10_000) + "}");
        assertEquals(201, good.getStatusCode().value());

        // 同键同参重放：原结果，仅一条 v2
        ResponseEntity<String> replay = exchange("/api/experiments/PA-9/protocol-versions",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "pa9-amend-key"),
                "{\"ratioA\":70,\"ratioB\":30,\"effectiveAt\":" + (BASE + 10_000) + "}");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(good.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM protocol_version WHERE experiment_id = 'PA-9' AND version_no = 2",
                Integer.class));

        // 同键异操作者：409
        assertEquals(409, exchange("/api/experiments/PA-9/protocol-versions",
                HttpMethod.POST, headers("coord-2", "COORDINATOR", "pa9-amend-key"),
                "{\"ratioA\":70,\"ratioB\":30,\"effectiveAt\":" + (BASE + 10_000) + "}")
                .getStatusCode().value());

        // 中心登记同键重放：同一条分配
        ResponseEntity<String> a1 = exchange(
                "/api/experiments/PA-9/centers/C1/participants/P1/allocations", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa9-alloc-key"), null);
        ResponseEntity<String> a2 = exchange(
                "/api/experiments/PA-9/centers/C1/participants/P1/allocations", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "pa9-alloc-key"), null);
        assertEquals(201, a1.getStatusCode().value());
        assertEquals(a1.getBody(), a2.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'PA-9' AND center_id = 'C1'",
                Integer.class));
    }

    @Test
    void newVersionRatioProducesExactBlockComposition() throws Exception {
        // 容量 100 + 比例 70:30：完整区组恰好 70 A / 30 B
        createExperiment("PA-10", "pa10");
        createCenter("PA-10", "C1", 100, "pa10-c1");
        createAmendment("PA-10", 70, 30, BASE + 10_000, "pa10-v2");
        clock.setTime(BASE + 10_000);
        // 实验级登记触发生效
        assertEquals(201, exchange("/api/experiments/PA-10/participants/L1/allocations",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "pa10-l1"), null)
                .getStatusCode().value());
        assertEquals(70, jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_code_sequence WHERE experiment_id = 'PA-10' "
                        + "AND version_no = 2 AND treatment = 'A'", Long.class));
        assertEquals(30, jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_code_sequence WHERE experiment_id = 'PA-10' "
                        + "AND version_no = 2 AND treatment = 'B'", Long.class));
        // 序列盲码全局唯一且与全局 allocation 盲码不冲突域：抽样校验格式
        List<String> codes = jdbc.queryForList(
                "SELECT blind_code FROM center_code_sequence WHERE experiment_id = 'PA-10'",
                String.class);
        Set<String> unique = new HashSet<>(codes);
        assertEquals(codes.size(), unique.size());
        for (String code : codes) {
            assertTrue(code.matches("[A-Z2-9]{12}"), "盲码格式不符: " + code);
        }
    }
}
