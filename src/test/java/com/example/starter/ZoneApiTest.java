package com.example.starter;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 禁飞区创建/撤销接口测试：主流程、参数校验、版本递增与错误语义。
 */
class ZoneApiTest extends IntegrationTestBase {

    @Test
    void createZoneIncrementsAirspaceVersion() throws Exception {
        MvcResult first = createZone("req-z1", "zone-a", 0, 0, 10, 10);
        assertEquals(200, status(first));
        assertTrue(json(first).contains("\"airspaceVersion\":1"));
        assertTrue(json(first).contains("\"active\":true"));

        MvcResult second = createZone("req-z2", "zone-b", 20, 20, 30, 30);
        assertEquals(200, status(second));
        assertTrue(json(second).contains("\"airspaceVersion\":2"));
        assertEquals(2, airspaceVersion());
    }

    @Test
    void createDegenerateZoneRejected() throws Exception {
        assertEquals(400, status(createZone("req-z3", "zone-c", 5, 0, 5, 10)));
        assertEquals(400, status(createZone("req-z4", "zone-d", 0, 8, 10, 8)));
        assertEquals(400, status(createZone("req-z5", "zone-e", 10, 10, 0, 0)));
        assertEquals(0, countRows("zones"));
        assertEquals(0, airspaceVersion());
    }

    @Test
    void createZoneWithOutOfRangeCoordinateRejected() throws Exception {
        assertEquals(400, status(createZone("req-z6", "zone-f", -100001, 0, 10, 10)));
        assertEquals(400, status(createZone("req-z7", "zone-g", 0, 0, 10, 100001)));
        assertEquals(0, countRows("zones"));
    }

    @Test
    void createZoneWithBoundaryCoordinatesAccepted() throws Exception {
        MvcResult result = createZone("req-z8", "zone-h", -100000, -100000, 100000, 100000);
        assertEquals(200, status(result));
    }

    @Test
    void duplicateZoneIdRejected() throws Exception {
        assertEquals(200, status(createZone("req-z9", "zone-i", 0, 0, 10, 10)));
        MvcResult duplicate = createZone("req-z10", "zone-i", 20, 20, 30, 30);
        assertEquals(409, status(duplicate));
        assertTrue(json(duplicate).contains("ZONE_EXISTS"));
        assertEquals(1, countRows("zones"));
    }

    @Test
    void revokeZoneIncrementsVersionAndKeepsRow() throws Exception {
        assertEquals(200, status(createZone("req-z11", "zone-j", 0, 0, 10, 10)));
        MvcResult revoked = revokeZone("req-z12", "zone-j");
        assertEquals(200, status(revoked));
        assertTrue(json(revoked).contains("\"active\":false"));
        assertTrue(json(revoked).contains("\"airspaceVersion\":2"));
        assertEquals(1, countRows("zones"));
        assertEquals(2, airspaceVersion());
    }

    @Test
    void revokeUnknownOrInactiveZoneReturns404() throws Exception {
        assertEquals(404, status(revokeZone("req-z13", "zone-missing")));
        assertEquals(200, status(createZone("req-z14", "zone-k", 0, 0, 10, 10)));
        assertEquals(200, status(revokeZone("req-z15", "zone-k")));
        // 已撤销的禁飞区不能再次撤销。
        assertEquals(404, status(revokeZone("req-z16", "zone-k")));
        assertEquals(2, airspaceVersion());
    }

    @Test
    void revokedZoneIdCannotBeReused() throws Exception {
        assertEquals(200, status(createZone("req-z17", "zone-l", 0, 0, 10, 10)));
        assertEquals(200, status(revokeZone("req-z18", "zone-l")));
        assertEquals(409, status(createZone("req-z19", "zone-l", 20, 20, 30, 30)));
    }

    @Test
    void missingRequestIdRejected() throws Exception {
        MvcResult result = postJson("/api/zones",
                new com.example.starter.api.dto.CreateZoneRequest(null, "zone-m", 0, 0, 10, 10));
        assertEquals(400, status(result));
        assertEquals(0, countRows("zones"));
    }
}
