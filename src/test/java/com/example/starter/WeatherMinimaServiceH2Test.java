package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BatchReviewItemDto;
import com.example.starter.api.dto.BatchReviewRequest;
import com.example.starter.api.dto.BatchReviewResult;
import com.example.starter.api.dto.DutyAdvanceRequest;
import com.example.starter.api.dto.ForecastEntryDto;
import com.example.starter.api.dto.ForecastUploadRequest;
import com.example.starter.api.dto.ForecastUploadResult;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupationCreateRequest;
import com.example.starter.api.dto.OccupationResult;
import com.example.starter.api.dto.ReviewDiagnosticsDto;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.VersionDiagnosticDto;
import com.example.starter.api.dto.WeatherMinimaConfigRequest;
import com.example.starter.api.dto.WeatherMinimaResult;
import com.example.starter.api.dto.WeatherWindowDto;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.service.AirspaceReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空域气象最低标准与审查时效冻结的 H2 数据库测试（MODE=MySQL）。
 * 覆盖主流程（标准配置/预测上传/审查/占用）、422 失败分支、批量整批事务、
 * STALE 时效诊断、幂等重放与真实并发互斥。
 */
@SpringBootTest
class WeatherMinimaServiceH2Test {

    @Autowired
    private AirspaceReviewService service;
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
        jdbc.update("DELETE FROM occupation");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM weather_forecast");
        jdbc.update("DELETE FROM weather_minima");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE weather_meta SET weather_version = 0, forecast_version = 0, "
                + "duty_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private String rid(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    private static List<RoutePointDto> pts(int... xy) {
        return java.util.stream.IntStream.range(0, xy.length / 2)
                .mapToObj(i -> new RoutePointDto(xy[2 * i], xy[2 * i + 1]))
                .toList();
    }

    private void createRoute(String routeId, int... xy) {
        service.createRoute(new RouteCreateRequest(routeId, pts(xy), "req-" + rid("route")));
    }

    private void createZone(String zoneId, int xMin, int yMin, int xMax, int yMax) {
        service.createZone(new ZoneCreateRequest(zoneId, xMin, yMin, xMax, yMax,
                "req-" + rid("zone")));
    }

    private MutationResponse configMinima(String zoneId, List<WeatherWindowDto> windows) {
        return service.configureMinima(new WeatherMinimaConfigRequest(
                zoneId, windows, "op-test", "req-" + rid("minima")));
    }

    private MutationResponse uploadForecast(List<ForecastEntryDto> entries) {
        return service.uploadForecast(new ForecastUploadRequest(
                entries, "op-test", "req-" + rid("forecast")));
    }

    private ReviewRequest reviewReq(String routeId, int routeVersion, long airspaceVersion,
                                    Long forecastVersion) {
        return new ReviewRequest(routeId, routeVersion, airspaceVersion, forecastVersion,
                "op-test", "req-" + rid("review"));
    }

    private ReviewResultDto reviewData(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    private long weatherVersion() {
        return jdbc.queryForObject(
                "SELECT weather_version FROM weather_meta WHERE id = 1", Long.class);
    }

    private long forecastVersion() {
        return jdbc.queryForObject(
                "SELECT forecast_version FROM weather_meta WHERE id = 1", Long.class);
    }

    private long dutyVersion() {
        return jdbc.queryForObject(
                "SELECT duty_version FROM weather_meta WHERE id = 1", Long.class);
    }

    private int reviewCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class);
    }

    // ============================ 主流程 ============================

    @Test
    void weatherBlockedWhenForecastBelowMinimaWithActualRequiredDeficit() {
        createRoute("wr1", 0, 10, 100, 10);
        createZone("wz1", 40, 5, 60, 15);
        // 08:00~12:00 UTC 最低能见度 5000 米、最低云底 500 米
        configMinima("wz1", List.of(new WeatherWindowDto(480, 720, 5000, 500)));
        assertEquals(1L, weatherVersion());
        // 预测能见度 3000、云底 300，均低于标准
        uploadForecast(List.of(new ForecastEntryDto("wz1", 480, 720, 3000, 300)));
        assertEquals(1L, forecastVersion());

        MutationResponse resp = service.review(reviewReq("wr1", 1, 1L, 1L));
        ReviewResultDto dto = reviewData(resp);
        assertEquals("WEATHER_BLOCKED", dto.conclusion());
        assertTrue(dto.hitZoneIds().isEmpty());
        assertEquals(2, dto.weatherViolations().size());
        // 稳定排序：CLOUD_BASE 在 VISIBILITY 前（按指标名字典序）
        var cloud = dto.weatherViolations().get(0);
        assertEquals("CLOUD_BASE", cloud.metric());
        assertEquals(300, cloud.actualValue());
        assertEquals(500, cloud.requiredValue());
        assertEquals(200, cloud.deficit());
        var vis = dto.weatherViolations().get(1);
        assertEquals("VISIBILITY", vis.metric());
        assertEquals(3000, vis.actualValue());
        assertEquals(5000, vis.requiredValue());
        assertEquals(2000, vis.deficit());
        assertEquals("wz1", vis.zoneId());
        assertEquals(480, vis.windowStartMinute());
        assertEquals(720, vis.windowEndMinute());
        // 快照固化版本
        assertEquals(1L, dto.weatherVersion());
        assertEquals(1L, dto.forecastVersion());
        assertEquals(0L, dto.dutyVersion());
        // 历史查询保留原结论与明细
        ReviewResultDto history = service.getReview(dto.reviewId());
        assertEquals("WEATHER_BLOCKED", history.conclusion());
        assertEquals(2, history.weatherViolations().size());
        assertNull(history.current());
    }

    @Test
    void clearWhenForecastMeetsMinimaThenOccupationFreezesVersions() {
        createRoute("wr2", 0, 10, 100, 10);
        createZone("wz2", 40, 5, 60, 15);
        configMinima("wz2", List.of(new WeatherWindowDto(480, 720, 5000, 500)));
        uploadForecast(List.of(new ForecastEntryDto("wz2", 480, 720, 6000, 800)));

        MutationResponse reviewResp = service.review(reviewReq("wr2", 1, 1L, 1L));
        ReviewResultDto review = reviewData(reviewResp);
        assertEquals("CLEAR", review.conclusion());
        assertTrue(review.weatherViolations().isEmpty());
        assertTrue(review.current());

        // 通过审查后创建占用：必须使用审查时固化的气象版本
        MutationResponse occResp = service.createOccupation(
                new OccupationCreateRequest(review.reviewId(), "op-test", "req-" + rid("occ")));
        assertFalse(occResp.replayed());
        OccupationResult occ = objectMapper.convertValue(occResp.data(), OccupationResult.class);
        assertEquals(review.reviewId(), occ.reviewId());
        assertEquals("wr2", occ.routeId());
        assertEquals(1, occ.routeVersion());
        assertEquals(1L, occ.airspaceVersion());
        assertEquals(1L, occ.weatherVersion());
        assertEquals(1L, occ.forecastVersion());
        assertEquals(0L, occ.dutyVersion());
        assertTrue(occ.createdAt() > 0);

        // 占用明细与历史查询
        assertEquals(occ.occupationId(), service.getOccupation(occ.occupationId()).occupationId());
        List<OccupationResult> byRoute = service.getOccupationsByRoute("wr2");
        assertEquals(1, byRoute.size());
        assertEquals(occ.occupationId(), byRoute.get(0).occupationId());

        // 占用创建后气象修订/换班/新预测不改写已建占用的固化版本
        configMinima("wz2", List.of(new WeatherWindowDto(480, 720, 9000, 900)));
        uploadForecast(List.of(new ForecastEntryDto("wz2", 480, 720, 100, 100)));
        service.advanceDuty(new DutyAdvanceRequest("op-test", "req-" + rid("duty")));
        OccupationResult after = service.getOccupation(occ.occupationId());
        assertEquals(1L, after.weatherVersion());
        assertEquals(1L, after.forecastVersion());
        assertEquals(0L, after.dutyVersion());
    }

    @Test
    void absoluteNoFlyBlocksEvenWithGoodForecastAndTakesPrecedence() {
        createRoute("wr3", 0, 10, 100, 10);
        createZone("absolute", 20, 5, 30, 15);   // 未配置标准：绝对禁飞区
        createZone("conditional", 40, 5, 60, 15);
        configMinima("conditional", List.of(new WeatherWindowDto(480, 720, 5000, 500)));
        // 条件区域预测也不达标，但绝对禁飞优先：结论必须是 BLOCKED
        uploadForecast(List.of(new ForecastEntryDto("conditional", 480, 720, 100, 100)));

        MutationResponse resp = service.review(reviewReq("wr3", 1, 2L, 1L));
        ReviewResultDto dto = reviewData(resp);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(List.of("absolute"), dto.hitZoneIds());
        // 气象明细仍完整记录（业务证据），但不改变结论
        assertEquals(2, dto.weatherViolations().size());
    }

    @Test
    void batchReviewCreatesAllResultsSortedByRouteId() {
        createRoute("bb", 0, -50, 100, -50);
        createRoute("ba", 0, 10, 100, 10);
        createZone("bz", 40, 5, 60, 15);
        configMinima("bz", List.of(new WeatherWindowDto(480, 720, 5000, 500)));
        uploadForecast(List.of(new ForecastEntryDto("bz", 480, 720, 6000, 600)));

        MutationResponse resp = service.reviewBatch(new BatchReviewRequest(
                List.of(new BatchReviewItemDto("bb", 1), new BatchReviewItemDto("ba", 1)),
                1L, 1L, "op-test", "req-" + rid("batch")));
        BatchReviewResult result = objectMapper.convertValue(resp.data(), BatchReviewResult.class);
        assertEquals(2, result.results().size());
        // 稳定排序：按 routeId 字典序
        assertEquals("ba", result.results().get(0).routeId());
        assertEquals("bb", result.results().get(1).routeId());
        assertEquals("CLEAR", result.results().get(0).conclusion());
        assertEquals("CLEAR", result.results().get(1).conclusion());
        assertEquals(2, reviewCount());
    }

    // ============================ 422 失败分支 ============================

    @Test
    void negativeMinimaRejected422AndDoesNotConsumeRequestId() {
        createZone("nz1", 0, 0, 10, 10);
        String requestId = "req-negative-minima";
        ApiException ex = assertThrows(ApiException.class, () -> service.configureMinima(
                new WeatherMinimaConfigRequest("nz1",
                        List.of(new WeatherWindowDto(480, 720, -1, 500)), "op-test", requestId)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("WEATHER_MINIMA_NEGATIVE", ex.code());
        // 失败不占键：同键可成功用于合法请求
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?", Integer.class, requestId));
        MutationResponse ok = service.configureMinima(new WeatherMinimaConfigRequest("nz1",
                List.of(new WeatherWindowDto(480, 720, 1000, 500)), "op-test", requestId));
        assertFalse(ok.replayed());
        assertEquals(1L, weatherVersion());
    }

    @Test
    void overlappingWindowsRejected422ButAdjacentAllowed() {
        createZone("oz1", 0, 0, 10, 10);
        ApiException overlap = assertThrows(ApiException.class, () -> configMinima("oz1",
                List.of(new WeatherWindowDto(480, 720, 1000, 100),
                        new WeatherWindowDto(700, 900, 1000, 100))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, overlap.status());
        assertEquals("WEATHER_WINDOW_OVERLAP", overlap.code());
        assertEquals(0L, weatherVersion());

        // 左闭右开：首尾相接 [480,720) 与 [720,1440) 不重叠
        MutationResponse ok = configMinima("oz1",
                List.of(new WeatherWindowDto(720, 1440, 2000, 200),
                        new WeatherWindowDto(480, 720, 1000, 100)));
        assertFalse(ok.replayed());
        WeatherMinimaResult minima = service.getMinima("oz1");
        assertEquals(1L, minima.weatherVersion());
        // 明细查询稳定排序：按时段起始升序
        assertEquals(480, minima.windows().get(0).startMinute());
        assertEquals(720, minima.windows().get(1).startMinute());
    }

    @Test
    void invalidWindowAndMissingZoneRejected() {
        createZone("iz1", 0, 0, 10, 10);
        ApiException badRange = assertThrows(ApiException.class, () -> configMinima("iz1",
                List.of(new WeatherWindowDto(720, 480, 1000, 100))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, badRange.status());
        assertEquals("WEATHER_WINDOW_INVALID", badRange.code());

        ApiException beyond = assertThrows(ApiException.class, () -> configMinima("iz1",
                List.of(new WeatherWindowDto(0, 1441, 1000, 100))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, beyond.status());

        ApiException missing = assertThrows(ApiException.class, () -> configMinima("ghost",
                List.of(new WeatherWindowDto(0, 60, 1000, 100))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    @Test
    void invalidForecastRejected() {
        createZone("fz1", 0, 0, 10, 10);
        ApiException negative = assertThrows(ApiException.class, () -> uploadForecast(
                List.of(new ForecastEntryDto("fz1", 0, 60, -5, 100))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, negative.status());
        assertEquals("FORECAST_NEGATIVE", negative.code());

        ApiException badWindow = assertThrows(ApiException.class, () -> uploadForecast(
                List.of(new ForecastEntryDto("fz1", 60, 60, 100, 100))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, badWindow.status());

        ApiException missingZone = assertThrows(ApiException.class, () -> uploadForecast(
                List.of(new ForecastEntryDto("ghost", 0, 60, 100, 100))));
        assertEquals(HttpStatus.NOT_FOUND, missingZone.status());

        ApiException duplicate = assertThrows(ApiException.class, () -> uploadForecast(
                List.of(new ForecastEntryDto("fz1", 0, 60, 100, 100),
                        new ForecastEntryDto("fz1", 0, 120, 200, 200))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, duplicate.status());
        assertEquals("FORECAST_DUPLICATE_WINDOW", duplicate.code());
        // 全部失败：预测版本未被推进
        assertEquals(0L, forecastVersion());
    }

    @Test
    void reviewCrossingConditionalZoneWithoutForecastIs422() {
        createRoute("fr1", 0, 10, 100, 10);
        createZone("fz2", 40, 5, 60, 15);
        configMinima("fz2", List.of(new WeatherWindowDto(480, 720, 5000, 500)));

        // 未提交预测版本 → 422
        ApiException noVersion = assertThrows(ApiException.class,
                () -> service.review(reviewReq("fr1", 1, 1L, null)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, noVersion.status());
        assertEquals("FORECAST_MISSING", noVersion.code());

        // 提交了不存在的预测版本 → 422
        ApiException badVersion = assertThrows(ApiException.class,
                () -> service.review(reviewReq("fr1", 1, 1L, 99L)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, badVersion.status());

        // 预测版本存在但缺覆盖时段 → 422
        uploadForecast(List.of(new ForecastEntryDto("fz2", 480, 600, 6000, 600)));
        ApiException gap = assertThrows(ApiException.class,
                () -> service.review(reviewReq("fr1", 1, 1L, 1L)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, gap.status());
        assertEquals("FORECAST_MISSING", gap.code());

        // 三次失败均未产生审核记录
        assertEquals(0, reviewCount());
    }

    @Test
    void reviewWithNonCurrentForecastVersionIs409() {
        createRoute("fr2", 0, 10, 100, 10);
        createZone("fz3", 40, 5, 60, 15);
        configMinima("fz3", List.of(new WeatherWindowDto(480, 720, 5000, 500)));
        uploadForecast(List.of(new ForecastEntryDto("fz3", 480, 720, 6000, 600)));
        // 新预测上传后，旧预测版本不再是最新
        uploadForecast(List.of(new ForecastEntryDto("fz3", 480, 720, 6000, 600)));
        assertEquals(2L, forecastVersion());

        ApiException stale = assertThrows(ApiException.class,
                () -> service.review(reviewReq("fr2", 1, 1L, 1L)));
        assertEquals(HttpStatus.CONFLICT, stale.status());
        assertEquals("VERSION_CONFLICT", stale.code());
        // 用最新预测版本可通过
        MutationResponse ok = service.review(reviewReq("fr2", 1, 1L, 2L));
        assertEquals("CLEAR", reviewData(ok).conclusion());
    }

    // ============================ 批量整批事务 ============================

    @Test
    void batchReviewWithAnyRouteMissingForecastCreatesNothing() {
        createRoute("ba1", 0, 10, 100, 10);   // 经过条件区域
        createRoute("ba2", 0, -50, 100, -50); // 不经过任何区域
        createZone("bz1", 40, 5, 60, 15);
        configMinima("bz1", List.of(new WeatherWindowDto(480, 720, 5000, 500)));
        String requestId = "req-batch-missing-forecast";

        ApiException ex = assertThrows(ApiException.class, () -> service.reviewBatch(
                new BatchReviewRequest(
                        List.of(new BatchReviewItemDto("ba1", 1), new BatchReviewItemDto("ba2", 1)),
                        1L, null, "op-test", requestId)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("FORECAST_MISSING", ex.code());
        // 整批不创建结果，也不占用 requestId
        assertEquals(0, reviewCount());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?", Integer.class, requestId));

        // 补齐预测后同键可成功
        uploadForecast(List.of(new ForecastEntryDto("bz1", 480, 720, 6000, 600)));
        MutationResponse ok = service.reviewBatch(new BatchReviewRequest(
                List.of(new BatchReviewItemDto("ba1", 1), new BatchReviewItemDto("ba2", 1)),
                1L, 1L, "op-test", requestId));
        assertFalse(ok.replayed());
        assertEquals(2, reviewCount());
    }

    @Test
    void batchReviewWithVersionConflictAbortsAll() {
        createRoute("bc1", 0, 10, 100, 10);
        createRoute("bc2", 0, -50, 100, -50);
        ApiException ex = assertThrows(ApiException.class, () -> service.reviewBatch(
                new BatchReviewRequest(
                        List.of(new BatchReviewItemDto("bc1", 1), new BatchReviewItemDto("bc2", 9)),
                        0L, null, "op-test", "req-" + rid("batch"))));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals(0, reviewCount());

        ApiException missing = assertThrows(ApiException.class, () -> service.reviewBatch(
                new BatchReviewRequest(
                        List.of(new BatchReviewItemDto("bc1", 1), new BatchReviewItemDto("ghost", 1)),
                        0L, null, "op-test", "req-" + rid("batch"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
        assertEquals(0, reviewCount());

        ApiException duplicated = assertThrows(ApiException.class, () -> service.reviewBatch(
                new BatchReviewRequest(
                        List.of(new BatchReviewItemDto("bc1", 1), new BatchReviewItemDto("bc1", 1)),
                        0L, null, "op-test", "req-" + rid("batch"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, duplicated.status());
        assertEquals(0, reviewCount());
    }

    // ============================ 时效冻结与 STALE ============================

    @Test
    void minimaRevisionStalesOldClearButNeverRewritesIt() {
        createRoute("sr1", 0, 10, 100, 10);
        createZone("sz1", 40, 5, 60, 15);
        configMinima("sz1", List.of(new WeatherWindowDto(480, 720, 5000, 500)));
        uploadForecast(List.of(new ForecastEntryDto("sz1", 480, 720, 6000, 600)));
        ReviewResultDto review = reviewData(service.review(reviewReq("sr1", 1, 1L, 1L)));
        assertEquals("CLEAR", review.conclusion());

        // 气象修订：只影响新审查
        configMinima("sz1", List.of(new WeatherWindowDto(480, 720, 9000, 900)));
        assertEquals(2L, weatherVersion());

        // 旧 CLEAR 不被改写，但当前查询标记 STALE
        assertEquals("CLEAR", service.getReview(review.reviewId()).conclusion());
        ReviewResultDto current = service.getCurrentReview("sr1");
        assertEquals("STALE", current.conclusion());
        assertFalse(current.current());

        // 时效诊断：仅 WEATHER_MINIMA 维度偏离，含固化值与当前值
        ReviewDiagnosticsDto diag = service.getReviewDiagnostics(review.reviewId());
        assertEquals("CLEAR", diag.conclusion());
        assertTrue(diag.stale());
        assertEquals(List.of("ROUTE", "AIRSPACE", "WEATHER_MINIMA", "FORECAST", "DUTY"),
                diag.dimensions().stream().map(VersionDiagnosticDto::dimension).toList());
        VersionDiagnosticDto minimaDim = diag.dimensions().get(2);
        assertEquals(1L, minimaDim.frozenValue());
        assertEquals(2L, minimaDim.currentValue());
        assertTrue(minimaDim.stale());
        assertFalse(diag.dimensions().get(0).stale());
        assertFalse(diag.dimensions().get(3).stale());

        // 占用拒绝使用 STALE 结果
        ApiException occ = assertThrows(ApiException.class, () -> service.createOccupation(
                new OccupationCreateRequest(review.reviewId(), "op-test", "req-" + rid("occ"))));
        assertEquals(HttpStatus.CONFLICT, occ.status());
        assertEquals("OCCUPATION_STALE_REVIEW", occ.code());

        // 用新标准重新审查（预测 6000 < 9000 → WEATHER_BLOCKED），旧结果仍不变
        uploadForecast(List.of(new ForecastEntryDto("sz1", 480, 720, 6000, 600)));
        ReviewResultDto reReview = reviewData(service.review(reviewReq("sr1", 1, 1L, 2L)));
        assertEquals("WEATHER_BLOCKED", reReview.conclusion());
        assertEquals("CLEAR", service.getReview(review.reviewId()).conclusion());
    }

    @Test
    void dutyAdvanceAndForecastUploadStaleReview() {
        createRoute("sr2", 0, 10, 100, 10);
        createZone("sz2", 40, 5, 60, 15);
        configMinima("sz2", List.of(new WeatherWindowDto(480, 720, 5000, 500)));
        uploadForecast(List.of(new ForecastEntryDto("sz2", 480, 720, 6000, 600)));
        ReviewResultDto review = reviewData(service.review(reviewReq("sr2", 1, 1L, 1L)));
        assertEquals("CLEAR", service.getCurrentReview("sr2").conclusion());

        // 换班 → 值班维度 STALE
        service.advanceDuty(new DutyAdvanceRequest("op-test", "req-" + rid("duty")));
        assertEquals(1L, dutyVersion());
        assertEquals("STALE", service.getCurrentReview("sr2").conclusion());
        ReviewDiagnosticsDto diag = service.getReviewDiagnostics(review.reviewId());
        assertTrue(diag.dimensions().get(4).stale());
        assertEquals(0L, diag.dimensions().get(4).frozenValue());
        assertEquals(1L, diag.dimensions().get(4).currentValue());

        // 重新审查后，新预测上传 → 预测维度 STALE
        ReviewResultDto review2 = reviewData(service.review(reviewReq("sr2", 1, 1L, 1L)));
        assertEquals("CLEAR", service.getCurrentReview("sr2").conclusion());
        uploadForecast(List.of(new ForecastEntryDto("sz2", 480, 720, 6000, 600)));
        assertEquals("STALE", service.getCurrentReview("sr2").conclusion());
        ReviewDiagnosticsDto diag2 = service.getReviewDiagnostics(review2.reviewId());
        assertTrue(diag2.dimensions().get(3).stale());
        assertEquals(1L, diag2.dimensions().get(3).frozenValue());
        assertEquals(2L, diag2.dimensions().get(3).currentValue());
        // 占用同样被拒绝
        ApiException occ = assertThrows(ApiException.class, () -> service.createOccupation(
                new OccupationCreateRequest(review2.reviewId(), "op-test", "req-" + rid("occ"))));
        assertEquals("OCCUPATION_STALE_REVIEW", occ.code());
    }

    @Test
    void occupationRejectsNonClearAndDuplicate() {
        createRoute("or1", 0, 10, 100, 10);
        createZone("oz2", 40, 5, 60, 15);
        configMinima("oz2", List.of(new WeatherWindowDto(480, 720, 5000, 500)));
        uploadForecast(List.of(new ForecastEntryDto("oz2", 480, 720, 100, 100)));
        ReviewResultDto blocked = reviewData(service.review(reviewReq("or1", 1, 1L, 1L)));
        assertEquals("WEATHER_BLOCKED", blocked.conclusion());

        // 非 CLEAR 不能创建占用
        ApiException notClear = assertThrows(ApiException.class, () -> service.createOccupation(
                new OccupationCreateRequest(blocked.reviewId(), "op-test", "req-" + rid("occ"))));
        assertEquals(HttpStatus.CONFLICT, notClear.status());
        assertEquals("OCCUPATION_NOT_CLEAR", notClear.code());

        // 不存在的审核 404
        ApiException missing = assertThrows(ApiException.class, () -> service.createOccupation(
                new OccupationCreateRequest("rv_ghost", "op-test", "req-" + rid("occ"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        // CLEAR 审核占用成功后，同一审核不能重复占用
        uploadForecast(List.of(new ForecastEntryDto("oz2", 480, 720, 6000, 600)));
        ReviewResultDto clear = reviewData(service.review(reviewReq("or1", 1, 1L, 2L)));
        MutationResponse occ = service.createOccupation(
                new OccupationCreateRequest(clear.reviewId(), "op-test", "req-" + rid("occ")));
        assertFalse(occ.replayed());
        ApiException duplicate = assertThrows(ApiException.class, () -> service.createOccupation(
                new OccupationCreateRequest(clear.reviewId(), "op-test", "req-" + rid("occ2"))));
        assertEquals(HttpStatus.CONFLICT, duplicate.status());
        assertEquals("OCCUPATION_ALREADY_EXISTS", duplicate.code());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM occupation", Integer.class));
    }

    // ============================ 历史与查询 ============================

    @Test
    void reviewHistoryIsStablySortedAndKeepsOriginalConclusions() {
        createRoute("hr1", 0, 10, 100, 10);
        createZone("hz1", 40, 5, 60, 15);
        configMinima("hz1", List.of(new WeatherWindowDto(480, 720, 5000, 500)));
        uploadForecast(List.of(new ForecastEntryDto("hz1", 480, 720, 100, 100)));
        ReviewResultDto r1 = reviewData(service.review(reviewReq("hr1", 1, 1L, 1L)));
        uploadForecast(List.of(new ForecastEntryDto("hz1", 480, 720, 6000, 600)));
        ReviewResultDto r2 = reviewData(service.review(reviewReq("hr1", 1, 1L, 2L)));

        List<ReviewResultDto> history = service.getReviewHistory("hr1");
        assertEquals(2, history.size());
        // 稳定排序：created_at 升序、reviewId 升序
        List<ReviewResultDto> sorted = history.stream()
                .sorted(Comparator.comparing((ReviewResultDto d) ->
                                jdbc.queryForObject("SELECT created_at FROM review WHERE review_id = ?",
                                        Long.class, d.reviewId()))
                        .thenComparing(ReviewResultDto::reviewId))
                .toList();
        assertEquals(sorted.stream().map(ReviewResultDto::reviewId).toList(),
                history.stream().map(ReviewResultDto::reviewId).toList());
        // 历史结论不被后续操作改写，current 语义为 null
        assertTrue(history.stream().allMatch(d -> d.current() == null));
        assertEquals("WEATHER_BLOCKED", service.getReview(r1.reviewId()).conclusion());
        assertEquals("CLEAR", service.getReview(r2.reviewId()).conclusion());

        // 预测明细查询：版本不存在 404，存在则稳定排序
        ApiException noForecast = assertThrows(ApiException.class, () -> service.getForecast(99L));
        assertEquals(HttpStatus.NOT_FOUND, noForecast.status());
        assertEquals(1, service.getForecast(1L).entries().size());
    }

    // ============================ 幂等 ============================

    @Test
    void minimaAndForecastReplaySameResultAndRejectDifferentParams() {
        createZone("iz2", 0, 0, 10, 10);
        String minimaReq = "req-minima-replay";
        WeatherMinimaConfigRequest minima = new WeatherMinimaConfigRequest("iz2",
                List.of(new WeatherWindowDto(480, 720, 1000, 100)), "op-a", minimaReq);
        MutationResponse first = service.configureMinima(minima);
        assertFalse(first.replayed());
        MutationResponse replay = service.configureMinima(minima);
        assertTrue(replay.replayed());
        // 重放不推进版本、不重复写入
        assertEquals(1L, weatherVersion());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weather_minima WHERE zone_id = 'iz2'", Integer.class));

        // 同键异参（含操作者不同）→ 409
        ApiException diffParams = assertThrows(ApiException.class, () -> service.configureMinima(
                new WeatherMinimaConfigRequest("iz2",
                        List.of(new WeatherWindowDto(480, 720, 2000, 100)), "op-a", minimaReq)));
        assertEquals(HttpStatus.CONFLICT, diffParams.status());
        ApiException diffOperator = assertThrows(ApiException.class, () -> service.configureMinima(
                new WeatherMinimaConfigRequest("iz2",
                        List.of(new WeatherWindowDto(480, 720, 1000, 100)), "op-b", minimaReq)));
        assertEquals(HttpStatus.CONFLICT, diffOperator.status());

        // 预测上传重放：版本只推进一次
        String forecastReq = "req-forecast-replay";
        ForecastUploadRequest upload = new ForecastUploadRequest(
                List.of(new ForecastEntryDto("iz2", 0, 60, 100, 100)), "op-a", forecastReq);
        MutationResponse f1 = service.uploadForecast(upload);
        assertFalse(f1.replayed());
        MutationResponse f2 = service.uploadForecast(upload);
        assertTrue(f2.replayed());
        assertEquals(1L, forecastVersion());
        ForecastUploadResult replayed = objectMapper.convertValue(f2.data(), ForecastUploadResult.class);
        assertEquals(1L, replayed.forecastVersion());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weather_forecast", Integer.class));
    }

    @Test
    void occupationReplayReturnsFirstSnapshot() {
        createRoute("ir1", 0, 10, 100, 10);
        createZone("iz3", 40, 5, 60, 15);
        configMinima("iz3", List.of(new WeatherWindowDto(480, 720, 5000, 500)));
        uploadForecast(List.of(new ForecastEntryDto("iz3", 480, 720, 6000, 600)));
        ReviewResultDto review = reviewData(service.review(reviewReq("ir1", 1, 1L, 1L)));

        String requestId = "req-occ-replay";
        OccupationCreateRequest occReq = new OccupationCreateRequest(review.reviewId(), "op-a", requestId);
        MutationResponse first = service.createOccupation(occReq);
        String firstId = objectMapper.convertValue(first.data(), OccupationResult.class).occupationId();
        // 环境变化后重放仍返回首次完整响应快照
        configMinima("iz3", List.of(new WeatherWindowDto(480, 720, 9000, 900)));
        MutationResponse replay = service.createOccupation(occReq);
        assertTrue(replay.replayed());
        assertEquals(firstId, objectMapper.convertValue(replay.data(), OccupationResult.class).occupationId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM occupation", Integer.class));
        // 同键异参 → 409
        ApiException diff = assertThrows(ApiException.class, () -> service.createOccupation(
                new OccupationCreateRequest(review.reviewId(), "op-b", requestId)));
        assertEquals(HttpStatus.CONFLICT, diff.status());
    }

    // ============================ 并发 ============================

    @Test
    void concurrentMinimaRevisionAndReviewUseConsistentSnapshot() throws Exception {
        createRoute("cr1", 0, 10, 100, 10);
        createZone("cz1", 40, 5, 60, 15);
        configMinima("cz1", List.of(new WeatherWindowDto(480, 720, 5000, 500)));
        uploadForecast(List.of(new ForecastEntryDto("cz1", 480, 720, 6000, 600)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                long versionBefore = weatherVersion();
                CyclicBarrier barrier = new CyclicBarrier(2);
                int newMin = 4000 + i;
                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.review(reviewReq("cr1", 1, 1L, 1L));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> configFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.configureMinima(new WeatherMinimaConfigRequest("cz1",
                                List.of(new WeatherWindowDto(480, 720, newMin, 500)),
                                "op-test", "req-" + rid("minima")));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object configResult = configFuture.get(15, TimeUnit.SECONDS);
                assertTrue(configResult instanceof MutationResponse, "标准修订必须成功");
                assertTrue(reviewResult instanceof MutationResponse, "审查必须成功（两种标准下均 CLEAR）");
                ReviewResultDto dto = reviewData((MutationResponse) reviewResult);
                assertEquals("CLEAR", dto.conclusion());
                // 固化版本只能是修订前或修订后之一，且与其后诊断一致
                long frozen = dto.weatherVersion();
                assertTrue(frozen == versionBefore || frozen == versionBefore + 1,
                        "固化气象版本必须来自某个一致状态: " + frozen);
                ReviewDiagnosticsDto diag = service.getReviewDiagnostics(dto.reviewId());
                VersionDiagnosticDto dim = diag.dimensions().get(2);
                assertEquals(frozen, dim.frozenValue());
                assertEquals(weatherVersion(), dim.currentValue());
                assertEquals(frozen != weatherVersion(), dim.stale());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameRequestIdForecastUploadPlaysBackOneOutcome() throws Exception {
        createZone("cz2", 0, 0, 10, 10);
        String requestId = "req-concurrent-forecast";
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.uploadForecast(new ForecastUploadRequest(
                            List.of(new ForecastEntryDto("cz2", 0, 60, 100, 100)),
                            "op-a", requestId));
                }));
            }
            int firstCount = 0;
            int replayCount = 0;
            for (Future<MutationResponse> f : futures) {
                MutationResponse resp = f.get(15, TimeUnit.SECONDS);
                if (resp.replayed()) {
                    replayCount++;
                } else {
                    firstCount++;
                }
            }
            assertEquals(1, firstCount, "仅一次请求真正执行上传");
            assertEquals(n - 1, replayCount);
            // 预测版本只推进一次，预测行只写入一批
            assertEquals(1L, forecastVersion());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM weather_forecast", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                    Integer.class, requestId));
        } finally {
            pool.shutdownNow();
        }
    }
}
