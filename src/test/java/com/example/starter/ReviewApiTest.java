package com.example.starter;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审核接口测试：CLEAR/BLOCKED 主流程、版本一致性、历史不可变与当前结论 STALE 语义。
 */
class ReviewApiTest extends IntegrationTestBase {

    @Test
    void clearReviewWhenRouteAvoidsAllZones() throws Exception {
        assertEquals(200, status(createZone("req-1", "zone-a", 0, 0, 10, 10)));
        assertEquals(200, status(createRoute("req-2", "route-a", 50, 50, 90, 90)));

        MvcResult result = review("req-3", "route-a", 1, 1);
        assertEquals(200, status(result));
        JsonNode body = objectMapper.readTree(json(result));
        assertEquals("CLEAR", body.get("conclusion").asText());
        assertEquals(0, body.get("hitZoneIds").size());
        assertEquals(1, body.get("routeVersion").asInt());
        assertEquals(1, body.get("airspaceVersion").asLong());
        assertEquals(1, countRows("reviews"));
    }

    @Test
    void blockedWhenSegmentCrossesZoneWithEndpointsOutside() throws Exception {
        assertEquals(200, status(createZone("req-1", "zone-a", 0, 0, 10, 10)));
        // 航线两端均在区域外，线段横穿区域：仅检查航点会漏判。
        assertEquals(200, status(createRoute("req-2", "route-a", -5, 5, 15, 5)));

        MvcResult result = review("req-3", "route-a", 1, 1);
        assertEquals(200, status(result));
        JsonNode body = objectMapper.readTree(json(result));
        assertEquals("BLOCKED", body.get("conclusion").asText());
        assertEquals(1, body.get("hitZoneIds").size());
        assertEquals("zone-a", body.get("hitZoneIds").get(0).asText());
    }

    @Test
    void blockedWhenSegmentOnlyTouchesBoundary() throws Exception {
        assertEquals(200, status(createZone("req-1", "zone-a", 0, 0, 10, 10)));
        // 线段与区域上边重合接触。
        assertEquals(200, status(createRoute("req-2", "route-a", -5, 10, 15, 10)));

        MvcResult result = review("req-3", "route-a", 1, 1);
        assertEquals(200, status(result));
        assertEquals("BLOCKED", objectMapper.readTree(json(result)).get("conclusion").asText());
    }

    @Test
    void hitZoneIdsAreSortedLexicographicallyAndDeduplicated() throws Exception {
        assertEquals(200, status(createZone("req-1", "z-b", 0, 0, 10, 10)));
        assertEquals(200, status(createZone("req-2", "z-a", 0, 0, 10, 10)));
        assertEquals(200, status(createZone("req-3", "z-aa", 0, 0, 10, 10)));
        // 与航线不相交的区域不应出现。
        assertEquals(200, status(createZone("req-4", "z-far", 500, 500, 600, 600)));
        assertEquals(200, status(createRoute("req-5", "route-a", -5, 5, 15, 5)));

        MvcResult result = review("req-6", "route-a", 1, 4);
        assertEquals(200, status(result));
        JsonNode hits = objectMapper.readTree(json(result)).get("hitZoneIds");
        assertEquals(3, hits.size());
        assertEquals("z-a", hits.get(0).asText());
        assertEquals("z-aa", hits.get(1).asText());
        assertEquals("z-b", hits.get(2).asText());
    }

    @Test
    void revokedZoneDoesNotBlock() throws Exception {
        assertEquals(200, status(createZone("req-1", "zone-a", 0, 0, 10, 10)));
        assertEquals(200, status(revokeZone("req-2", "zone-a")));
        assertEquals(200, status(createRoute("req-3", "route-a", -5, 5, 15, 5)));

        MvcResult result = review("req-4", "route-a", 1, 2);
        assertEquals(200, status(result));
        assertEquals("CLEAR", objectMapper.readTree(json(result)).get("conclusion").asText());
    }

    @Test
    void staleAirspaceVersionRejectedWith409() throws Exception {
        assertEquals(200, status(createZone("req-1", "zone-a", 0, 0, 10, 10)));
        assertEquals(200, status(createRoute("req-2", "route-a", 50, 50, 90, 90)));
        // 提交旧的空域版本 0，当前为 1。
        MvcResult result = review("req-3", "route-a", 1, 0);
        assertEquals(409, status(result));
        assertTrue(json(result).contains("AIRSPACE_VERSION_MISMATCH"));
        // 失败不产生审核记录，也不占用幂等键（此前两个成功写操作各占一键）。
        assertEquals(0, countRows("reviews"));
        assertEquals(2, countRows("idempotency_keys"));
    }

