package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.CoverageIntervalDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.SegmentProfileDto;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 航线通信覆盖审查的 H2 数据库测试（MODE=MySQL）：
 * 主流程、禁飞优先级、旧无档案航线、参数校验、修订与历史不可变。
 */
@SpringBootTest
@DisplayName("链路覆盖审查 H2 主流程与拒绝分支")
class LinkCoverageServiceH2Test {

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
        jdbc.update("DELETE FROM route_segment");
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

    private static SegmentProfileDto seg(int durationMs, int... intervals) {
        List<CoverageIntervalDto> coverage = new ArrayList<>();
        for (int i = 0; i < intervals.length; i += 2) {
            coverage.add(new CoverageIntervalDto(intervals[i], intervals[i + 1]));
        }
        return new SegmentProfileDto(durationMs, coverage);
    }

    private ReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    // ============================ 主流程 ============================

    @Test
    @DisplayName("合成样例：两段各 10 秒，前 80% + 后 70%，相接失联 5 秒正好允许")
    void syntheticTwoSegmentsExactly5000Allowed() {
        service.createRoute(new RouteCreateRequest("link-ok",
                pts(0, 0, 100, 0, 200, 0), "req-" + rid("route"),
                List.of(seg(10000, 0, 8000), seg(10000, 3000, 10000)), "tester"));
        MutationResponse resp = service.review(
                new ReviewRequest("link-ok", 1, 0L, "req-" + rid("review")));
        ReviewResultDto dto = dataOf(resp);
        assertEquals("CLEAR", dto.conclusion());
        assertEquals("5000", dto.maxContinuousLossMs());
        // 跨段缺口连成同一段连续失联
        assertEquals(1, dto.linkGaps().size());
        assertEquals(0, dto.linkGaps().get(0).fromSegment());
        assertEquals(1, dto.linkGaps().get(0).toSegment());
        assertEquals("5000", dto.linkGaps().get(0).lossMs());
        // 合并并集存档
        assertEquals(2, dto.coverageUnion().size());
        assertEquals(List.of(new CoverageIntervalDto(0, 8000)), dto.coverageUnion().get(0).merged());
        assertEquals(List.of(new CoverageIntervalDto(3000, 10000)),
                dto.coverageUnion().get(1).merged());
        // 合并并集与跨段缺口真实落库
        String unionJson = jdbc.queryForObject(
                "SELECT coverage_union FROM review WHERE review_id = ?", String.class, dto.reviewId());
        String gapsJson = jdbc.queryForObject(
                "SELECT link_gaps FROM review WHERE review_id = ?", String.class, dto.reviewId());
        assertNotNull(unionJson);
        assertNotNull(gapsJson);
        assertTrue(unionJson.contains("\"end\":8000"));
        assertTrue(gapsJson.contains("\"lossMs\":\"5000\""));
    }

    @Test
    @DisplayName("合成样例：第二段改后 69%，相接失联 5.1 秒 LINK_BLOCKED")
    void syntheticTwoSegments5100Blocked() {
        service.createRoute(new RouteCreateRequest("link-bad",
                pts(0, 0, 100, 0, 200, 0), "req-" + rid("route"),
                List.of(seg(10000, 0, 8000), seg(10000, 3100, 10000)), "tester"));
        ReviewResultDto dto = dataOf(service.review(
                new ReviewRequest("link-bad", 1, 0L, "req-" + rid("review"))));
        assertEquals("LINK_BLOCKED", dto.conclusion());
        assertEquals("5100", dto.maxContinuousLossMs());
        assertTrue(dto.hitZoneIds().isEmpty());
    }

    @Test
    @DisplayName("禁飞命中仍阻断：优先于链路结论，不被 LINK_BLOCKED 替代")
    void zoneHitTakesPrecedenceOverLinkConclusion() {
        service.createRoute(new RouteCreateRequest("link-zone",
                pts(0, 10, 100, 10, 200, 10), "req-" + rid("route"),
                List.of(seg(10000, 0, 8000), seg(10000, 3100, 10000)), "tester"));
        service.createZone(new ZoneCreateRequest("z-hit", 40, 5, 60, 15, "req-" + rid("zone")));
        ReviewResultDto dto = dataOf(service.review(
                new ReviewRequest("link-zone", 1, 1L, "req-" + rid("review"))));
        // 链路本身超限，但禁飞命中结论必须保留为 BLOCKED
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(List.of("z-hit"), dto.hitZoneIds());
        // 链路存档仍然完整记录
        assertEquals("5100", dto.maxContinuousLossMs());
    }

    @Test
    @DisplayName("旧无通信档案航线不适用链路审查，链路字段为 null")
    void legacyRouteWithoutProfileSkipsLinkEvaluation() {
        service.createRoute(new RouteCreateRequest("legacy",
                pts(0, 0, 100, 0), "req-" + rid("route")));
        ReviewResultDto dto = dataOf(service.review(
                new ReviewRequest("legacy", 1, 0L, "req-" + rid("review"))));
        assertEquals("CLEAR", dto.conclusion());
        assertNull(dto.coverageUnion());
        assertNull(dto.linkGaps());
        assertNull(dto.maxContinuousLossMs());
        String unionJson = jdbc.queryForObject(
                "SELECT coverage_union FROM review WHERE review_id = ?", String.class, dto.reviewId());
        assertNull(unionJson);
    }

