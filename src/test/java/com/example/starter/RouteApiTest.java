package com.example.starter;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 航线创建/替换接口测试：主流程、点列约束、版本校验与错误语义。
 */
class RouteApiTest extends IntegrationTestBase {

    @Test
    void createRouteStartsAtVersionOne() throws Exception {
        MvcResult result = createRoute("req-r1", "route-a", 0, 0, 100, 100);
        assertEquals(200, status(result));
        assertTrue(json(result).contains("\"version\":1"));
    }

    @Test
    void createRouteRejectsInvalidPointLists() throws Exception {
        // 少于 2 个点。
        assertEquals(400, status(createRoute("req-r2", "route-b", 1, 1)));
        // 超过 50 个点。
        MvcResult tooMany = postJson("/api/routes",
                new com.example.starter.api.dto.CreateRouteRequest("req-r3", "route-c",
                        manyPoints(51)));
        assertEquals(400, status(tooMany));
        // 全部点相同。
        MvcResult identical = postJson("/api/routes",
                new com.example.starter.api.dto.CreateRouteRequest("req-r4", "route-d",
                        repeatedPoints(7, 7, 3)));
        assertEquals(400, status(identical));
        assertTrue(json(identical).contains("ROUTE_POINTS_IDENTICAL"));
        // 坐标越界。
        assertEquals(400, status(createRoute("req-r5", "route-e", 0, 0, 100001, 0)));
        assertEquals(0, countRows("routes"));
    }

    @Test
    void duplicateRouteIdRejected() throws Exception {
        assertEquals(200, status(createRoute("req-r6", "route-f", 0, 0, 10, 10)));
        MvcResult duplicate = createRoute("req-r7", "route-f", 20, 20, 30, 30);
        assertEquals(409, status(duplicate));
        assertTrue(json(duplicate).contains("ROUTE_EXISTS"));
        assertEquals(1, countRows("routes"));
    }

    @Test
    void replaceRouteIncrementsVersion() throws Exception {
        assertEquals(200, status(createRoute("req-r8", "route-g", 0, 0, 10, 10)));
        MvcResult replaced = replaceRoute("req-r9", "route-g", 1, 0, 0, 50, 50, 100, 0);
        assertEquals(200, status(replaced));
        assertTrue(json(replaced).contains("\"version\":2"));
    }

    @Test
    void replaceRouteWithStaleVersionRejected() throws Exception {
        assertEquals(200, status(createRoute("req-r10", "route-h", 0, 0, 10, 10)));
        MvcResult stale = replaceRoute("req-r11", "route-h", 2, 0, 0, 50, 50);
        assertEquals(409, status(stale));
        assertTrue(json(stale).contains("ROUTE_VERSION_MISMATCH"));
        // 失败不产生版本变化。
        MvcResult ok = replaceRoute("req-r12", "route-h", 1, 0, 0, 50, 50);
        assertEquals(200, status(ok));
        assertTrue(json(ok).contains("\"version\":2"));
    }

    @Test
    void replaceUnknownRouteReturns404() throws Exception {
        assertEquals(404, status(replaceRoute("req-r13", "route-missing", 1, 0, 0, 10, 10)));
    }

    @Test
    void replaceRouteValidatesPoints() throws Exception {
        assertEquals(200, status(createRoute("req-r14", "route-i", 0, 0, 10, 10)));
        assertEquals(400, status(replaceRoute("req-r15", "route-i", 1, 5, 5)));
        MvcResult identical = putJson("/api/routes/route-i",
                new com.example.starter.api.dto.ReplaceRouteRequest("req-r16", 1,
                        repeatedPoints(3, 3, 4)));
        assertEquals(400, status(identical));
    }
}
