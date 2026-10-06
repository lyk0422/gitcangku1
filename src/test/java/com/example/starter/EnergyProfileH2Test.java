package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.EnergyCheckDto;
import com.example.starter.api.dto.EnergyProfileDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合成能量档案的 H2 数据库测试（MODE=MySQL）。
 * 覆盖题干算例主流程、几何命中保留、登记缺项 400、溢出与边界。
 */
@SpringBootTest
class EnergyProfileH2Test {

    @Autowired
    private com.example.starter.service.AirspaceReviewService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void cleanBefore() {
        cleanup();
    }

    @AfterEach
    void cleanAfter() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_segment_energy");
        jdbc.update("DELETE FROM route_alternate_energy");
        jdbc.update("DELETE FROM route_energy_profile");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
    }

    private String rid(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    private static List<RoutePointDto> pts(int... xy) {
        return java.util.stream.IntStream.range(0, xy.length / 2)
                .mapToObj(i -> new RoutePointDto(xy[2 * i], xy[2 * i + 1]))
                .toList();
    }

    private ReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    private ReviewResultDto review(String routeId, int routeVersion, long airspaceVersion) {
        return dataOf(service.review(
                new ReviewRequest(routeId, routeVersion, airspaceVersion, "req-" + rid("review"))));
    }

    // ============================ 题干算例主流程 ============================

    @Test
    void energyBlockedAtEndpointWithPointAndDeficit() {
        // 合成能量100、保留10，两段各30，终点备降耗能40 → 终点需要110而阻断
        service.createRoute(new RouteCreateRequest("e1", pts(0, 0, 50, 0, 100, 0),
                new EnergyProfileDto(100L, 10L, List.of(30L, 30L), List.of(0L, 0L, 40L)),
                "tester", "req-" + rid("route")));
        ReviewResultDto dto = review("e1", 1, 0L);

        assertEquals("ENERGY_BLOCKED", dto.conclusion());
        EnergyCheckDto ec = dto.energyCheck();
        assertNotNull(ec);
        assertEquals(100L, ec.departureEnergy());
        assertEquals(10L, ec.reserveEnergy());
        assertFalse(ec.sufficient());
        // 首个不足点为终点（点号 2），缺口 110 - 100 = 10
        assertEquals(2, ec.blockedPointSeq());
        assertEquals(10L, ec.deficit());
        // 每点累计与需求冻结：0/10、30/40、60/110
        assertEquals(3, ec.points().size());
        assertEquals(0L, ec.points().get(0).cumulativeEnergy());
        assertEquals(10L, ec.points().get(0).requiredEnergy());
        assertTrue(ec.points().get(0).sufficient());
        assertEquals(30L, ec.points().get(1).cumulativeEnergy());
        assertEquals(40L, ec.points().get(1).requiredEnergy());
        assertEquals(60L, ec.points().get(2).cumulativeEnergy());
        assertEquals(110L, ec.points().get(2).requiredEnergy());
        assertFalse(ec.points().get(2).sufficient());
        assertTrue(dto.hitZoneIds().isEmpty());
    }

    @Test
    void energyQualifiedStillRequiresGeometryClear() {
        // 终点备降降至 30：终点需要 100，能量合格；但几何命中仍须 BLOCKED
        service.createRoute(new RouteCreateRequest("e2", pts(0, 0, 50, 0, 100, 0),
                new EnergyProfileDto(100L, 10L, List.of(30L, 30L), List.of(0L, 0L, 30L)),
                "tester", "req-" + rid("route")));
        service.createZone(new ZoneCreateRequest("ez1", 40, -5, 60, 5, "req-" + rid("zone")));
        ReviewResultDto dto = review("e2", 1, 1L);

        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(List.of("ez1"), dto.hitZoneIds());
        assertNotNull(dto.energyCheck());
        assertTrue(dto.energyCheck().sufficient());
        assertNull(dto.energyCheck().blockedPointSeq());
        assertNull(dto.energyCheck().deficit());
    }

    @Test
    void oldRouteWithoutProfileReviewedAsBefore() {
        // 旧契约：无能量档案，审查不涉及能量
        service.createRoute(new RouteCreateRequest("e3", pts(0, 0, 100, 0), "req-" + rid("route")));
        ReviewResultDto dto = review("e3", 1, 0L);
        assertEquals("CLEAR", dto.conclusion());
        assertNull(dto.energyCheck());
    }

    // ============================ 登记缺项 400 ============================

    @Test
    void missingOperatorRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("e4", pts(0, 0, 100, 0),
                        new EnergyProfileDto(100L, 10L, List.of(30L), List.of(0L, 0L)),
                        null, "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("ENERGY_OPERATOR_REQUIRED", ex.code());
    }

    @Test
    void blankOperatorRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("e5", pts(0, 0, 100, 0),
                        new EnergyProfileDto(100L, 10L, List.of(30L), List.of(0L, 0L)),
                        "   ", "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("ENERGY_OPERATOR_REQUIRED", ex.code());
    }

    @Test
    void segmentCountMismatchRejected() {
        // 两个坐标段只给一条段耗能
        ApiException ex = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("e6", pts(0, 0, 50, 0, 100, 0),
                        new EnergyProfileDto(100L, 10L, List.of(30L), List.of(0L, 0L, 0L)),
                        "tester", "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("ENERGY_SEGMENT_COUNT_MISMATCH", ex.code());
    }

    @Test
    void alternateCountMismatchRejected() {
        // 三个航点只给两个备降
        ApiException ex = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("e7", pts(0, 0, 50, 0, 100, 0),
                        new EnergyProfileDto(100L, 10L, List.of(30L, 30L), List.of(0L, 0L)),
                        "tester", "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("ENERGY_ALTERNATE_COUNT_MISMATCH", ex.code());
    }

    @Test
    void tooManySegmentsRejected() {
        // 12 个航点 → 11 段，超过最多 10 段
        int[] xy = new int[24];
        for (int i = 0; i < 12; i++) {
            xy[2 * i] = i * 10;
            xy[2 * i + 1] = 0;
        }
        List<Long> segments = java.util.Collections.nCopies(11, 10L);
        List<Long> alternates = java.util.Collections.nCopies(12, 0L);
        ApiException ex = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("e8", pts(xy),
                        new EnergyProfileDto(1000L, 0L, segments, alternates),
                        "tester", "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("ENERGY_TOO_MANY_SEGMENTS", ex.code());
    }

    @Test
    void nonPositiveSegmentEnergyRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("e9", pts(0, 0, 100, 0),
                        new EnergyProfileDto(100L, 10L, List.of(0L), List.of(0L, 0L)),
                        "tester", "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("ENERGY_SEGMENT_NOT_POSITIVE", ex.code());
    }

    @Test
    void negativeAlternateRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("e10", pts(0, 0, 100, 0),
                        new EnergyProfileDto(100L, 10L, List.of(30L), List.of(0L, -1L)),
                        "tester", "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("ENERGY_ALTERNATE_NEGATIVE", ex.code());
    }

    @Test
    void negativeDepartureOrReserveRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("e11", pts(0, 0, 100, 0),
                        new EnergyProfileDto(-1L, 10L, List.of(30L), List.of(0L, 0L)),
                        "tester", "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("ENERGY_NEGATIVE", ex.code());
    }

    @Test
    void overflowRejected() {
        // 两段 Long.MAX_VALUE 求和溢出
        ApiException ex = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("e12", pts(0, 0, 50, 0, 100, 0),
                        new EnergyProfileDto(100L, 0L,
                                List.of(Long.MAX_VALUE, Long.MAX_VALUE), List.of(0L, 0L, 0L)),
                        "tester", "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("ENERGY_OVERFLOW", ex.code());
    }
}