    @Test
    @DisplayName("空覆盖合法表示全段失联：6000 毫秒阻断，5000 毫秒允许")
    void emptyCoverageMeansWholeSegmentLost() {
        service.createRoute(new RouteCreateRequest("empty-cov",
                pts(0, 0, 100, 0), "req-" + rid("route"),
                List.of(seg(6000)), "tester"));
        ReviewResultDto blocked = dataOf(service.review(
                new ReviewRequest("empty-cov", 1, 0L, "req-" + rid("review"))));
        assertEquals("LINK_BLOCKED", blocked.conclusion());
        assertEquals("6000", blocked.maxContinuousLossMs());

        service.createRoute(new RouteCreateRequest("empty-cov-ok",
                pts(0, 0, 100, 0), "req-" + rid("route"),
                List.of(seg(5000)), "tester"));
        ReviewResultDto allowed = dataOf(service.review(
                new ReviewRequest("empty-cov-ok", 1, 0L, "req-" + rid("review"))));
        assertEquals("CLEAR", allowed.conclusion());
        assertEquals("5000", allowed.maxContinuousLossMs());
    }

    // ============================ 参数校验（400） ============================

    @Test
    @DisplayName("覆盖区间反向或越界返回 400")
    void reversedOrOutOfBoundCoverageRejected() {
        ApiException reversed = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("bad-1", pts(0, 0, 100, 0), "req-" + rid("route"),
                        List.of(seg(10000, 5000, 4000)), "tester")));
        assertEquals(HttpStatus.BAD_REQUEST, reversed.status());

        ApiException overBound = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("bad-2", pts(0, 0, 100, 0), "req-" + rid("route"),
                        List.of(seg(10000, 0, 10001)), "tester")));
        assertEquals(HttpStatus.BAD_REQUEST, overBound.status());

        ApiException negative = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("bad-3", pts(0, 0, 100, 0), "req-" + rid("route"),
                        List.of(seg(10000, -1, 500)), "tester")));
        assertEquals(HttpStatus.BAD_REQUEST, negative.status());
    }

    @Test
    @DisplayName("段数不匹配、超 10 条区间、非正时长、缺操作者均 400")
    void invalidProfilesRejected() {
        // 2 个点只有 1 段，却登记 2 段
        ApiException count = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("bad-4", pts(0, 0, 100, 0), "req-" + rid("route"),
                        List.of(seg(10000, 0, 10000), seg(10000, 0, 10000)), "tester")));
        assertEquals(HttpStatus.BAD_REQUEST, count.status());

        // 单段 11 条覆盖区间
        int[] eleven = new int[22];
        for (int i = 0; i < 11; i++) {
            eleven[2 * i] = i * 10;
            eleven[2 * i + 1] = i * 10 + 5;
        }
        ApiException tooMany = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("bad-5", pts(0, 0, 100, 0), "req-" + rid("route"),
                        List.of(seg(10000, eleven)), "tester")));
        assertEquals(HttpStatus.BAD_REQUEST, tooMany.status());

        // 时长必须为正整数
        ApiException zeroDuration = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("bad-6", pts(0, 0, 100, 0), "req-" + rid("route"),
                        List.of(seg(0, 0, 10000)), "tester")));
        assertEquals(HttpStatus.BAD_REQUEST, zeroDuration.status());

        // 携带通信档案必须提供非空操作者
        ApiException noOperator = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("bad-7", pts(0, 0, 100, 0), "req-" + rid("route"),
                        List.of(seg(10000, 0, 10000)), " ")));
        assertEquals(HttpStatus.BAD_REQUEST, noOperator.status());
    }

    // ============================ 修订与历史 ============================

    @Test
    @DisplayName("修订生成新版本并标旧审查 STALE，历史存档不被修改")
    void replaceCreatesNewVersionAndKeepsHistoryImmutable() {
        service.createRoute(new RouteCreateRequest("rev-link",
                pts(0, 0, 100, 0, 200, 0), "req-" + rid("route"),
                List.of(seg(10000, 0, 8000), seg(10000, 3000, 10000)), "tester"));
        ReviewResultDto first = dataOf(service.review(
                new ReviewRequest("rev-link", 1, 0L, "req-" + rid("review"))));
        assertEquals("CLEAR", first.conclusion());

        // 修订：第二段改为后 69%，生成版本 2
        service.replaceRoute(new RouteReplaceRequest("rev-link", 1,
                pts(0, 0, 100, 0, 200, 0), "req-" + rid("replace"),
                List.of(seg(10000, 0, 8000), seg(10000, 3100, 10000)), "tester"));
        // 旧审查 STALE
        assertEquals("STALE", service.getCurrentReview("rev-link").conclusion());
        // 旧版本审查 409
        ApiException stale = assertThrows(ApiException.class, () -> service.review(
                new ReviewRequest("rev-link", 1, 0L, "req-" + rid("review"))));
        assertEquals(HttpStatus.CONFLICT, stale.status());
        // 新版本按新档案审查 → LINK_BLOCKED
        ReviewResultDto second = dataOf(service.review(
                new ReviewRequest("rev-link", 2, 0L, "req-" + rid("review"))));
        assertEquals("LINK_BLOCKED", second.conclusion());
        assertEquals("5100", second.maxContinuousLossMs());
        // 历史首次结论与存档不变
        ReviewResultDto history = service.getReview(first.reviewId());
        assertEquals("CLEAR", history.conclusion());
        assertEquals("5000", history.maxContinuousLossMs());
        assertEquals(List.of(new CoverageIntervalDto(3000, 10000)),
                history.coverageUnion().get(1).merged());
    }
}
