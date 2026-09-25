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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中心双人激活：两名不同 COORDINATOR 确认同一 activationKey；
 * 第二人确认时校验未关闭且上限大于零；激活记录不可变、代次递增。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SiteActivationTest extends AbstractBlindIntegrationTest {

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
        return mapper.readTree(response.getBody());
    }

    private void createExperiment(String id, int blockCount, String requestId) {
        ResponseEntity<String> resp = exchange("/api/experiments/" + id, HttpMethod.POST,
                headers("c1", "COORDINATOR", requestId),
                "{\"blockCount\":" + blockCount + "}");
        assertEquals(201, resp.getStatusCode().value());
    }

    private ResponseEntity<String> createSite(String exp, String site, int cap, String requestId) {
        return exchange("/api/experiments/" + exp + "/sites/" + site, HttpMethod.POST,
                headers("c1", "COORDINATOR", requestId), "{\"targetCap\":" + cap + "}");
    }

    private ResponseEntity<String> confirm(String exp, String site, String actor,
                                           String key, String requestId) {
        return exchange("/api/experiments/" + exp + "/sites/" + site + "/activation-confirmations",
                HttpMethod.POST, headers(actor, "COORDINATOR", requestId),
                "{\"activationKey\":\"" + key + "\"}");
    }

    @Test
    void dualConfirmation_activatesSite_writesImmutableRecordAndIncrementsGeneration()
            throws Exception {
        createExperiment("EXP-S1", 2, "req-s1-exp");
        ResponseEntity<String> created = createSite("EXP-S1", "SITE-1", 10, "req-s1-site");
        assertEquals(201, created.getStatusCode().value());
        JsonNode site = json(created);
        assertEquals("PENDING", site.path("status").asText());
        assertEquals(0, site.path("generation").asInt());
        assertEquals(10, site.path("targetCap").asInt());
        assertEquals(0, site.path("allocatedCount").asInt());
        assertEquals("SITE_NOT_ACTIVE", site.path("gateReason").asText());
        assertTrue(site.path("activations").isEmpty());

        // 第一人确认：记录待确认，中心仍未激活
        clock.advance(1_000L);
        ResponseEntity<String> first = confirm("EXP-S1", "SITE-1", "m1", "KEY-1", "req-s1-c1");
        assertEquals(200, first.getStatusCode().value());
        JsonNode firstBody = json(first);
        assertFalse(firstBody.path("activated").asBoolean());
        assertEquals("m1", firstBody.path("firstActor").asText());
        assertEquals("PENDING", firstBody.path("status").asText());
        assertEquals(1_700_000_001_000L, firstBody.path("firstConfirmedAt").asLong());

        // 第二人（不同操作者、同一 activationKey）确认：激活生效
        clock.advance(2_000L);
        ResponseEntity<String> second = confirm("EXP-S1", "SITE-1", "m2", "KEY-1", "req-s1-c2");
        assertEquals(200, second.getStatusCode().value());
        JsonNode secondBody = json(second);
        assertTrue(secondBody.path("activated").asBoolean());
        assertEquals("ACTIVE", secondBody.path("status").asText());
        assertEquals(1, secondBody.path("generation").asInt());
        assertEquals("m1", secondBody.path("firstActor").asText());
        assertEquals("m2", secondBody.path("secondActor").asText());
        assertEquals(1_700_000_003_000L, secondBody.path("secondConfirmedAt").asLong());

        // 查询：代次 1、门禁解除、双人激活记录不可变
        ResponseEntity<String> view = exchange("/api/experiments/EXP-S1/sites/SITE-1",
                HttpMethod.GET, headers("r1", "REVIEWER", null), null);
        assertEquals(200, view.getStatusCode().value());
        JsonNode viewBody = json(view);
        assertEquals("ACTIVE", viewBody.path("status").asText());
        assertEquals(1, viewBody.path("generation").asInt());
        assertTrue(viewBody.path("gateReason").isNull());
        assertEquals(1, viewBody.path("activations").size());
        JsonNode record = viewBody.path("activations").get(0);
        assertEquals(1, record.path("generation").asInt());
        assertEquals("KEY-1", record.path("activationKey").asText());
        assertEquals("m1", record.path("firstActor").asText());
        assertEquals("m2", record.path("secondActor").asText());
        assertEquals(10, record.path("targetCap").asInt());

        // 库内激活记录恰一条，且中心行代次一致
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation "
                        + "WHERE experiment_id = 'EXP-S1' AND site_code = 'SITE-1'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT generation FROM site "
                        + "WHERE experiment_id = 'EXP-S1' AND site_code = 'SITE-1'",
                Integer.class));
        // 待确认已清空
        assertNull(jdbc.queryForObject(
                "SELECT pending_actor FROM site "
                        + "WHERE experiment_id = 'EXP-S1' AND site_code = 'SITE-1'",
                String.class));
    }

    @Test
    void confirm_rejectsSameActorSecondAndMismatchedKey() throws Exception {
        createExperiment("EXP-S2", 2, "req-s2-exp");
        assertEquals(201, createSite("EXP-S2", "SITE-1", 5, "req-s2-site").getStatusCode().value());
        assertEquals(200, confirm("EXP-S2", "SITE-1", "m1", "KEY-1", "req-s2-c1")
                .getStatusCode().value());

        // 同一操作者不同键：409
        ResponseEntity<String> sameActorDiffKey =
                confirm("EXP-S2", "SITE-1", "m1", "KEY-OTHER", "req-s2-c2");
        assertEquals(409, sameActorDiffKey.getStatusCode().value());

        // 不同操作者不同键：409
        ResponseEntity<String> diffActorDiffKey =
                confirm("EXP-S2", "SITE-1", "m2", "KEY-OTHER", "req-s2-c3");
        assertEquals(409, diffActorDiffKey.getStatusCode().value());

        // 同一操作者同键：重放首次确认响应，不产生第二确认
        ResponseEntity<String> replay = confirm("EXP-S2", "SITE-1", "m1", "KEY-1", "req-s2-c4");
        assertEquals(200, replay.getStatusCode().value());
        JsonNode replayBody = json(replay);
        assertFalse(replayBody.path("activated").asBoolean());
        assertEquals("m1", replayBody.path("firstActor").asText());
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM site "
                        + "WHERE experiment_id = 'EXP-S2' AND site_code = 'SITE-1'",
                String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation "
                        + "WHERE experiment_id = 'EXP-S2' AND site_code = 'SITE-1'",
                Integer.class));

        // 第二人同键确认成功
        assertEquals(200, confirm("EXP-S2", "SITE-1", "m2", "KEY-1", "req-s2-c5")
                .getStatusCode().value());
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM site "
                        + "WHERE experiment_id = 'EXP-S2' AND site_code = 'SITE-1'",
                String.class));
    }

    @Test
    void confirm_rejectsZeroCapAndClosedSite_andReplaysOnActive() throws Exception {
        createExperiment("EXP-S3", 2, "req-s3-exp");
        // 上限为 0：第二人确认时校验失败 409，且中心保持未激活
        assertEquals(201, createSite("EXP-S3", "SITE-Z", 0, "req-s3-sitez")
                .getStatusCode().value());
        assertEquals(200, confirm("EXP-S3", "SITE-Z", "m1", "KEY-Z", "req-s3-z1")
                .getStatusCode().value());
        ResponseEntity<String> zeroCap = confirm("EXP-S3", "SITE-Z", "m2", "KEY-Z", "req-s3-z2");
        assertEquals(409, zeroCap.getStatusCode().value());
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM site "
                        + "WHERE experiment_id = 'EXP-S3' AND site_code = 'SITE-Z'",
                String.class));

        // 正常中心激活后：两名确认人之一同键重放激活结果；其他操作者 409
        assertEquals(201, createSite("EXP-S3", "SITE-1", 3, "req-s3-site1")
                .getStatusCode().value());
        assertEquals(200, confirm("EXP-S3", "SITE-1", "m1", "KEY-1", "req-s3-a1")
                .getStatusCode().value());
        assertEquals(200, confirm("EXP-S3", "SITE-1", "m2", "KEY-1", "req-s3-a2")
                .getStatusCode().value());
        ResponseEntity<String> replay = confirm("EXP-S3", "SITE-1", "m2", "KEY-1", "req-s3-a3");
        assertEquals(200, replay.getStatusCode().value());
        assertTrue(json(replay).path("activated").asBoolean());
        assertEquals(1, json(replay).path("generation").asInt());
        ResponseEntity<String> stranger = confirm("EXP-S3", "SITE-1", "m3", "KEY-1", "req-s3-a4");
        assertEquals(409, stranger.getStatusCode().value());

        // 关闭后确认：409 不可恢复
        assertEquals(200, exchange("/api/experiments/EXP-S3/sites/SITE-1/closure",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s3-close"), null)
                .getStatusCode().value());
        ResponseEntity<String> confirmClosed = confirm("EXP-S3", "SITE-1", "m1", "KEY-1",
                "req-s3-a5");
        assertEquals(409, confirmClosed.getStatusCode().value());
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT status FROM site "
                        + "WHERE experiment_id = 'EXP-S3' AND site_code = 'SITE-1'",
                String.class));
    }

    @Test
    void inactiveSite_rejectsAllocationWith409_andSiteNotFound404() throws Exception {
        createExperiment("EXP-S4", 2, "req-s4-exp");
        assertEquals(201, createSite("EXP-S4", "SITE-1", 5, "req-s4-site")
                .getStatusCode().value());

        // 未激活中心：不得生成盲码或分配区组 → 409
        ResponseEntity<String> alloc = exchange(
                "/api/experiments/EXP-S4/sites/SITE-1/participants/P1/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s4-alloc"),
                "{\"assignmentKey\":\"ASG-1\"}");
        assertEquals(409, alloc.getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'EXP-S4'",
                Integer.class));

        // 中心不存在 → 404；实验不存在 → 404
        assertEquals(404, exchange(
                "/api/experiments/EXP-S4/sites/NOPE/participants/P1/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s4-alloc-404"),
                "{\"assignmentKey\":\"ASG-2\"}").getStatusCode().value());
        assertEquals(404, exchange("/api/experiments/EXP-S4/sites/NOPE",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null)
                .getStatusCode().value());
        assertEquals(404, createSite("NOPE-EXP", "SITE-1", 5, "req-s4-site-404")
                .getStatusCode().value());

        // REVIEWER 不可执行中心写操作
        assertEquals(403, exchange("/api/experiments/EXP-S4/sites/SITE-2",
                HttpMethod.POST, headers("r1", "REVIEWER", "req-s4-site-403"),
                "{\"targetCap\":5}").getStatusCode().value());
        assertEquals(403, exchange(
                "/api/experiments/EXP-S4/sites/SITE-1/activation-confirmations",
                HttpMethod.POST, headers("r1", "REVIEWER", "req-s4-c-403"),
                "{\"activationKey\":\"K\"}").getStatusCode().value());
    }

    @Test
    void sameRequestId_replaysSiteCreateAndConfirmResponses() throws Exception {
        createExperiment("EXP-S5", 2, "req-s5-exp");
        ResponseEntity<String> first = createSite("EXP-S5", "SITE-1", 5, "req-s5-site");
        ResponseEntity<String> replay = createSite("EXP-S5", "SITE-1", 5, "req-s5-site");
        assertEquals(201, first.getStatusCode().value());
        assertEquals(201, replay.getStatusCode().value());
        assertNotNull(first.getBody());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site "
                        + "WHERE experiment_id = 'EXP-S5' AND site_code = 'SITE-1'",
                Integer.class));

        // 同 requestId 重放首次确认：响应一致，不产生额外状态
        ResponseEntity<String> c1 = confirm("EXP-S5", "SITE-1", "m1", "KEY-1", "req-s5-c1");
        ResponseEntity<String> c1Replay = confirm("EXP-S5", "SITE-1", "m1", "KEY-1", "req-s5-c1");
        assertEquals(200, c1.getStatusCode().value());
        assertEquals(c1.getBody(), c1Replay.getBody());
        // 失败不占键：异参同键 409 后，正确参数可用新键成功
        assertEquals(409, confirm("EXP-S5", "SITE-1", "m2", "KEY-X", "req-s5-c2")
                .getStatusCode().value());
        assertEquals(200, confirm("EXP-S5", "SITE-1", "m2", "KEY-1", "req-s5-c3")
                .getStatusCode().value());
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM site "
                        + "WHERE experiment_id = 'EXP-S5' AND site_code = 'SITE-1'",
                String.class));
    }
}
