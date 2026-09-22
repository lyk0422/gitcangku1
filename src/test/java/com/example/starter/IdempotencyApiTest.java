package com.example.starter;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等性测试：同键同参重放原成功结果、同键异参 409、失败不占键、键与业务变更原子提交。
 */
class IdempotencyApiTest extends IntegrationTestBase {

    @Test
    void sameKeySameParamsReplaysOriginalZoneCreation() throws Exception {
        MvcResult first = createZone("idem-1", "zone-a", 0, 0, 10, 10);
        assertEquals(200, status(first));
        MvcResult replay = createZone("idem-1", "zone-a", 0, 0, 10, 10);
        assertEquals(200, status(replay));
        // 重放返回原成功结果，且不重复创建、不重复递增版本。
        assertEquals(json(first), json(replay));
        assertEquals(1, countRows("zones"));
        assertEquals(1, airspaceVersion());
        assertEquals(1, countRows("idempotency_keys"));
    }

    @Test
    void sameKeyDifferentParamsRejectedWith409() throws Exception {
        assertEquals(200, status(createZone("idem-2", "zone-a", 0, 0, 10, 10)));
        MvcResult conflict = createZone("idem-2", "zone-a", 0, 0, 20, 20);
        assertEquals(409, status(conflict));
        assertTrue(json(conflict).contains("IDEMPOTENCY_CONFLICT"));
        assertEquals(1, countRows("zones"));
    }

    @Test
    void requestIdIsGloballyUniqueAcrossEndpoints() throws Exception {
        assertEquals(200, status(createZone("idem-3", "zone-a", 0, 0, 10, 10)));
        // 同一 requestId 用于不同写操作，视为异参冲突。
        MvcResult conflict = createRoute("idem-3", "route-a", 0, 0, 5, 5);
        assertEquals(409, status(conflict));
        assertTrue(json(conflict).contains("IDEMPOTENCY_CONFLICT"));
        assertEquals(0, countRows("routes"));
    }

    @Test
    void failedValidationDoesNotOccupyKey() throws Exception {
        // 退化矩形创建失败（400），同一 requestId 可携带合法参数重试。
        assertEquals(400, status(createZone("idem-4", "zone-a", 5, 5, 5, 10)));
        MvcResult retry = createZone("idem-4", "zone-a", 0, 0, 10, 10);
        assertEquals(200, status(retry));
        assertEquals(1, countRows("zones"));
    }

    @Test
    void failedBusinessConflictDoesNotOccupyKey() throws Exception {
        assertEquals(200, status(createRoute("idem-5", "route-a", 0, 0, 10, 10)));
        // 重复 routeId 创建失败（409，事务内回滚），键不应被占用。
        assertEquals(409, status(createRoute("idem-6", "route-a", 0, 0, 10, 10)));
        MvcResult retry = createRoute("idem-6", "route-b", 0, 0, 10, 10);
        assertEquals(200, status(retry));
        assertEquals(2, countRows("routes"));
    }

    @Test
    void reviewReplayReturnsSameReviewId() throws Exception {
        assertEquals(200, status(createZone("idem-7", "zone-a", 0, 0, 10, 10)));
        assertEquals(200, status(createRoute("idem-8", "route-a", -5, 5, 15, 5)));
        MvcResult first = review("idem-9", "route-a", 1, 1);
        assertEquals(200, status(first));
        MvcResult replay = review("idem-9", "route-a", 1, 1);
        assertEquals(200, status(replay));
        JsonNode firstBody = objectMapper.readTree(json(first));
        JsonNode replayBody = objectMapper.readTree(json(replay));
        assertEquals(firstBody.get("reviewId").asText(), replayBody.get("reviewId").asText());
        assertEquals("BLOCKED", replayBody.get("conclusion").asText());
        assertEquals(1, countRows("reviews"));
    }

    @Test
    void reviewSameKeyDifferentVersionRejectedWith409() throws Exception {
        assertEquals(200, status(createRoute("idem-10", "route-a", 0, 0, 10, 10)));
        assertEquals(200, status(review("idem-11", "route-a", 1, 0)));
        MvcResult conflict = review("idem-11", "route-a", 2, 0);
        assertEquals(409, status(conflict));
        assertTrue(json(conflict).contains("IDEMPOTENCY_CONFLICT"));
        assertEquals(1, countRows("reviews"));
    }

    @Test
    void replaceRouteReplayReturnsSameVersion() throws Exception {
        assertEquals(200, status(createRoute("idem-12", "route-a", 0, 0, 10, 10)));
        MvcResult first = replaceRoute("idem-13", "route-a", 1, 0, 0, 20, 20);
        assertEquals(200, status(first));
        MvcResult replay = replaceRoute("idem-13", "route-a", 1, 0, 0, 20, 20);
        assertEquals(200, status(replay));
        assertEquals(json(first), json(replay));
        assertTrue(json(replay).contains("\"version\":2"));
        // 重放不再次递增版本：再次以版本 2 替换应成功，说明当前版本仍为 2。
        assertEquals(200, status(replaceRoute("idem-14", "route-a", 2, 0, 0, 30, 30)));
    }
}
