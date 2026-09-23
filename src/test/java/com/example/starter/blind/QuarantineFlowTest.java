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

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合规隔离单主流程与失败分支（真实 H2）：
 * 提交当前闭包及版本发起、另一名不在闭包内的 COMPLIANCE 确认冻结快照、
 * 冻结不删边、新增披露生成新版本重新 OPEN、隔离历史查询、过期/不一致/同人/闭包内确认拒绝。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QuarantineFlowTest extends AbstractBlindIntegrationTest {

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
        assertTrue(response.getBody() != null && !response.getBody().isBlank());
        return mapper.readTree(response.getBody());
    }

    private List<String> actors(JsonNode node, String field) {
        return Arrays.asList(mapper.convertValue(node.withArray(field), String[].class));
    }

    private void baselineContamination(String expId, String prefix) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("coord-1", "COORDINATOR", prefix + "-create"),
                "{\"blockCount\":2}").getStatusCode().value());
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/PA/allocations",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", prefix + "-alloc"), null)
                .getStatusCode().value());
        ResponseEntity<String> apply = exchange(
                "/api/experiments/" + expId + "/participants/PA/unblind-requests",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", prefix + "-apply"),
                "{\"reason\":\"紧急\"}");
        try {
            String ubId = json(apply).path("requestId").asText();
            assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval",
                    HttpMethod.POST, headers("rev-2", "REVIEWER", prefix + "-approve"), null)
                    .getStatusCode().value());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/PA/disclosures",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", prefix + "-disc"),
                "{\"exposureKey\":\"" + prefix + "-EX\",\"targetActorIds\":[\"op-a\"]}")
                .getStatusCode().value());
    }

    @Test
    void initiateConfirmFreeze_newDisclosureReopensVersion_andHistoryAvailable() throws Exception {
        baselineContamination("Q-1", "q1");
        List<String> closureAtV1 = List.of("coord-1", "op-a");

        // 发起隔离：提交版本 1 与完整闭包。
        ResponseEntity<String> initiate = exchange(
                "/api/experiments/Q-1/participants/PA/quarantine-orders", HttpMethod.POST,
                headers("comp-1", "COMPLIANCE", "q1-init"),
                "{\"versionNo\":1,\"actors\":[\"coord-1\",\"op-a\"]}");
        assertEquals(201, initiate.getStatusCode().value());
        JsonNode order = json(initiate);
        String orderId = order.path("orderId").asText();
        assertEquals("OPEN", order.path("status").asText());
        assertEquals(1, order.path("versionNo").asInt());
        assertEquals("comp-1", order.path("initiatorActor").asText());
        assertEquals(closureAtV1, actors(order, "snapshotActors"));
        assertFalse(initiate.getBody().contains("treatment"));

        // 发起人自己确认：403。
        assertEquals(403, exchange("/api/quarantine-orders/" + orderId + "/confirmation",
                HttpMethod.POST, headers("comp-1", "COMPLIANCE", "q1-confirm-self"), null)
                .getStatusCode().value());

        // 非 COMPLIANCE 角色确认：403。
        assertEquals(403, exchange("/api/quarantine-orders/" + orderId + "/confirmation",
                HttpMethod.POST, headers("comp-x", "COORDINATOR", "q1-confirm-coord"), null)
                .getStatusCode().value());

        // 另一名不在闭包内的 COMPLIANCE 确认：200，版本 v1 冻结。
        clock.advance(5000L);
        ResponseEntity<String> confirmed = exchange(
                "/api/quarantine-orders/" + orderId + "/confirmation", HttpMethod.POST,
                headers("comp-2", "COMPLIANCE", "q1-confirm"), null);
        assertEquals(200, confirmed.getStatusCode().value());
        JsonNode confirmedBody = json(confirmed);
        assertEquals("CONFIRMED", confirmedBody.path("status").asText());
        assertEquals("comp-2", confirmedBody.path("confirmerActor").asText());
        assertEquals(1_700_000_005_000L, confirmedBody.path("confirmedAt").asLong());

        // 版本 v1 状态 CLOSED；边仍保留。
        JsonNode v1 = json(exchange(
                "/api/experiments/Q-1/participants/PA/contamination/versions/1",
                HttpMethod.GET, headers("comp-1", "COMPLIANCE", null), null));
        assertEquals("CLOSED", v1.path("status").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_edge WHERE experiment_id = 'Q-1'", Integer.class),
                "冻结只冻结快照，不删除边");

        // 重复确认：409。
        assertEquals(409, exchange("/api/quarantine-orders/" + orderId + "/confirmation",
                HttpMethod.POST, headers("comp-3", "COMPLIANCE", "q1-confirm-again"), null)
                .getStatusCode().value());

        // 冻结后不能就同一 CLOSED 版本再次发起：409。
        assertEquals(409, exchange(
                "/api/experiments/Q-1/participants/PA/quarantine-orders", HttpMethod.POST,
                headers("comp-1", "COMPLIANCE", "q1-init-again-closed"),
                "{\"versionNo\":1,\"actors\":[\"coord-1\",\"op-a\"]}")
                .getStatusCode().value());

        // 新增披露：生成 v2 并重新 OPEN。
        ResponseEntity<String> newDisc = exchange(
                "/api/experiments/Q-1/participants/PA/disclosures", HttpMethod.POST,
                headers("op-a", "REVIEWER", "q1-disc-2"),
                "{\"exposureKey\":\"q1-EX-2\",\"targetActorIds\":[\"op-b\"]}");
        assertEquals(201, newDisc.getStatusCode().value());
        assertEquals(2, json(newDisc).path("closureVersion").asInt());
        JsonNode closureNow = json(exchange(
                "/api/experiments/Q-1/participants/PA/contamination/closure",
                HttpMethod.GET, headers("comp-2", "COMPLIANCE", null), null));
        assertEquals(2, closureNow.path("currentVersion").asInt());
        assertEquals("OPEN", closureNow.path("versionStatus").asText());
        assertEquals(List.of("coord-1", "op-a", "op-b"),
                actors(closureNow, "contaminatedActors"));

        // 历史查询：两张单（发起第二个 OPEN 单并确认）。
        ResponseEntity<String> initiate2 = exchange(
                "/api/experiments/Q-1/participants/PA/quarantine-orders", HttpMethod.POST,
                headers("comp-2", "COMPLIANCE", "q1-init-2"),
                "{\"versionNo\":2,\"actors\":[\"coord-1\",\"op-a\",\"op-b\"]}");
        assertEquals(201, initiate2.getStatusCode().value());
        String orderId2 = json(initiate2).path("orderId").asText();
        assertEquals(200, exchange("/api/quarantine-orders/" + orderId2 + "/confirmation",
                HttpMethod.POST, headers("comp-1", "COMPLIANCE", "q1-confirm-2"), null)
                .getStatusCode().value());

        ResponseEntity<String> history = exchange(
                "/api/experiments/Q-1/participants/PA/quarantine-orders",
                HttpMethod.GET, headers("comp-1", "COMPLIANCE", null), null);
        assertEquals(200, history.getStatusCode().value());
        JsonNode historyArray = json(history);
        assertEquals(2, historyArray.size());
        assertEquals("CONFIRMED", historyArray.get(0).path("status").asText());
        assertEquals("CONFIRMED", historyArray.get(1).path("status").asText());
        assertEquals(1, historyArray.get(0).path("versionNo").asInt());
        assertEquals(2, historyArray.get(1).path("versionNo").asInt());
        assertFalse(history.getBody().contains("treatment"));

        // 单隔离单查询。
        assertEquals(200, exchange("/api/quarantine-orders/" + orderId,
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null)
                .getStatusCode().value());
        assertEquals(404, exchange("/api/quarantine-orders/Q-MISSING",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null)
                .getStatusCode().value());
    }

    @Test
    void initiate_rejectsStaleOrMismatchedClosure() {
        baselineContamination("Q-2", "q2");

        // 过期版本号：409。
        assertEquals(409, exchange(
                "/api/experiments/Q-2/participants/PA/quarantine-orders", HttpMethod.POST,
                headers("comp-1", "COMPLIANCE", "q2-stale"),
                "{\"versionNo\":99,\"actors\":[\"coord-1\",\"op-a\"]}")
                .getStatusCode().value());

        // 版本号当前但闭包集合不完整：409。
        assertEquals(409, exchange(
                "/api/experiments/Q-2/participants/PA/quarantine-orders", HttpMethod.POST,
                headers("comp-1", "COMPLIANCE", "q2-partial"),
                "{\"versionNo\":1,\"actors\":[\"coord-1\"]}")
                .getStatusCode().value());

        // 闭包多出未知操作者：409。
        assertEquals(409, exchange(
                "/api/experiments/Q-2/participants/PA/quarantine-orders", HttpMethod.POST,
                headers("comp-1", "COMPLIANCE", "q2-extra"),
                "{\"versionNo\":1,\"actors\":[\"coord-1\",\"op-a\",\"intruder\"]}")
                .getStatusCode().value());

        // actors 缺失：400。
        assertEquals(400, exchange(
                "/api/experiments/Q-2/participants/PA/quarantine-orders", HttpMethod.POST,
                headers("comp-1", "COMPLIANCE", "q2-noactors"),
                "{\"versionNo\":1}").getStatusCode().value());

        // 非 COMPLIANCE 发起：403。
        assertEquals(403, exchange(
                "/api/experiments/Q-2/participants/PA/quarantine-orders", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "q2-coord"),
                "{\"versionNo\":1,\"actors\":[\"coord-1\",\"op-a\"]}")
                .getStatusCode().value());

        // 无隔离单产生。
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM quarantine_order WHERE experiment_id = 'Q-2'",
                Integer.class));
    }

    @Test
    void confirmerInsideClosure_isRejected_andNewDisclosureDuringOpenOrderDoesNotBlockSnapshot()
            throws Exception {
        baselineContamination("Q-3", "q3");

        // 发起 OPEN 隔离单。
        ResponseEntity<String> initiate = exchange(
                "/api/experiments/Q-3/participants/PA/quarantine-orders", HttpMethod.POST,
                headers("comp-1", "COMPLIANCE", "q3-init"),
                "{\"versionNo\":1,\"actors\":[\"coord-1\",\"op-a\"]}");
        assertEquals(201, initiate.getStatusCode().value());
        String orderId = json(initiate).path("orderId").asText();

        // 先让 comp-dirty 进入闭包：新增披露（产生 v2 OPEN，v1 尚未冻结）。
        assertEquals(201, exchange(
                "/api/experiments/Q-3/participants/PA/disclosures", HttpMethod.POST,
                headers("op-a", "REVIEWER", "q3-disc-2"),
                "{\"exposureKey\":\"q3-EX-2\",\"targetActorIds\":[\"comp-dirty\"]}")
                .getStatusCode().value());

        // 闭包内的 comp-dirty 不能确认：403。
        ResponseEntity<String> dirty = exchange(
                "/api/quarantine-orders/" + orderId + "/confirmation", HttpMethod.POST,
                headers("comp-dirty", "COMPLIANCE", "q3-confirm-dirty"), null);
        assertEquals(403, dirty.getStatusCode().value());

        // 不在闭包的 comp-2 仍可确认；冻结的是发起时提交的 v1 快照（边不删）。
        assertEquals(200, exchange("/api/quarantine-orders/" + orderId + "/confirmation",
                HttpMethod.POST, headers("comp-2", "COMPLIANCE", "q3-confirm"), null)
                .getStatusCode().value());
        assertEquals("CLOSED", json(exchange(
                "/api/experiments/Q-3/participants/PA/contamination/versions/1",
                HttpMethod.GET, headers("comp-2", "COMPLIANCE", null), null))
                .path("status").asText());
        // 当前版本为 v2 OPEN。
        JsonNode current = json(exchange(
                "/api/experiments/Q-3/participants/PA/contamination/closure",
                HttpMethod.GET, headers("comp-2", "COMPLIANCE", null), null));
        assertEquals(2, current.path("currentVersion").asInt());
        assertEquals("OPEN", current.path("versionStatus").asText());
    }
}
