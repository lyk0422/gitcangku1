package com.example.starter.restitution;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写操作幂等：同 requestId+同操作者+同参重放首次结果；异参 409；失败不占键；集合换序同参；
 * requestId 按操作者隔离。
 */
class IdempotencyIntegrationTest extends BaseIntegrationTest {

    @Test
    void sameKeySameParamsReplaysFirstResult() throws Exception {
        String fixedRequestId = newRequestId();

        JsonNode first = writeJson("POST", "/api/cases", "clerk", fixedRequestId,
                Map.of("items", List.of("Vase", "Scroll")), 201);
        JsonNode replay = writeJson("POST", "/api/cases", "clerk", fixedRequestId,
                Map.of("items", List.of("Vase", "Scroll")), 201);
        assertThat(replay.get("caseId").asText()).isEqualTo(first.get("caseId").asText());
        assertThat(replay.get("version").asLong()).isEqualTo(1);

        Integer caseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM restitution_case", Integer.class);
        assertThat(caseCount).isEqualTo(1);

        // 集合换序视为同参，重放同一结果
        JsonNode reordered = writeJson("POST", "/api/cases", "clerk", fixedRequestId,
                Map.of("items", List.of("Scroll", "Vase")), 201);
        assertThat(reordered.get("caseId").asText()).isEqualTo(first.get("caseId").asText());
    }

    @Test
    void sameKeyDifferentParamsConflicts() throws Exception {
        String fixedRequestId = newRequestId();
        writeJson("POST", "/api/cases", "clerk", fixedRequestId,
                Map.of("items", List.of("Vase")), 201);
        JsonNode err = writeJson("POST", "/api/cases", "clerk", fixedRequestId,
                Map.of("items", List.of("Scroll")), 409);
        assertThat(err.get("error").asText()).isEqualTo("IDEMPOTENCY_PARAM_CONFLICT");
    }

    @Test
    void failedRequestDoesNotOccupyKey() throws Exception {
        String caseId = createCase("clerk", List.of("Vase"));
        registerClaim("clerk", caseId, "c1", "museum-A", List.of("Vase"));

        long currentVersion = getJson("/api/cases/" + caseId, 200).get("version").asLong();
        // 裁决条件不满足：422，且该 requestId 不应被占用
        String decisionRequestId = newRequestId();
        writeJson("POST", "/api/cases/" + caseId + "/decisions", "judge", decisionRequestId,
                Map.of("expectedVersion", currentVersion, "claimKeys", List.of("c1")), 422);

        // 补齐证据与两名评审人批准后，同一 requestId 可正常提交并成功
        addEvidence("clerk", caseId, "c1", "ev-1", "summary-1");
        approve("reviewer-1", caseId, "c1");
        approve("reviewer-2", caseId, "c1");
        long readyVersion = getJson("/api/cases/" + caseId, 200).get("version").asLong();
        JsonNode decision = writeJson("POST", "/api/cases/" + caseId + "/decisions", "judge",
                decisionRequestId,
                Map.of("expectedVersion", readyVersion, "claimKeys", List.of("c1")), 200);
        assertThat(decision.get("status").asText()).isEqualTo("DECIDED");

        // 再次重放：返回首次成功结果（案件版本仍为裁决后版本，不产生第二次裁决）
        JsonNode replay = writeJson("POST", "/api/cases/" + caseId + "/decisions", "judge",
                decisionRequestId,
                Map.of("expectedVersion", readyVersion, "claimKeys", List.of("c1")), 200);
        assertThat(replay.get("version").asLong()).isEqualTo(decision.get("version").asLong());
    }

    @Test
    void replayOnClaimRegistrationDoesNotDuplicate() throws Exception {
        String caseId = createCase("clerk", List.of("Vase"));
        String fixedRequestId = newRequestId();
        Map<String, Object> body = Map.of("claimKey", "c1", "applicant", "museum-A",
                "statement", "desc", "items", List.of("Vase"));

        JsonNode first = writeJson("POST", "/api/cases/" + caseId + "/claims", "clerk",
                fixedRequestId, body, 201);
        JsonNode replay = writeJson("POST", "/api/cases/" + caseId + "/claims", "clerk",
                fixedRequestId, body, 201);
        assertThat(replay.get("version").asLong()).isEqualTo(first.get("version").asLong());

        Integer claimCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM claim WHERE case_id = ?", Integer.class, caseId);
        assertThat(claimCount).isEqualTo(1);
        Integer itemRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM claim_item ci JOIN claim c ON ci.claim_id = c.id "
                        + "WHERE c.case_id = ?", Integer.class, caseId);
        assertThat(itemRows).isEqualTo(1);
    }

    @Test
    void sameRequestIdIsScopedPerActor() throws Exception {
        String sharedRequestId = newRequestId();
        JsonNode a = writeJson("POST", "/api/cases", "actor-a", sharedRequestId,
                Map.of("items", List.of("Vase")), 201);
        JsonNode b = writeJson("POST", "/api/cases", "actor-b", sharedRequestId,
                Map.of("items", List.of("Vase")), 201);
        assertThat(a.get("caseId").asText()).isNotEqualTo(b.get("caseId").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restitution_case", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void missingRequestHeaderIsBadRequest() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/cases")
                        .header("X-Actor-Id", "clerk")
                        .contentType("application/json")
                        .content("{\"items\":[\"Vase\"]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isBadRequest());
    }
}
