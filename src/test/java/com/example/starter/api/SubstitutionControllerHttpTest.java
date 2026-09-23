package com.example.starter.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 替代策略与恢复端点的真实 HTTP 入口测试：发布策略、版本化查询、
 * 平台触发替代的锁定与冻结解释 JSON、恢复流程及关键 4xx 语义。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubstitutionControllerHttpTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_step_rejection");
        jdbcTemplate.update("DELETE FROM lock_substitution_step");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_platform");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM substitution_candidate");
        jdbcTemplate.update("DELETE FROM substitution_rule");
        jdbcTemplate.update("DELETE FROM substitution_policy");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
        jdbcTemplate.update("UPDATE policy_state SET current_version = 0 WHERE id = 1");
    }

    private static HttpHeaders jsonHeaders(String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (requestId != null) {
            headers.set("X-Request-Id", requestId);
        }
        return headers;
    }

    private String registerBody(String name, int version, List<String> platforms,
                                Object... dependencies) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("version", version);
        body.put("dependencies", List.of(dependencies));
        body.put("platforms", platforms);
        return toJson(body);
    }

    private Map<String, Object> dependency(String name, int min, int max) {
        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("name", name);
        dep.put("minimumVersion", min);
        dep.put("maximumVersion", max);
        return dep;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void postArtifact(String body) {
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(body, jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private String policyBody(String source, String platform, Instant effectiveAt,
                              String target1, String target2) {
        Map<String, Object> candidate1 = new LinkedHashMap<>();
        candidate1.put("coordinate", target1);
        candidate1.put("priority", 1);
        List<Object> candidates = new java.util.ArrayList<>(List.of(candidate1));
        if (target2 != null) {
            Map<String, Object> candidate2 = new LinkedHashMap<>();
            candidate2.put("coordinate", target2);
            candidate2.put("priority", 2);
            candidates.add(candidate2);
        }
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("source", source);
        rule.put("platform", platform);
        rule.put("candidates", candidates);
        rule.put("effectiveAt", effectiveAt.toString());
        return toJson(Map.of("rules", List.of(rule)));
    }

    @Test
    void publishPolicyQueryCurrentAndGetVersionViaHttp() {
        String body = policyBody("legacy", "jvm", Instant.now().minusSeconds(3600),
                "newlib", "otherlib");

        ResponseEntity<JsonNode> created = restTemplate.postForEntity("/api/artifacts/policies",
                new HttpEntity<>(body, jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().path("version").asLong()).isEqualTo(1L);
        assertThat(created.getBody().path("rules").get(0).path("candidates")).hasSize(2);

        ResponseEntity<JsonNode> current = restTemplate.getForEntity(
                "/api/artifacts/policies/current", JsonNode.class);
        assertThat(current.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(current.getBody().path("version").asLong()).isEqualTo(1L);

        ResponseEntity<JsonNode> byVersion = restTemplate.getForEntity(
                "/api/artifacts/policies/1", JsonNode.class);
        assertThat(byVersion.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byVersion.getBody().path("rules").get(0).path("source").asText())
                .isEqualTo("legacy");

        ResponseEntity<JsonNode> list = restTemplate.getForEntity(
                "/api/artifacts/policies", JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).hasSize(1);
    }

    @Test
    void cyclicPolicyReturns422ViaHttp() {
        String ruleA = policyBody("a", "jvm", Instant.now().minusSeconds(3600), "b", null);
        // 手工构造两条规则的请求体。
        String body = "{\"rules\":["
                + "{\"source\":\"a\",\"platform\":\"jvm\",\"effectiveAt\":\""
                + Instant.now().minusSeconds(3600)
                + "\",\"candidates\":[{\"coordinate\":\"b\",\"priority\":1}]},"
                + "{\"source\":\"b\",\"platform\":\"jvm\",\"effectiveAt\":\""
                + Instant.now().minusSeconds(3600)
                + "\",\"candidates\":[{\"coordinate\":\"a\",\"priority\":1}]}]}";
        assertThat(ruleA).isNotNull();
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts/policies",
                new HttpEntity<>(body, jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().path("error").asText()).isEqualTo("UNPROCESSABLE_ENTITY");
    }

    @Test
    void missingPlatformInLockRequestReturns400() {
        postArtifact(registerBody("app", 1, List.of()));
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":1}";
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void substitutionLockEndToEndFreezesExplanationAndPolicyVersion() {
        // legacy:1 仅在 native 可用 → jvm 平台锁定 app 时触发替代到 newlib。
        postArtifact(registerBody("legacy", 1, List.of("native")));
        postArtifact(registerBody("app", 1, List.of(), dependency("legacy", 1, 1)));
        postArtifact(registerBody("newlib", 1, List.of()));
        restTemplate.postForEntity("/api/artifacts/policies",
                new HttpEntity<>(policyBody("legacy", "jvm",
                        Instant.now().minusSeconds(3600), "newlib", null),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);

        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"platform\":\"jvm\","
                + "\"expectedRepositoryVersion\":4}";
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(lock.getBody().path("platform").asText()).isEqualTo("jvm");
        assertThat(lock.getBody().path("policyVersion").asLong()).isEqualTo(1L);
        JsonNode steps = lock.getBody().path("substitutionSteps");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).path("originalCoordinate").asText()).isEqualTo("legacy");
        assertThat(steps.get(0).path("finalCoordinate").asText()).isEqualTo("newlib");
        assertThat(steps.get(0).path("sourcePattern").asText()).isEqualTo("legacy");

        long lockId = lock.getBody().path("id").asLong();
        ResponseEntity<JsonNode> reloaded = restTemplate.getForEntity(
                "/api/artifacts/locks/{id}", JsonNode.class, lockId);
        assertThat(reloaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reloaded.getBody().path("substitutionSteps").get(0).path("finalCoordinate").asText())
                .isEqualTo("newlib");
    }

    @Test
    void restoreWorkflowMakesWithdrawnArtifactLockableAgain() {
        postArtifact(registerBody("app", 1, List.of()));
        String rid = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> withdrawn = restTemplate.exchange(
                "/api/artifacts/app/versions/1/withdraw",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>("", jsonHeaders(rid)), JsonNode.class);
        assertThat(withdrawn.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 撤回状态锁定仍为 409。
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"platform\":\"jvm\","
                + "\"expectedRepositoryVersion\":2}";
        ResponseEntity<JsonNode> lockWhileWithdrawn = restTemplate.postForEntity(
                "/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(lockWhileWithdrawn.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<JsonNode> restored = restTemplate.exchange(
                "/api/artifacts/app/versions/1/restore",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>("", jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restored.getBody().path("withdrawn").asBoolean()).isFalse();

        String lockAfterRestore = "{\"rootName\":\"app\",\"rootVersion\":1,\"platform\":\"jvm\","
                + "\"expectedRepositoryVersion\":3}";
        ResponseEntity<JsonNode> lockOk = restTemplate.postForEntity(
                "/api/artifacts/locks",
                new HttpEntity<>(lockAfterRestore, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(lockOk.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void policyReplayWithSameRequestIdReturnsSameVersion() {
        String rid = UUID.randomUUID().toString();
        String body = policyBody("a", "jvm", Instant.now().minusSeconds(3600), "b", null);
        HttpEntity<String> request = new HttpEntity<>(body, jsonHeaders(rid));
        ResponseEntity<JsonNode> first = restTemplate.postForEntity(
                "/api/artifacts/policies", request, JsonNode.class);
        ResponseEntity<JsonNode> replay = restTemplate.postForEntity(
                "/api/artifacts/policies", request, JsonNode.class);
        assertThat(first.getBody().path("version").asLong()).isEqualTo(1L);
        assertThat(replay.getBody().path("version").asLong()).isEqualTo(1L);
    }
}
