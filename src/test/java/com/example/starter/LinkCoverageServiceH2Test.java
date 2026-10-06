package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.CoverageIntervalDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.SegmentLinkDto;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 航段通信覆盖审查的 H2 数据库测试（MODE=MySQL）。
 * 覆盖题干合成样例主流程、400 失败分支、禁飞优先、无档案不适用与版本失效。
 */
@SpringBootTest
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
        jdbc.update("DELETE FROM route_segment_coverage");
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

    private static SegmentLinkDto seg(long durationMs, int... fromTo) {
        List<CoverageIntervalDto> coverage = new ArrayList<>();
        for (int i = 0; i < fromTo.length; i += 2) {
            coverage.add(new CoverageIntervalDto(fromTo[i], fromTo[i + 1]));
        }
        return new SegmentLinkDto(durationMs, coverage);
    }

    /** 题干合成样例航线：三段点、两段各 10 秒；cov1/cov2 为两段覆盖起始刻度。 */
    private RouteCreateRequest synthesisRoute(String routeId, int cov1From, int cov1To,
                                              int cov2From, int cov2To, String requestId) {
        return new RouteCreateRequest(routeId, pts(0, 0, 0, 10, 0, 20),
                List.of(seg(10000, cov1From, cov1To), seg(10000, cov2From, cov2To)),
                "tester", requestId);
    }

    private ReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    // ============================ 主流程（题干合成样例） ============================

    @Test
    void junctionLossExactlyFiveSecondsIsAllowedAndEvidenceSaved() {
        // 两段各 10 秒：第一段仅覆盖前 80%，第二段仅覆盖后 70%；相接失联 2+3=5 秒，正好允许
        service.createRoute(synthesisRoute("lk1", 0, 8000, 3000, 10000, "req-" + rid("route")));
        MutationResponse resp = service.review(
                new ReviewRequest("lk1", 1, 0L, "req-" + rid("review")));
        ReviewResultDto dto = dataOf(resp);
        assertEquals("CLEAR", dto.conclusion());
        assertTrue(dto.current());
        // 保存合并并集与跨段缺口
        assertEquals(2, dto.linkUnion().size());
        assertEquals(0, dto.linkUnion().get(0).segmentIndex());
        assertEquals(0, dto.linkUnion().get(0).intervals().get(0).from());
        assertEquals(8000, dto.linkUnion().get(0).intervals().get(0).to());
        assertEquals(3000, dto.linkUnion().get(1).intervals().get(0).from());
        assertEquals(1, dto.linkGaps().size());
        assertEquals(0, dto.linkGaps().get(0).fromSegment());
        assertEquals(1, dto.linkGaps().get(0).toSegment());
        assertEquals("5000", dto.linkGaps().get(0).lossMillis());
        // 历史查询保留同样的并集、缺口与原结论，后续变更不改历史
        ReviewResultDto history = service.getReview(dto.reviewId());
        assertEquals("CLEAR", history.conclusion());
        assertEquals(dto.linkUnion(), history.linkUnion());
        assertEquals(dto.linkGaps(), history.linkGaps());
        assertNull(history.current());
    }

    @Test
    void junctionLossFivePointOneSecondsIsLinkBlocked() {
        // 第二段改为仅覆盖后 69%：相接失联 2+3.1=5.1 秒 → LINK_BLOCKED
        service.createRoute(synthesisRoute("lk2", 0, 8000, 3100, 10000, "req-" + rid("route")));
        MutationResponse resp = service.review(
                new ReviewRequest("lk2", 1, 0L, "req-" + rid("review")));
        ReviewResultDto dto = dataOf(resp);
        assertEquals("LINK_BLOCKED", dto.conclusion());
        assertTrue(dto.hitZoneIds().isEmpty());
        assertEquals("5100", dto.linkGaps().get(0).lossMillis());
        // 当前查询同样返回 LINK_BLOCKED
        assertEquals("LINK_BLOCKED", service.getCurrentReview("lk2").conclusion());
    }

    @Test
    void emptyCoverageMeansWholeSegmentLostAndBlocked() {
        // 空覆盖合法：单段 10 秒全段失联 → LINK_BLOCKED
        service.createRoute(new RouteCreateRequest("lk3", pts(0, 0, 0, 10),
                List.of(seg(10000)), "tester", "req-" + rid("route")));
        MutationResponse resp = service.review(
                new ReviewRequest("lk3", 1, 0L, "req-" + rid("review")));
        ReviewResultDto dto = dataOf(resp);
        assertEquals("LINK_BLOCKED", dto.conclusion());
        assertEquals("10000", dto.linkGaps().get(0).lossMillis());
        assertTrue(dto.linkUnion().get(0).intervals().isEmpty());
    }

    @Test
    void noFlyHitTakesPrecedenceOverLinkBlocked() {
        // 禁飞命中仍阻断：通信同样超限，结论必须是 BLOCKED 而非 LINK_BLOCKED
        service.createRoute(synthesisRoute("lk4", 0, 8000, 3100, 10000, "req-" + rid("route")));
        service.createZone(new ZoneCreateRequest("lkz", -1, 4, 1, 6, "req-" + rid("zone")));
        MutationResponse resp = service.review(
                new ReviewRequest("lk4", 1, 1L, "req-" + rid("review")));
        ReviewResultDto dto = dataOf(resp);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(List.of("lkz"), dto.hitZoneIds());
        // 通信证据仍然保存
        assertEquals("5100", dto.linkGaps().get(0).lossMillis());
    }

    @Test
    void routeWithoutProfileIsNotSubjectToLinkReview() {
        // 旧无通信档案航线不适用通信审查：只按禁飞判定
        service.createRoute(new RouteCreateRequest("lk5", pts(0, 0, 0, 10), "req-" + rid("route")));
        MutationResponse resp = service.review(
                new ReviewRequest("lk5", 1, 0L, "req-" + rid("review")));
        ReviewResultDto dto = dataOf(resp);
        assertEquals("CLEAR", dto.conclusion());
        assertTrue(dto.linkUnion().isEmpty());
        assertTrue(dto.linkGaps().isEmpty());
    }

    // ============================ 400 失败分支 ============================

    @Test
    void reversedOrOutOfRangeCoverageRejected() {
        ApiException reversed = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("bad1", pts(0, 0, 0, 10),
                        List.of(seg(1000, 5000, 4000)), "tester", "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, reversed.status());
        assertEquals("COVERAGE_INTERVAL_REVERSED", reversed.code());

        ApiException negative = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("bad2", pts(0, 0, 0, 10),
                        List.of(seg(1000, -1, 5000)), "tester", "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, negative.status());
        assertEquals("COVERAGE_INTERVAL_OUT_OF_RANGE", negative.code());

        ApiException tooLarge = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("bad3", pts(0, 0, 0, 10),
                        List.of(seg(1000, 0, 10001)), "tester", "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, tooLarge.status());
        assertEquals("COVERAGE_INTERVAL_OUT_OF_RANGE", tooLarge.code());
    }

    @Test
    void invalidDurationCountLimitAndOperatorRejected() {
        ApiException duration = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("bad4", pts(0, 0, 0, 10),
                        List.of(seg(0, 0, 5000)), "tester", "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, duration.status());
        assertEquals("INVALID_SEGMENT_DURATION", duration.code());

        // 段数必须等于航点数减一（3 点 2 段，只给 1 段）
        ApiException count = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("bad5", pts(0, 0, 0, 10, 0, 20),
                        List.of(seg(1000, 0, 5000)), "tester", "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, count.status());
        assertEquals("SEGMENT_COUNT_MISMATCH", count.code());

        // 覆盖区间最多 10 条
        List<CoverageIntervalDto> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(new CoverageIntervalDto(i, i));
        }
        ApiException limit = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("bad6", pts(0, 0, 0, 10),
                        List.of(new SegmentLinkDto(1000L, eleven)), "tester",
                        "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, limit.status());
        assertEquals("COVERAGE_INTERVAL_LIMIT", limit.code());

        // 携带通信档案的写请求必须带非空操作者
        ApiException operator = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("bad7", pts(0, 0, 0, 10),
                        List.of(seg(1000, 0, 5000)), " ", "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, operator.status());
        assertEquals("OPERATOR_REQUIRED", operator.code());
    }

    @Test
    void failedProfileValidationDoesNotConsumeIdempotencyKey() {
        String requestId = "lk-fail-key";
        ApiException failed = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("lk6", pts(0, 0, 0, 10),
                        List.of(seg(1000, 5000, 4000)), "tester", requestId)));
        assertEquals(HttpStatus.BAD_REQUEST, failed.status());
        // 失败不占键：同键随后可用于合法请求
        MutationResponse ok = service.createRoute(new RouteCreateRequest("lk6",
                pts(0, 0, 0, 10), List.of(seg(1000, 0, 5000)), "tester", requestId));
        assertFalse(ok.replayed());
    }

    // ============================ 版本与 STALE ============================

    @Test
    void replaceCreatesNewVersionAndStalesOldReview() {
        // v1：70% 样例 CLEAR；替换为 69% 档案生成 v2，旧审查 STALE，v2 审查 LINK_BLOCKED
        service.createRoute(synthesisRoute("lk7", 0, 8000, 3000, 10000, "req-" + rid("route")));
        MutationResponse v1Review = service.review(
                new ReviewRequest("lk7", 1, 0L, "req-" + rid("review")));
        assertEquals("CLEAR", dataOf(v1Review).conclusion());

        MutationResponse replaced = service.replaceRoute(new RouteReplaceRequest("lk7", 1,
                pts(0, 0, 0, 10, 0, 20),
                List.of(seg(10000, 0, 8000), seg(10000, 3100, 10000)),
                "tester", "req-" + rid("replace")));
        assertEquals(2, objectMapper.convertValue(replaced.data(), java.util.Map.class)
                .get("version"));

        // 旧审查对当前查询失效；历史结论不变
        assertEquals("STALE", service.getCurrentReview("lk7").conclusion());
        assertEquals("CLEAR", service.getReview(dataOf(v1Review).reviewId()).conclusion());
        // 只能按当前版本审查：旧版本 409
        ApiException stale = assertThrows(ApiException.class, () -> service.review(
                new ReviewRequest("lk7", 1, 0L, "req-" + rid("review"))));
        assertEquals(HttpStatus.CONFLICT, stale.status());
        // 新版本按新档案审查 → LINK_BLOCKED
        MutationResponse v2Review = service.review(
                new ReviewRequest("lk7", 2, 0L, "req-" + rid("review")));
        assertEquals("LINK_BLOCKED", dataOf(v2Review).conclusion());
        assertEquals("5100", dataOf(v2Review).linkGaps().get(0).lossMillis());
    }
}
