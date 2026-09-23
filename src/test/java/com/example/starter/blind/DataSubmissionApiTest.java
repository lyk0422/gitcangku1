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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 受试者数据提交与授权代次边界：
 * 无授权 403；生效时刻前新代次不可用、旧代次令牌一律拒绝；生效后旧令牌仍拒绝；
 * 提交后知情（揭盲）即失去采集权限；写入按事务提交顺序归属代次；提交幂等。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DataSubmissionApiTest extends AbstractBlindIntegrationTest {

    private static final long T0 = 1_700_000_000_000L;

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

    private void createExperiment(String expId, String requestId) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("pi1", "COORDINATOR", requestId), "{\"blockCount\":2}")
                .getStatusCode().value());
    }

    private void register(String expId, String participantId, String requestId) {
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/" + participantId + "/allocations",
                HttpMethod.POST, headers("pi1", "COORDINATOR", requestId), null)
                .getStatusCode().value());
    }

    private void rotate(String expId, String rotationKey, long expectedVersion, long effectiveAt,
                        List<String> collectors, String requestId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rotationKey", rotationKey);
        body.put("expectedExperimentVersion", expectedVersion);
        body.put("effectiveAt", effectiveAt);
        body.put("dataCollectors", collectors);
        body.put("randomizationCustodians", List.of("rc1"));
        body.put("safetyReviewers", List.of("sr1"));
        assertEquals(201, exchange("/api/experiments/" + expId + "/role-rotations",
                HttpMethod.POST, headers("pi1", "COORDINATOR", requestId),
                mapper.writeValueAsString(body)).getStatusCode().value());
    }

    private ResponseEntity<String> submit(String expId, String participantId, String actor,
                                          long generation, String payload, String requestId)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessGeneration", generation);
        body.put("payload", payload);
        // 数据提交权限来自代次授权而非登录角色：统一以 REVIEWER 头提交
        return exchange("/api/experiments/" + expId + "/participants/" + participantId + "/data",
                HttpMethod.POST, headers(actor, "REVIEWER", requestId),
                mapper.writeValueAsString(body));
    }

    @Test
    void submit_withoutGrant_403() throws Exception {
        createExperiment("EXP-D1", "d1-create");
        register("EXP-D1", "P1", "d1-reg-1");
        // 初始代次无任何授权
        ResponseEntity<String> resp = submit("EXP-D1", "P1", "dc1", 1, "obs-1", "d1-sub-1");
        assertEquals(403, resp.getStatusCode().value());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM subject_data", Integer.class));
    }

    @Test
    void submit_withActiveGrant_success_attributesCurrentGeneration() throws Exception {
        createExperiment("EXP-D2", "d2-create");
        register("EXP-D2", "P1", "d2-reg-1");
        // 版本 2，轮换后代次 2，dc1 为采集人
        rotate("EXP-D2", "RK-D2", 2, T0, List.of("dc1"), "d2-rot");
        ResponseEntity<String> resp = submit("EXP-D2", "P1", "dc1", 2, "obs-1", "d2-sub-1");
        assertEquals(201, resp.getStatusCode().value());
        JsonNode view = json(resp);
        assertEquals(2, view.path("generationNo").asInt());
        assertEquals("dc1", view.path("collectorActor").asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM subject_data "
                + "WHERE experiment_id = 'EXP-D2' AND generation_no = 2", Integer.class));
        // 无授权人员即使持当前代次号也 403
        ResponseEntity<String> stranger = submit("EXP-D2", "P1", "dc9", 2, "obs-2", "d2-sub-2");
        assertEquals(403, stranger.getStatusCode().value());
    }

    @Test
    void submit_tokenLifecycle_oldTokenRejectedBeforeAndAfterEffectiveAt() throws Exception {
        createExperiment("EXP-D3", "d3-create");
        register("EXP-D3", "P1", "d3-reg-1");
        // 生效时刻在未来 1 小时；激活即结束旧代次
        rotate("EXP-D3", "RK-D3", 2, T0 + 3_600_000L, List.of("dc1"), "d3-rot");
        // 生效前：新代次令牌不可用
        ResponseEntity<String> early = submit("EXP-D3", "P1", "dc1", 2, "obs-1", "d3-sub-1");
        assertEquals(409, early.getStatusCode().value());
        // 生效前签发的旧代次令牌：生效前使用即拒绝
        ResponseEntity<String> oldBefore = submit("EXP-D3", "P1", "dc1", 1, "obs-1", "d3-sub-2");
        assertEquals(409, oldBefore.getStatusCode().value());
        // 生效后：新代次可用
        clock.advance(3_600_000L);
        ResponseEntity<String> after = submit("EXP-D3", "P1", "dc1", 2, "obs-1", "d3-sub-3");
        assertEquals(201, after.getStatusCode().value());
        // 生效后旧代次令牌仍拒绝
        ResponseEntity<String> oldAfter = submit("EXP-D3", "P1", "dc1", 1, "obs-2", "d3-sub-4");
        assertEquals(409, oldAfter.getStatusCode().value());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM subject_data "
                + "WHERE experiment_id = 'EXP-D3'", Integer.class));
    }

    @Test
    void submit_afterCollectorLearnsGroupViaUnblind_403() throws Exception {
        createExperiment("EXP-D4", "d4-create");
        register("EXP-D4", "P1", "d4-reg-1");
        rotate("EXP-D4", "RK-D4", 2, T0, List.of("dc1"), "d4-rot");
        // 授权后 dc1 通过受控揭盲知悉 P1 分组（申请须 COORDINATOR 角色头）
        ResponseEntity<String> apply = exchange(
                "/api/experiments/EXP-D4/participants/P1/unblind-requests", HttpMethod.POST,
                headers("dc1", "COORDINATOR", "d4-ub-apply"), "{\"reason\":\"应急揭盲\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = json(apply).path("requestId").asText();
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                headers("rev1", "REVIEWER", "d4-ub-approve"), null).getStatusCode().value());
        // 知情历史不可删除：dc1 立即失去对 P1 的采集权限
        ResponseEntity<String> resp = submit("EXP-D4", "P1", "dc1", 2, "obs-1", "d4-sub-1");
        assertEquals(403, resp.getStatusCode().value());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM subject_data", Integer.class));
    }

    @Test
    void submit_withdrawnSubjectOrClosedExperiment_409() throws Exception {
        createExperiment("EXP-D5", "d5-create");
        register("EXP-D5", "P1", "d5-reg-1");
        register("EXP-D5", "P2", "d5-reg-2");
        rotate("EXP-D5", "RK-D5", 3, T0, List.of("dc1"), "d5-rot");
        // 受试者退组（已结束）：拒绝提交
        assertEquals(200, exchange("/api/experiments/EXP-D5/participants/P2/withdrawal",
                HttpMethod.POST, headers("pi1", "COORDINATOR", "d5-wd"), null)
                .getStatusCode().value());
        ResponseEntity<String> withdrawn = submit("EXP-D5", "P2", "dc1", 2, "obs-1", "d5-sub-1");
        assertEquals(409, withdrawn.getStatusCode().value());
        // 实验关闭：拒绝提交
        assertEquals(200, exchange("/api/experiments/EXP-D5/close", HttpMethod.POST,
                headers("pi1", "COORDINATOR", "d5-close"), null).getStatusCode().value());
        ResponseEntity<String> closed = submit("EXP-D5", "P1", "dc1", 2, "obs-2", "d5-sub-2");
        assertEquals(409, closed.getStatusCode().value());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM subject_data", Integer.class));
    }

    @Test
    void submit_idempotentReplay_returnsFirstSnapshotAndSingleRow() throws Exception {
        createExperiment("EXP-D6", "d6-create");
        register("EXP-D6", "P1", "d6-reg-1");
        rotate("EXP-D6", "RK-D6", 2, T0, List.of("dc1"), "d6-rot");
        ResponseEntity<String> first = submit("EXP-D6", "P1", "dc1", 2, "obs-1", "d6-sub-1");
        assertEquals(201, first.getStatusCode().value());
        ResponseEntity<String> replay = submit("EXP-D6", "P1", "dc1", 2, "obs-1", "d6-sub-1");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM subject_data "
                + "WHERE experiment_id = 'EXP-D6'", Integer.class));
        // 同键异参 409
        ResponseEntity<String> conflict = submit("EXP-D6", "P1", "dc1", 2, "obs-2", "d6-sub-1");
        assertEquals(409, conflict.getStatusCode().value());
    }
}
