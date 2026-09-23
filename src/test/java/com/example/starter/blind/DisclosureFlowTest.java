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
 * 泄露披露与污染闭包主流程、失败分支与整体回滚（真实 H2，非 mock）：
 * 已批准揭盲申请人登记直接披露、下游接收人继续登记、闭包固定点传播、
 * 重复边不新增、禁止伪造来源、exposureKey 唯一、失败整体回滚不留边/事件/版本。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DisclosureFlowTest extends AbstractBlindIntegrationTest {

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

    private void createExperimentAndParticipant(String expId, String pid, String prefix) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("coord-1", "COORDINATOR", prefix + "-create"),
                "{\"blockCount\":2}").getStatusCode().value());
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                HttpMethod.POST,
                headers("coord-1", "COORDINATOR", prefix + "-alloc"), null)
                .getStatusCode().value());
    }

    private String approveUnblindingFor(String expId, String pid, String applicant,
                                        String reviewer, String prefix) throws Exception {
        ResponseEntity<String> apply = exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/unblind-requests",
                HttpMethod.POST, headers(applicant, "COORDINATOR", prefix + "-apply"),
                "{\"reason\":\"合成医疗紧急情况需要揭盲\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = json(apply).path("requestId").asText();
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                headers(reviewer, "REVIEWER", prefix + "-approve"), null)
                .getStatusCode().value());
        return ubId;
    }

    private List<String> closureActors(JsonNode node) {
        return Arrays.asList(mapper.convertValue(node.withArray("closureActors"),
                String[].class));
    }

    private List<String> contaminatedActors(JsonNode node) {
        return Arrays.asList(mapper.convertValue(node.withArray("contaminatedActors"),
                String[].class));
    }

    @Test
    void disclosure_propagatesClosure_downstreamAndQueriesNeverExposeTreatment() throws Exception {
        createExperimentAndParticipant("D-1", "PA", "d1");
        String ubId = approveUnblindingFor("D-1", "PA", "coord-1", "rev-2", "d1");

        // 披露前：闭包仅含已批准申请人根节点，无版本，无处理代码。
        ResponseEntity<String> closureBefore = exchange(
                "/api/experiments/D-1/participants/PA/contamination/closure",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null);
        assertEquals(200, closureBefore.getStatusCode().value());
        JsonNode before = json(closureBefore);
        assertEquals(List.of("coord-1"), contaminatedActors(before));
        assertTrue(before.path("currentVersion").isNull());
        assertEquals(0, before.path("edgeCount").asInt());
        assertFalse(closureBefore.getBody().contains("treatment"));

        // 申请人直接披露给 op-a 与 op-b（1~20 名）。
        clock.advance(1000L);
        ResponseEntity<String> first = exchange(
                "/api/experiments/D-1/participants/PA/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "d1-disc-1"),
                "{\"exposureKey\":\"EX-1\",\"targetActorIds\":[\"op-a\",\"op-b\"]}");
        assertEquals(201, first.getStatusCode().value());
        JsonNode firstBody = json(first);
        assertEquals("EX-1", firstBody.path("exposureKey").asText());
        assertEquals("coord-1", firstBody.path("sourceActorId").asText());
        assertEquals(2, firstBody.path("newEdgeCount").asInt());
        assertEquals(0, firstBody.path("duplicateEdgeCount").asInt());
        assertEquals(1, firstBody.path("closureVersion").asInt());
        assertEquals(List.of("coord-1", "op-a", "op-b"), closureActors(firstBody));
        assertFalse(first.getBody().contains("treatment"));

        // 下游接收人 op-a（以 REVIEWER 身份）继续披露给 op-c：闭包沿有向边传播。
        clock.advance(1000L);
        ResponseEntity<String> downstream = exchange(
                "/api/experiments/D-1/participants/PA/disclosures", HttpMethod.POST,
                headers("op-a", "REVIEWER", "d1-disc-2"),
                "{\"exposureKey\":\"EX-2\",\"targetActorIds\":[\"op-c\"]}");
        assertEquals(201, downstream.getStatusCode().value());
        JsonNode downBody = json(downstream);
        assertEquals(2, downBody.path("closureVersion").asInt());
        assertEquals(List.of("coord-1", "op-a", "op-b", "op-c"), closureActors(downBody));

        // op-c 再披露给已在闭包内的 op-a 与新节点 op-d：
        // op-c→op-a 与既有 op-a→op-c 方向相反，仍是一条新有向边；仅闭包新增 op-d。
        clock.advance(1000L);
        ResponseEntity<String> mixed = exchange(
                "/api/experiments/D-1/participants/PA/disclosures", HttpMethod.POST,
                headers("op-c", "REVIEWER", "d1-disc-3"),
                "{\"exposureKey\":\"EX-3\",\"targetActorIds\":[\"op-a\",\"op-d\"]}");
        assertEquals(201, mixed.getStatusCode().value());
        JsonNode mixedBody = json(mixed);
        assertEquals(2, mixedBody.path("newEdgeCount").asInt());
        assertEquals(0, mixedBody.path("duplicateEdgeCount").asInt());
        assertEquals(3, mixedBody.path("closureVersion").asInt());
        assertEquals(List.of("coord-1", "op-a", "op-b", "op-c", "op-d"), closureActors(mixedBody));
        assertEquals(5, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_edge WHERE experiment_id = 'D-1'", Integer.class));

        // 闭包查询：当前版本 v3、边 5 条；版本列表三版连续，均不含处理代码。
        JsonNode closureNow = json(exchange(
                "/api/experiments/D-1/participants/PA/contamination/closure",
                HttpMethod.GET, headers("rev-2", "REVIEWER", null), null));
        assertEquals(3, closureNow.path("currentVersion").asInt());
        assertEquals("OPEN", closureNow.path("versionStatus").asText());
        assertEquals(5, closureNow.path("edgeCount").asInt());
        assertEquals(List.of("coord-1", "op-a", "op-b", "op-c", "op-d"),
                contaminatedActors(closureNow));

        ResponseEntity<String> versions = exchange(
                "/api/experiments/D-1/participants/PA/contamination/versions",
                HttpMethod.GET, headers("rev-2", "REVIEWER", null), null);
        assertEquals(200, versions.getStatusCode().value());
        JsonNode versionArray = json(versions);
        assertEquals(3, versionArray.size());
        assertEquals(1, versionArray.get(0).path("versionNo").asInt());
        assertEquals(2, versionArray.get(1).path("versionNo").asInt());
        assertEquals(3, versionArray.get(2).path("versionNo").asInt());
        assertEquals(2, versionArray.get(0).path("edgeCount").asInt());
        assertEquals(3, versionArray.get(1).path("edgeCount").asInt());
        assertEquals(5, versionArray.get(2).path("edgeCount").asInt());
        assertFalse(versions.getBody().contains("treatment"));

        // 单版本查询。
        ResponseEntity<String> v1 = exchange(
                "/api/experiments/D-1/participants/PA/contamination/versions/1",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null);
        assertEquals(200, v1.getStatusCode().value());
        assertEquals(List.of("coord-1", "op-a", "op-b"), Arrays.asList(
                mapper.convertValue(json(v1).withArray("actors"), String[].class)));
        assertEquals(404, exchange(
                "/api/experiments/D-1/participants/PA/contamination/versions/99",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null)
                .getStatusCode().value());

        // 污染记录不扩大处理代码查询权限：闭包内 op-a 查询揭盲结果仍 403；
        // 已有批准结果仍可由原申请人 coord-1 读取。
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("op-a", "REVIEWER", null), null)
                .getStatusCode().value());
        ResponseEntity<String> result = exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null);
        assertEquals(200, result.getStatusCode().value());
        assertTrue(result.getBody().contains("treatment"));
    }

    @Test
    void duplicateEdges_doNotAddOrVersion_butEventIsRecorded() throws Exception {
        createExperimentAndParticipant("D-2", "PA", "d2");
        approveUnblindingFor("D-2", "PA", "coord-1", "rev-2", "d2");

        assertEquals(201, exchange(
                "/api/experiments/D-2/participants/PA/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "d2-disc-1"),
                "{\"exposureKey\":\"EX-10\",\"targetActorIds\":[\"op-a\"]}")
                .getStatusCode().value());

        // 换 exposureKey 与 requestId 重复登记完全相同的边：不新增边、不生成新版本。
        ResponseEntity<String> repeat = exchange(
                "/api/experiments/D-2/participants/PA/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "d2-disc-2"),
                "{\"exposureKey\":\"EX-11\",\"targetActorIds\":[\"op-a\"]}");
        assertEquals(201, repeat.getStatusCode().value());
        JsonNode body = json(repeat);
        assertEquals(0, body.path("newEdgeCount").asInt());
        assertEquals(1, body.path("duplicateEdgeCount").asInt());
        assertEquals(1, body.path("closureVersion").asInt(), "全部重复时不得新增版本");

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_edge WHERE experiment_id = 'D-2'", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_event WHERE experiment_id = 'D-2'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM closure_version WHERE experiment_id = 'D-2'", Integer.class));
    }

    @Test
    void disclosure_rejectsUnauthorizedSourcesUnknownParticipantsAndBadBaskets() {
        createExperimentAndParticipant("D-3", "PA", "d3");
        // PA 尚未揭盲：无人获知代码，任何来源都不能登记。
        assertEquals(403, exchange(
                "/api/experiments/D-3/participants/PA/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "d3-no-unblind"),
                "{\"exposureKey\":\"EX-20\",\"targetActorIds\":[\"op-a\"]}")
                .getStatusCode().value());

        approveUnblindingForFlow("D-3", "PA");

        // 登记另一参与者 PB 但不揭盲：coord-1 仅获知 PA，不能登记 PB 的披露（闭包为空）。
        assertEquals(201, exchange(
                "/api/experiments/D-3/participants/PB/allocations", HttpMethod.POST,
                headers("coord-2", "COORDINATOR", "d3-pb-alloc"), null)
                .getStatusCode().value());
        assertEquals(403, exchange(
                "/api/experiments/D-3/participants/PB/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "d3-cross-participant"),
                "{\"exposureKey\":\"EX-CROSS\",\"targetActorIds\":[\"op-a\"]}")
                .getStatusCode().value());

        // 未获知代码的操作者伪造披露源：403。
        assertEquals(403, exchange(
                "/api/experiments/D-3/participants/PA/disclosures", HttpMethod.POST,
                headers("outsider", "COORDINATOR", "d3-outsider"),
                "{\"exposureKey\":\"EX-21\",\"targetActorIds\":[\"op-a\"]}")
                .getStatusCode().value());

        // COMPLIANCE 角色身份本身不授予代码知识：不在闭包内的合规负责人登记仍 403
        // （授权按“本人是否在闭包内”判定，与角色无关；下游接收人可登记已在主流程以
        // op-a 的 REVIEWER 身份覆盖）。
        assertEquals(403, exchange(
                "/api/experiments/D-3/participants/PA/disclosures", HttpMethod.POST,
                headers("comp-clean", "COMPLIANCE", "d3-compliance"),
                "{\"exposureKey\":\"EX-22\",\"targetActorIds\":[\"op-a\"]}")
                .getStatusCode().value());

        // 向自己登记：400。
        assertEquals(400, exchange(
                "/api/experiments/D-3/participants/PA/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "d3-self"),
                "{\"exposureKey\":\"EX-23\",\"targetActorIds\":[\"coord-1\"]}")
                .getStatusCode().value());

        // 空接收人列表：400。
        assertEquals(400, exchange(
                "/api/experiments/D-3/participants/PA/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "d3-empty"),
                "{\"exposureKey\":\"EX-24\",\"targetActorIds\":[]}")
                .getStatusCode().value());

        // 超过 20 名接收人：400。
        String tooMany = String.join("\",\"",
                java.util.stream.IntStream.range(1, 22).mapToObj(i -> "op-" + i).toList());
        assertEquals(400, exchange(
                "/api/experiments/D-3/participants/PA/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "d3-too-many"),
                "{\"exposureKey\":\"EX-25\",\"targetActorIds\":[\"" + tooMany + "\"]}")
                .getStatusCode().value());

        // exposureKey 缺失：400。
        assertEquals(400, exchange(
                "/api/experiments/D-3/participants/PA/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "d3-no-key"),
                "{\"exposureKey\":\"\",\"targetActorIds\":[\"op-a\"]}")
                .getStatusCode().value());

        // 未登记参与者：404。
        assertEquals(404, exchange(
                "/api/experiments/D-3/participants/NOBODY/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "d3-nobody"),
                "{\"exposureKey\":\"EX-26\",\"targetActorIds\":[\"op-a\"]}")
                .getStatusCode().value());

        // 上述全部失败必须整体回滚：无事件、无边、无版本。
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_event WHERE experiment_id = 'D-3'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_edge WHERE experiment_id = 'D-3'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM closure_version WHERE experiment_id = 'D-3'", Integer.class));
    }

    @Test
    void exposureKey_isGloballyUnique_failureRollsBackAndDoesNotConsumeRequestId() {
        createExperimentAndParticipant("D-4", "PA", "d4a");
        approveUnblindingForFlow("D-4", "PA");
        assertEquals(201, exchange(
                "/api/experiments/D-4/participants/PA/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "d4-disc-1"),
                "{\"exposureKey\":\"EX-30\",\"targetActorIds\":[\"op-a\"]}")
                .getStatusCode().value());

        // 同 exposureKey 搭配不同 requestId 与不同接收人：409，且不写入任何新边。
        ResponseEntity<String> reused = exchange(
                "/api/experiments/D-4/participants/PA/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "d4-disc-2"),
                "{\"exposureKey\":\"EX-30\",\"targetActorIds\":[\"op-z\"]}");
        assertEquals(409, reused.getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_edge WHERE experiment_id = 'D-4'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_edge WHERE target_actor = 'op-z'", Integer.class));
        // 失败不占幂等键：同一 requestId 换新 exposureKey 后成功。
        assertEquals(201, exchange(
                "/api/experiments/D-4/participants/PA/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "d4-disc-2"),
                "{\"exposureKey\":\"EX-31\",\"targetActorIds\":[\"op-b\"]}")
                .getStatusCode().value());
    }

    private void approveUnblindingForFlow(String expId, String pid) {
        ResponseEntity<String> apply = exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/unblind-requests",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", expId + "-x-apply"),
                "{\"reason\":\"紧急医疗\"}");
        try {
            String ubId = json(apply).path("requestId").asText();
            assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval",
                    HttpMethod.POST, headers("rev-2", "REVIEWER", expId + "-x-approve"), null)
                    .getStatusCode().value());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
