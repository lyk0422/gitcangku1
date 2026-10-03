package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BatchReviewItemDto;
import com.example.starter.api.dto.BatchReviewRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyCreateRequest;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.ReviewDiagnosticsDto;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.WeatherForecastEntryDto;
import com.example.starter.api.dto.WeatherForecastUploadRequest;
import com.example.starter.api.dto.WeatherForecastUploadResult;
import com.example.starter.api.dto.WeatherMinimaConfigRequest;
import com.example.starter.api.dto.WeatherMinimaDetailDto;
import com.example.starter.api.dto.WeatherViolationDto;
import com.example.starter.api.dto.WeatherWindowDto;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.api.dto.ZoneRevokeRequest;
import com.example.starter.service.AirspaceReviewService;
import com.example.starter.service.WeatherService;
import com.fasterxml.jackson.core.type.TypeReference;
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
 * 空域气象最低标准业务的 H2 数据库测试（MODE=MySQL）。
 * 覆盖主流程（CLEAR / WEATHER_BLOCKED）、422 失败分支（负值、时段重叠、预测缺失）、
 * 批量审查整批回滚、占用与 STALE 边界、幂等重放与异参冲突、只读查询不改状态。
 */
@SpringBootTest
class WeatherServiceH2Test {

    @Autowired
    private AirspaceReviewService reviewService;
    @Autowired
    private WeatherService weatherService;
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
        jdbc.update("DELETE FROM occupancy");
        jdbc.update("DELETE FROM weather_forecast");
        jdbc.update("DELETE FROM weather_minima");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE weather_meta SET weather_version = 0 WHERE id = 1");
    }

    private String rid(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    private static List<RoutePointDto> pts(int... xy) {
        return java.util.stream.IntStream.range(0, xy.length / 2)
                .mapToObj(i -> new RoutePointDto(xy[2 * i], xy[2 * i + 1]))
                .toList();
    }

    private <T> T data(MutationResponse resp, Class<T> type) {
        return objectMapper.convertValue(resp.data(), type);
    }

    private long airspaceVersion() {
        Long v = jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class);
        return v == null ? 0L : v;
    }

    private long weatherVersion() {
        Long v = jdbc.queryForObject(
                "SELECT weather_version FROM weather_meta WHERE id = 1", Long.class);
        return v == null ? 0L : v;
    }

    private long count(String table) {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return v == null ? 0L : v;
    }

    /** 建一个已撤销区域（保留为可配置气象标准的区域，不参与禁飞命中）。 */
    private void revokedZone(String zoneId, int xMin, int yMin, int xMax, int yMax) {
        reviewService.createZone(new ZoneCreateRequest(zoneId, xMin, yMin, xMax, yMax, rid("zone")));
        reviewService.revokeZone(new ZoneRevokeRequest(zoneId, rid("revoke")));
    }

    private void configMinima(String zoneId, long start, long end, int minVis, int minCloud) {
        weatherService.configureMinima(new WeatherMinimaConfigRequest(
                rid("minima"), "op-a", zoneId,
                List.of(new WeatherWindowDto(start, end, minVis, minCloud))));
    }

    private long uploadForecast(String zoneId, long start, long end, int vis, int cloud) {
        MutationResponse resp = weatherService.uploadForecast(new WeatherForecastUploadRequest(
                rid("forecast"), "op-a",
                List.of(new WeatherForecastEntryDto(zoneId, start, end, vis, cloud))));
        return data(resp, WeatherForecastUploadResult.class).forecastVersion();
    }
    // ============================ 主流程 ============================

    @Test
    void weatherClearThenWeatherBlockedWithViolationDetails() {
        revokedZone("z1", 40, 5, 60, 15);
        reviewService.createRoute(new RouteCreateRequest("r1", pts(0, 10, 100, 10), rid("route")));
        configMinima("z1", 1000L, 2000L, 1000, 300);
        assertEquals(1L, weatherVersion());
        long fv = uploadForecast("z1", 1000L, 2000L, 1500, 500);
        assertEquals(2L, fv);

        // 预测达标 → CLEAR，气象版本固化进结果
        MutationResponse clear = reviewService.review(
                new ReviewRequest("r1", 1, airspaceVersion(), rid("review"), fv));
        ReviewResultDto clearDto = data(clear, ReviewResultDto.class);
        assertEquals("CLEAR", clearDto.conclusion());
        assertEquals(fv, clearDto.weatherVersion());
        assertNotNull(clearDto.weatherViolations());
        assertTrue(clearDto.weatherViolations().isEmpty());
        assertTrue(clearDto.current());

        // 新预测低于标准：旧气象版本审查 409；新版本 WEATHER_BLOCKED 且含实际/要求/差额
        long fv2 = uploadForecast("z1", 1000L, 2000L, 800, 100);
        ApiException oldVersion = assertThrows(ApiException.class, () -> reviewService.review(
                new ReviewRequest("r1", 1, airspaceVersion(), rid("review"), fv)));
        assertEquals(HttpStatus.CONFLICT, oldVersion.status());

        MutationResponse blocked = reviewService.review(
                new ReviewRequest("r1", 1, airspaceVersion(), rid("review"), fv2));
        ReviewResultDto blockedDto = data(blocked, ReviewResultDto.class);
        assertEquals("WEATHER_BLOCKED", blockedDto.conclusion());
        assertEquals(2, blockedDto.weatherViolations().size());
        WeatherViolationDto vis = blockedDto.weatherViolations().get(0);
        assertEquals("visibility", vis.field());
        assertEquals(800, vis.actual());
        assertEquals(1000, vis.required());
        assertEquals(200, vis.deficit());
        WeatherViolationDto cloud = blockedDto.weatherViolations().get(1);
        assertEquals("cloudBase", cloud.field());
        assertEquals(100, cloud.actual());
        assertEquals(300, cloud.required());
        assertEquals(200, cloud.deficit());

        // 历史查询保留原结论与违规明细，current 为 null
        ReviewResultDto history = reviewService.getReview(blockedDto.reviewId());
        assertEquals("WEATHER_BLOCKED", history.conclusion());
        assertNull(history.current());
        assertEquals(2, history.weatherViolations().size());
    }

    // ============================ 422 失败分支 ============================

    @Test
    void negativeMinimaReturns422AndKeepsState() {
        revokedZone("z1", 40, 5, 60, 15);
        long dedupBefore = count("request_dedup");
        ApiException ex = assertThrows(ApiException.class, () -> weatherService.configureMinima(
                new WeatherMinimaConfigRequest(rid("minima"), "op-a", "z1",
                        List.of(new WeatherWindowDto(1000L, 2000L, -1, 300)))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("INVALID_MINIMA_VALUE", ex.code());
        // 失败不留半成品、不推进版本、不占键（去重记录数与操作前一致）
        assertEquals(0L, weatherVersion());
        assertEquals(0L, count("weather_minima"));
        assertEquals(dedupBefore, count("request_dedup"));
    }

    @Test
    void overlappingWindowsReturns422() {
        revokedZone("z1", 40, 5, 60, 15);
        ApiException ex = assertThrows(ApiException.class, () -> weatherService.configureMinima(
                new WeatherMinimaConfigRequest(rid("minima"), "op-a", "z1",
                        List.of(new WeatherWindowDto(1000L, 3000L, 1000, 300),
                                new WeatherWindowDto(2000L, 4000L, 1000, 300)))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("WINDOW_OVERLAP", ex.code());
        assertEquals(0L, count("weather_minima"));
        assertEquals(0L, weatherVersion());
    }

    @Test
    void adjacentWindowsAreAllowed() {
        revokedZone("z1", 40, 5, 60, 15);
        // 左闭右开：[1000,2000) 与 [2000,3000) 相邻不重叠
        MutationResponse resp = weatherService.configureMinima(new WeatherMinimaConfigRequest(
                rid("minima"), "op-a", "z1",
                List.of(new WeatherWindowDto(2000L, 3000L, 1000, 300),
                        new WeatherWindowDto(1000L, 2000L, 800, 200))));
        assertFalse(resp.replayed());
        WeatherMinimaDetailDto detail = weatherService.getMinimaDetail("z1");
        assertEquals(2, detail.windows().size());
        // 明细按起点升序，排序稳定
        assertEquals(1000L, detail.windows().get(0).startUtc());
        assertEquals(2000L, detail.windows().get(1).startUtc());
    }

    @Test
    void invalidWindowReturns422() {
        revokedZone("z1", 40, 5, 60, 15);
        ApiException ex = assertThrows(ApiException.class, () -> weatherService.configureMinima(
                new WeatherMinimaConfigRequest(rid("minima"), "op-a", "z1",
                        List.of(new WeatherWindowDto(2000L, 2000L, 1000, 300)))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("INVALID_WINDOW", ex.code());
    }

    @Test
    void forecastMissingReturns422AndReleasesRequestId() {
        revokedZone("z1", 40, 5, 60, 15);
        reviewService.createRoute(new RouteCreateRequest("r1", pts(0, 10, 100, 10), rid("route")));
        configMinima("z1", 1000L, 2000L, 1000, 300);

        // 没有上传预测 → 审查 422，且不创建审查记录、不占用 requestId
        long dedupBefore = count("request_dedup");
        String requestId = rid("review");
        ApiException ex = assertThrows(ApiException.class, () -> reviewService.review(
                new ReviewRequest("r1", 1, airspaceVersion(), requestId, weatherVersion())));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("FORECAST_MISSING", ex.code());
        assertEquals(0L, count("review"));
        assertEquals(dedupBefore, count("request_dedup"));

        // 补上预测后，同一 requestId（参数不同）可以成功：失败未占键
        long fv = uploadForecast("z1", 1000L, 2000L, 1500, 500);
        MutationResponse ok = reviewService.review(
                new ReviewRequest("r1", 1, airspaceVersion(), requestId, fv));
        assertFalse(ok.replayed());
        assertEquals("CLEAR", data(ok, ReviewResultDto.class).conclusion());
    }
    // ============================ STALE 与占用 ============================

    @Test
    void weatherRevisionMarksStaleButKeepsEvidenceAndRejectsOccupancy() {
        revokedZone("z1", 40, 5, 60, 15);
        reviewService.createRoute(new RouteCreateRequest("r1", pts(0, 10, 100, 10), rid("route")));
        configMinima("z1", 1000L, 2000L, 1000, 300);
        long fv = uploadForecast("z1", 1000L, 2000L, 1500, 500);
        MutationResponse clear = reviewService.review(
                new ReviewRequest("r1", 1, airspaceVersion(), rid("review"), fv));
        ReviewResultDto clearDto = data(clear, ReviewResultDto.class);
        assertEquals("CLEAR", reviewService.getCurrentReview("r1").conclusion());

        // 气象修订（新预测上传）只影响新审查：旧 CLEAR 不改写但标记 STALE
        uploadForecast("z1", 1000L, 2000L, 1500, 500);
        ReviewResultDto current = reviewService.getCurrentReview("r1");
        assertEquals("STALE", current.conclusion());
        assertFalse(current.current());
        assertEquals("CLEAR", reviewService.getReview(clearDto.reviewId()).conclusion());

        // 诊断：保留原结论与固化快照，逐项给出审查时版本与当前版本
        ReviewDiagnosticsDto diag = weatherService.getDiagnostics(clearDto.reviewId());
        assertEquals("CLEAR", diag.conclusion());
        assertEquals("STALE", diag.effective());
        assertEquals(fv, diag.weatherVersionAtReview());
        assertEquals(weatherVersion(), diag.weatherVersionCurrent());
        assertEquals(1, diag.routeVersionAtReview());
        assertEquals(1, diag.routeVersionCurrent());
        assertNotNull(diag.weatherSnapshot());
        assertEquals(fv, diag.weatherSnapshot().path("weatherVersion").asLong());

        // 占用拒绝使用 STALE 结果
        ApiException ex = assertThrows(ApiException.class, () -> weatherService.createOccupancy(
                new OccupancyCreateRequest(rid("occ"), "op-a", clearDto.reviewId())));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("OCCUPANCY_REVIEW_STALE", ex.code());
        assertEquals(0L, count("occupancy"));
    }

    @Test
    void occupancyUsesFixedWeatherVersionAndReplays() {
        revokedZone("z1", 40, 5, 60, 15);
        reviewService.createRoute(new RouteCreateRequest("r1", pts(0, 10, 100, 10), rid("route")));
        configMinima("z1", 1000L, 2000L, 1000, 300);
        long fv = uploadForecast("z1", 1000L, 2000L, 1500, 500);
        MutationResponse clear = reviewService.review(
                new ReviewRequest("r1", 1, airspaceVersion(), rid("review"), fv));
        ReviewResultDto clearDto = data(clear, ReviewResultDto.class);

        // 创建占用：必须使用审查时固化的气象版本
        String occReq = rid("occ");
        MutationResponse occ = weatherService.createOccupancy(
                new OccupancyCreateRequest(occReq, "op-a", clearDto.reviewId()));
        OccupancyResult occResult = data(occ, OccupancyResult.class);
        assertEquals(clearDto.reviewId(), occResult.reviewId());
        assertEquals(fv, occResult.weatherVersion());
        assertEquals(1, occResult.routeVersion());
        assertEquals(airspaceVersion(), occResult.airspaceVersion());

        // 同键同参重放：返回首次完整结果快照
        MutationResponse replay = weatherService.createOccupancy(
                new OccupancyCreateRequest(occReq, "op-a", clearDto.reviewId()));
        assertTrue(replay.replayed());
        assertEquals(occResult.occupancyId(), data(replay, OccupancyResult.class).occupancyId());
        assertEquals(1L, count("occupancy"));

        // 同键异参 → 409
        ApiException mismatch = assertThrows(ApiException.class, () -> weatherService.createOccupancy(
                new OccupancyCreateRequest(occReq, "op-b", clearDto.reviewId())));
        assertEquals(HttpStatus.CONFLICT, mismatch.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", mismatch.code());

        // 占用明细查询只读且内容一致
        OccupancyResult fetched = weatherService.getOccupancy(occResult.occupancyId());
        assertEquals(occResult.occupancyId(), fetched.occupancyId());
        assertEquals(fv, fetched.weatherVersion());
    }

    @Test
    void occupancyRejectsNonClearReview() {
        // 有效禁飞区命中 → BLOCKED，占用 409
        reviewService.createZone(new ZoneCreateRequest("z1", 40, 5, 60, 15, rid("zone")));
        reviewService.createRoute(new RouteCreateRequest("r1", pts(0, 10, 100, 10), rid("route")));
        MutationResponse blocked = reviewService.review(
                new ReviewRequest("r1", 1, airspaceVersion(), rid("review")));
        ReviewResultDto blockedDto = data(blocked, ReviewResultDto.class);
        assertEquals("BLOCKED", blockedDto.conclusion());
        ApiException ex = assertThrows(ApiException.class, () -> weatherService.createOccupancy(
                new OccupancyCreateRequest(rid("occ"), "op-a", blockedDto.reviewId())));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("OCCUPANCY_NOT_CLEAR", ex.code());
        assertEquals(0L, count("occupancy"));
    }
    // ============================ 批量审查 ============================

    @Test
    void batchReviewAllOrNothingOnMissingForecast() {
        revokedZone("z1", 40, 5, 60, 15);
        revokedZone("z2", 40, 15, 60, 25);
        reviewService.createRoute(new RouteCreateRequest("r1", pts(0, 10, 100, 10), rid("route")));
        reviewService.createRoute(new RouteCreateRequest("r2", pts(0, 20, 100, 20), rid("route")));
        configMinima("z1", 1000L, 2000L, 1000, 300);
        configMinima("z2", 1000L, 2000L, 1000, 300);
        // 只上传 z1 的预测，z2 缺预测
        long fv = uploadForecast("z1", 1000L, 2000L, 1500, 500);

        long dedupBefore = count("request_dedup");
        String batchReq = rid("batch");
        long airspace = airspaceVersion();
        ApiException ex = assertThrows(ApiException.class, () -> reviewService.reviewBatch(
                new BatchReviewRequest(batchReq, "op-a", List.of(
                        new BatchReviewItemDto("r1", 1, airspace, fv),
                        new BatchReviewItemDto("r2", 1, airspace, fv)))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("FORECAST_MISSING", ex.code());
        // 整批不创建结果，失败不占键（去重记录数与批量操作前一致）
        assertEquals(0L, count("review"));
        assertEquals(dedupBefore, count("request_dedup"));

        // 补齐 z2 预测后同一 requestId 整批成功，结果按提交顺序返回
        long fv2 = uploadForecast("z2", 1000L, 2000L, 100, 500);
        MutationResponse ok = reviewService.reviewBatch(
                new BatchReviewRequest(batchReq, "op-a", List.of(
                        new BatchReviewItemDto("r1", 1, airspace, fv2),
                        new BatchReviewItemDto("r2", 1, airspace, fv2))));
        List<ReviewResultDto> results = objectMapper.convertValue(ok.data(),
                new TypeReference<List<ReviewResultDto>>() { });
        assertEquals(2, results.size());
        assertEquals("r1", results.get(0).routeId());
        assertEquals("CLEAR", results.get(0).conclusion());
        assertEquals("r2", results.get(1).routeId());
        // z2 预测能见度 100 低于标准 1000 → WEATHER_BLOCKED
        assertEquals("WEATHER_BLOCKED", results.get(1).conclusion());
        assertEquals(1, results.get(1).weatherViolations().size());
        assertEquals(2L, count("review"));
    }

    // ============================ 幂等与只读 ============================

    @Test
    void minimaReplacementIsAtomicAndReplayNormalizesWindowOrder() {
        revokedZone("z1", 40, 5, 60, 15);
        String reqId = rid("minima");
        MutationResponse first = weatherService.configureMinima(new WeatherMinimaConfigRequest(
                reqId, "op-a", "z1",
                List.of(new WeatherWindowDto(1000L, 2000L, 1000, 300),
                        new WeatherWindowDto(2000L, 3000L, 900, 200))));
        assertEquals(1L, weatherVersion());

        // 整体替换：新集合完全替代旧集合
        weatherService.configureMinima(new WeatherMinimaConfigRequest(
                rid("minima"), "op-a", "z1",
                List.of(new WeatherWindowDto(5000L, 6000L, 700, 100))));
        WeatherMinimaDetailDto detail = weatherService.getMinimaDetail("z1");
        assertEquals(1, detail.windows().size());
        assertEquals(5000L, detail.windows().get(0).startUtc());
        assertEquals(2L, weatherVersion());

        // 同键同参（窗口书写顺序不同）重放首次响应，不再次推进版本
        MutationResponse replay = weatherService.configureMinima(new WeatherMinimaConfigRequest(
                reqId, "op-a", "z1",
                List.of(new WeatherWindowDto(2000L, 3000L, 900, 200),
                        new WeatherWindowDto(1000L, 2000L, 1000, 300))));
        assertTrue(replay.replayed());
        assertEquals(2L, weatherVersion());

        // 同键异参 → 409
        ApiException mismatch = assertThrows(ApiException.class, () -> weatherService.configureMinima(
                new WeatherMinimaConfigRequest(reqId, "op-a", "z1",
                        List.of(new WeatherWindowDto(1000L, 2000L, 1000, 300)))));
        assertEquals(HttpStatus.CONFLICT, mismatch.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", mismatch.code());
    }

    @Test
    void readOnlyQueriesDoNotChangeState() {
        revokedZone("z1", 40, 5, 60, 15);
        reviewService.createRoute(new RouteCreateRequest("r1", pts(0, 10, 100, 10), rid("route")));
        configMinima("z1", 1000L, 2000L, 1000, 300);
        long fv = uploadForecast("z1", 1000L, 2000L, 1500, 500);
        MutationResponse clear = reviewService.review(
                new ReviewRequest("r1", 1, airspaceVersion(), rid("review"), fv));
        ReviewResultDto clearDto = data(clear, ReviewResultDto.class);
        MutationResponse occ = weatherService.createOccupancy(
                new OccupancyCreateRequest(rid("occ"), "op-a", clearDto.reviewId()));
        OccupancyResult occResult = data(occ, OccupancyResult.class);

        long reviewsBefore = count("review");
        long dedupBefore = count("request_dedup");
        long weatherBefore = weatherVersion();
        // 明细、历史、诊断、当前结论、版本查询全部只读
        weatherService.getMinimaDetail("z1");
        weatherService.getForecastDetail(fv);
        weatherService.getDiagnostics(clearDto.reviewId());
        weatherService.getOccupancy(occResult.occupancyId());
        weatherService.getWeatherVersion();
        reviewService.getReview(clearDto.reviewId());
        reviewService.getCurrentReview("r1");
        assertEquals(reviewsBefore, count("review"));
        assertEquals(dedupBefore, count("request_dedup"));
        assertEquals(weatherBefore, weatherVersion());

        // 预测明细按版本固化：截至 fv 可见 1 条
        assertEquals(1, weatherService.getForecastDetail(fv).entries().size());
        ApiException notFound = assertThrows(ApiException.class,
                () -> weatherService.getForecastDetail(fv + 100));
        assertEquals(HttpStatus.NOT_FOUND, notFound.status());
    }
}