    @Test
    void staleRouteVersionRejectedWith409() throws Exception {
        assertEquals(200, status(createRoute("req-1", "route-a", 0, 0, 10, 10)));
        assertEquals(200, status(replaceRoute("req-2", "route-a", 1, 0, 0, 20, 20)));
        MvcResult result = review("req-3", "route-a", 1, 0);
        assertEquals(409, status(result));
        assertTrue(json(result).contains("ROUTE_VERSION_MISMATCH"));
        assertEquals(0, countRows("reviews"));
    }

    @Test
    void reviewUnknownRouteReturns404() throws Exception {
        assertEquals(404, status(review("req-1", "route-missing", 1, 0)));
    }

    @Test
    void historyKeepsOriginalConclusionAfterZoneChange() throws Exception {
        assertEquals(200, status(createRoute("req-1", "route-a", -5, 5, 15, 5)));
        MvcResult created = review("req-2", "route-a", 1, 0);
        assertEquals(200, status(created));
        String reviewId = objectMapper.readTree(json(created)).get("reviewId").asText();
        assertEquals("CLEAR", objectMapper.readTree(json(created)).get("conclusion").asText());

        // 新增阻断性禁飞区后，历史结论保持不变。
        assertEquals(200, status(createZone("req-3", "zone-a", 0, 0, 10, 10)));
        MvcResult history = getReview(reviewId);
        assertEquals(200, status(history));
        JsonNode body = objectMapper.readTree(json(history));
        assertEquals("CLEAR", body.get("conclusion").asText());
        assertEquals(0, body.get("airspaceVersion").asLong());
    }

    @Test
    void currentReviewBecomesStaleAfterZoneChange() throws Exception {
        assertEquals(200, status(createRoute("req-1", "route-a", 50, 50, 90, 90)));
        assertEquals(200, status(review("req-2", "route-a", 1, 0)));

        MvcResult current = getCurrentReview("route-a");
        assertEquals(200, status(current));
        assertEquals("CURRENT", objectMapper.readTree(json(current)).get("status").asText());

        assertEquals(200, status(createZone("req-3", "zone-a", 0, 0, 10, 10)));
        MvcResult stale = getCurrentReview("route-a");
        assertEquals(200, status(stale));
        JsonNode body = objectMapper.readTree(json(stale));
        assertEquals("STALE", body.get("status").asText());
        assertTrue(body.get("review").isNull());
    }

    @Test
    void currentReviewBecomesStaleAfterRouteReplace() throws Exception {
        assertEquals(200, status(createRoute("req-1", "route-a", 50, 50, 90, 90)));
        assertEquals(200, status(review("req-2", "route-a", 1, 0)));
        assertEquals(200, status(replaceRoute("req-3", "route-a", 1, 60, 60, 80, 80)));

        MvcResult stale = getCurrentReview("route-a");
        assertEquals("STALE", objectMapper.readTree(json(stale)).get("status").asText());

        // 用新版本重新审核后恢复 CURRENT。
        assertEquals(200, status(review("req-4", "route-a", 2, 0)));
        MvcResult current = getCurrentReview("route-a");
        JsonNode body = objectMapper.readTree(json(current));
        assertEquals("CURRENT", body.get("status").asText());
        assertEquals(2, body.get("review").get("routeVersion").asInt());
    }

    @Test
    void currentReviewIsStaleWhenNeverReviewed() throws Exception {
        assertEquals(200, status(createRoute("req-1", "route-a", 50, 50, 90, 90)));
        MvcResult result = getCurrentReview("route-a");
        assertEquals(200, status(result));
        assertEquals("STALE", objectMapper.readTree(json(result)).get("status").asText());
    }

    @Test
    void currentReviewOfUnknownRouteReturns404() throws Exception {
        assertEquals(404, status(getCurrentReview("route-missing")));
    }

    @Test
    void unknownReviewIdReturns404() throws Exception {
        assertEquals(404, status(getReview("no-such-review")));
    }
}
