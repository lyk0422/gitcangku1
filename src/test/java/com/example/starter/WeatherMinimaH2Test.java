package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BatchReviewItemDto;
import com.example.starter.api.dto.BatchReviewRequest;
import com.example.starter.api.dto.BatchReviewResult;
import com.example.starter.api.dto.ForecastEntryDto;
import com.example.starter.api.dto.ForecastUploadRequest;
import com.example.starter.api.dto.ForecastUploadResult;
import com.example.starter.api.dto.MinimaWindowDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyRequest;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.ReviewDiagnosticsDto;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.WeatherRegionConfigRequest;
import com.example.starter.api.dto.WeatherRegionDetailDto;
import com.example.starter.api.dto.WeatherRegionResult;
import com.example.starter.api.dto.WeatherViolationDto;
import com.example.starter.service.AirspaceReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * 空域气象最低标准的 H2 数据库测试（MODE=MySQL）。
 * 覆盖主流程（标准配置、预测上传、审核、占用）、422 失败分支、
 * 批量审核整批回滚、气象修订 STALE、幂等边界与真实并发串行化。
 */
@SpringBootTest
class WeatherMinimaH2Test {

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
        jdbc.update("DELETE FROM occupancy");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM weather_forecast");
        jdbc.update("DELETE FROM weather_minima_window");
        jdbc.update("DELETE FROM weather_region");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE weather_meta SET duty_version = 0, forecast_version = 0 WHERE id = 1");
    }

    private String rid(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    private static List<RoutePointDto> pts(int... xy) {
        return java.util.stream.IntStream.range(0, xy.length / 2)
                .mapToObj(i -> new RoutePointDto(xy[2 * i], xy[2 * i + 1]))
                .toList();
    }

    private static MinimaWindowDto win(long start, long end, int visMin, int cbMin) {
        return new MinimaWindowDto(start, end, visMin, cbMin);
    }

    private static ForecastEntryDto fentry(String regionId, long start, long end, int vis, int cb) {
        return new ForecastEntryDto(regionId, start, end, vis, cb);
    }

    private WeatherRegionConfigRequest regionReq(String regionId, List<MinimaWindowDto> windows) {
        return new WeatherRegionConfigRequest(regionId, 40, 5, 60, 15, windows,
                "tester", "req-" + rid("region"));
    }

    private ForecastUploadRequest forecastReq(ForecastEntryDto... entries) {
        return new ForecastUploadRequest(List.of(entries), "tester", "req-" + rid("forecast"));
    }

    private void createRoute(String routeId, int... xy) {
        service.createRoute(new RouteCreateRequest(routeId, pts(xy), "req-" + rid("route")));
    }

    private ReviewResultDto reviewData(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    private long dutyVersion() {
        return jdbc.queryForObject("SELECT duty_version FROM weather_meta WHERE id = 1", Long.class);
    }

    private long latestForecastVersion() {
        return jdbc.queryForObject(
                "SELECT forecast_version FROM weather_meta WHERE id = 1", Long.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    // ============================ 主流程 ============================

    @Test
    void clearWithFrozenWeatherSnapshotThenOccupancy() {
        MutationResponse region = service.configureWeatherRegion(
                regionReq("wr1", List.of(win(1000, 2000, 500, 300))));
        assertEquals(1, objectMapper.convertValue(region.data(), WeatherRegionResult.class).dutyVersion());

        MutationResponse forecast = service.uploadForecast(
                forecastReq(fentry("wr1", 1000, 2000, 800, 400)));
        assertEquals(1, objectMapper.convertValue(forecast.data(), ForecastUploadResult.class)
                .forecastVersion());

        createRoute("r1", 0, 10, 100, 10);
        MutationResponse review = service.review(
                new ReviewRequest("r1", 1, 0L, 1L, "tester", "req-" + rid("review")));
        ReviewResultDto dto = reviewData(review);
        assertEquals("CLEAR", dto.conclusion());
        assertEquals(1L, dto.dutyVersion());
        assertEquals(1L, dto.forecastVersion());
        // 路径经过气象区域但无违规：违规明细为空列表而非 null
        assertNotNull(dto.weatherViolations());
        assertTrue(dto.weatherViolations().isEmpty());
        assertTrue(dto.current());

        // 通过审核后创建占用：必须使用审核时固化的气象版本
        MutationResponse occupancy = service.createOccupancy(
                new OccupancyRequest(dto.reviewId(), "tester", "req-" + rid("occ")));
        OccupancyResult occ = objectMapper.convertValue(occupancy.data(), OccupancyResult.class);
        assertEquals(dto.reviewId(), occ.reviewId());
        assertEquals(1L, occ.dutyVersion());
        assertEquals(1L, occ.forecastVersion());
        assertEquals(1, count("occupancy"));
    }

    @Test
    void weatherBlockedWithActualRequiredAndDeficit() {
        service.configureWeatherRegion(regionReq("wr1", List.of(win(1000, 2000, 500, 300))));
        service.uploadForecast(forecastReq(fentry("wr1", 1000, 2000, 400, 200)));
        createRoute("r1", 0, 10, 100, 10);

        MutationResponse review = service.review(
                new ReviewRequest("r1", 1, 0L, 1L, "tester", "req-" + rid("review")));
        ReviewResultDto dto = reviewData(review);
        assertEquals("WEATHER_BLOCKED", dto.conclusion());
        assertTrue(dto.hitZoneIds().isEmpty());
        List<WeatherViolationDto> violations = dto.weatherViolations();
        assertEquals(2, violations.size());
        // 排序稳定：regionId、时段起点、指标（VISIBILITY 先于 CLOUD_BASE）
        WeatherViolationDto vis = violations.get(0);
        assertEquals("VISIBILITY", vis.metric());
        assertEquals("wr1", vis.regionId());
        assertEquals(1000, vis.windowStartUtc());
        assertEquals(2000, vis.windowEndUtc());
        assertEquals(400, vis.actualM());
        assertEquals(500, vis.requiredM());
        assertEquals(100, vis.deficitM());
        WeatherViolationDto cb = violations.get(1);
        assertEquals("CLOUD_BASE", cb.metric());
        assertEquals(200, cb.actualM());
        assertEquals(300, cb.requiredM());
        assertEquals(100, cb.deficitM());

        // 历史保留原结论；WEATHER_BLOCKED 不能创建占用
        assertEquals("WEATHER_BLOCKED", service.getReview(dto.reviewId()).conclusion());
        ApiException occ = assertThrows(ApiException.class, () -> service.createOccupancy(
                new OccupancyRequest(dto.reviewId(), "tester", "req-" + rid("occ"))));
        assertEquals(HttpStatus.CONFLICT, occ.status());
        assertEquals("OCCUPANCY_NOT_CLEAR", occ.code());
    }
    // ============================ 422 失败分支 ============================

    @Test
    void negativeMinimaRejected422AndKeyNotConsumed() {
        String requestId = "req-neg-" + rid("region");
        ApiException ex = assertThrows(ApiException.class, () -> service.configureWeatherRegion(
                new WeatherRegionConfigRequest("wr1", 40, 5, 60, 15,
                        List.of(win(1000, 2000, -1, 300)), "tester", requestId)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("WEATHER_MINIMA_NEGATIVE", ex.code());
        // 失败回滚：不建区域、不推进值班版本、不占幂等键
        assertEquals(0, count("weather_region"));
        assertEquals(0, dutyVersion());
        assertEquals(0, count("request_dedup"));
        // 同键修正参数后可成功
        MutationResponse ok = service.configureWeatherRegion(
                new WeatherRegionConfigRequest("wr1", 40, 5, 60, 15,
                        List.of(win(1000, 2000, 500, 300)), "tester", requestId));
        assertFalse(ok.replayed());
        assertEquals(1, dutyVersion());
    }

    @Test
    void overlappingWindowsRejected422ButAdjacentAllowed() {
        ApiException overlap = assertThrows(ApiException.class, () -> service.configureWeatherRegion(
                regionReq("wr1", List.of(win(0, 100, 500, 300), win(50, 150, 500, 300)))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, overlap.status());
        assertEquals("WEATHER_WINDOW_OVERLAP", overlap.code());

        ApiException invalid = assertThrows(ApiException.class, () -> service.configureWeatherRegion(
                regionReq("wr1", List.of(win(100, 100, 500, 300)))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, invalid.status());
        assertEquals("WEATHER_WINDOW_INVALID", invalid.code());

        // 左闭右开：相邻窗口（前一终点等于后一起点）不重叠，合法
        MutationResponse ok = service.configureWeatherRegion(
                regionReq("wr1", List.of(win(0, 100, 500, 300), win(100, 200, 600, 400))));
        assertFalse(ok.replayed());
        WeatherRegionDetailDto detail = service.getWeatherRegion("wr1");
        assertEquals(2, detail.windows().size());
        assertEquals(0, detail.windows().get(0).windowStartUtc());
        assertEquals(100, detail.windows().get(1).windowStartUtc());
    }

    @Test
    void missingForecastRejected422() {
        service.configureWeatherRegion(regionReq("wr1", List.of(win(1000, 2000, 500, 300))));
        createRoute("r1", 0, 10, 100, 10);

        // 未提交预测版本
        ApiException noVersion = assertThrows(ApiException.class, () -> service.review(
                new ReviewRequest("r1", 1, 0L, null, "tester", "req-" + rid("review"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, noVersion.status());
        assertEquals("FORECAST_MISSING", noVersion.code());

        // 提交了不存在的预测版本
        ApiException badVersion = assertThrows(ApiException.class, () -> service.review(
                new ReviewRequest("r1", 1, 0L, 99L, "tester", "req-" + rid("review"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, badVersion.status());

        // 预测版本存在但缺少该区域该时段的条目
        service.configureWeatherRegion(regionReq("wr2", List.of(win(1000, 2000, 500, 300))));
        // wr2 与 wr1 矩形相同，航线同时经过两个区域；预测只覆盖 wr1
        service.uploadForecast(forecastReq(fentry("wr1", 1000, 2000, 800, 400)));
        ApiException missingEntry = assertThrows(ApiException.class, () -> service.review(
                new ReviewRequest("r1", 1, 0L, 1L, "tester", "req-" + rid("review"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, missingEntry.status());
        assertEquals("FORECAST_MISSING", missingEntry.code());

        // 全部失败：没有产生任何审核结果
        assertEquals(0, count("review"));
    }

    @Test
    void forecastUploadValidationAndReplay() {
        service.configureWeatherRegion(regionReq("wr1", List.of(win(1000, 2000, 500, 300))));

        ApiException unknownRegion = assertThrows(ApiException.class, () -> service.uploadForecast(
                forecastReq(fentry("ghost", 1000, 2000, 800, 400))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, unknownRegion.status());
        assertEquals("FORECAST_REGION_UNKNOWN", unknownRegion.code());

        ApiException negative = assertThrows(ApiException.class, () -> service.uploadForecast(
                forecastReq(fentry("wr1", 1000, 2000, -5, 400))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, negative.status());
        assertEquals("WEATHER_FORECAST_NEGATIVE", negative.code());

        ApiException duplicate = assertThrows(ApiException.class, () -> service.uploadForecast(
                forecastReq(fentry("wr1", 1000, 2000, 800, 400),
                        fentry("wr1", 1000, 2000, 900, 500))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, duplicate.status());
        assertEquals("FORECAST_ENTRY_DUPLICATE", duplicate.code());

        // 全部失败：不分配版本、不落条目
        assertEquals(0, latestForecastVersion());
        assertEquals(0, count("weather_forecast"));

        // 成功后同键重放返回首次版本，不再分配新版本
        String requestId = "req-fc-" + rid("forecast");
        ForecastUploadRequest req = new ForecastUploadRequest(
                List.of(fentry("wr1", 1000, 2000, 800, 400)), "tester", requestId);
        MutationResponse first = service.uploadForecast(req);
        assertEquals(1, objectMapper.convertValue(first.data(), ForecastUploadResult.class)
                .forecastVersion());
        MutationResponse replay = service.uploadForecast(req);
        assertTrue(replay.replayed());
        assertEquals(1, objectMapper.convertValue(replay.data(), ForecastUploadResult.class)
                .forecastVersion());
        assertEquals(1, latestForecastVersion());
        assertEquals(1, count("weather_forecast"));

        // 同键异参（不同操作者）→ 409：指纹包含操作者
        ApiException mismatch = assertThrows(ApiException.class, () -> service.uploadForecast(
                new ForecastUploadRequest(List.of(fentry("wr1", 1000, 2000, 800, 400)),
                        "other-operator", requestId)));
        assertEquals(HttpStatus.CONFLICT, mismatch.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", mismatch.code());
    }

    // ============================ 批量审核 ============================

    @Test
    void batchReviewAllOrNothingOnMissingForecast() {
        service.configureWeatherRegion(regionReq("wr1", List.of(win(1000, 2000, 500, 300))));
        createRoute("r1", 0, 10, 100, 10);   // 经过气象区域
        createRoute("r2", 0, -50, 100, -50); // 不经过任何区域

        // r1 缺预测：整批 422，不创建任何结果
        ApiException ex = assertThrows(ApiException.class, () -> service.reviewBatch(
                new BatchReviewRequest(
                        List.of(new BatchReviewItemDto("r1", 1, 0L),
                                new BatchReviewItemDto("r2", 1, 0L)),
                        null, "tester", "req-" + rid("batch"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("FORECAST_MISSING", ex.code());
        assertTrue(ex.getMessage().contains("r1"));
        assertEquals(0, count("review"));

        // 补齐预测后整批成功：数量响应给出要求值与实际值
        service.uploadForecast(forecastReq(fentry("wr1", 1000, 2000, 800, 400)));
        MutationResponse ok = service.reviewBatch(new BatchReviewRequest(
                List.of(new BatchReviewItemDto("r1", 1, 0L),
                        new BatchReviewItemDto("r2", 1, 0L)),
                1L, "tester", "req-" + rid("batch")));
        BatchReviewResult result = objectMapper.convertValue(ok.data(), BatchReviewResult.class);
        assertEquals(2, result.requestedCount());
        assertEquals(2, result.createdCount());
        assertEquals(2, result.results().size());
        // 结果顺序与请求一致
        assertEquals("r1", result.results().get(0).routeId());
        assertEquals("r2", result.results().get(1).routeId());
        assertEquals("CLEAR", result.results().get(0).conclusion());
        assertEquals("CLEAR", result.results().get(1).conclusion());
        assertEquals(2, count("review"));
    }

    @Test
    void batchReviewVersionConflictRollsBackEntireBatch() {
        createRoute("r1", 0, 10, 100, 10);
        createRoute("r2", 0, -50, 100, -50);
        // 第二条航线版本错误：整批 409，不创建任何结果
        ApiException ex = assertThrows(ApiException.class, () -> service.reviewBatch(
                new BatchReviewRequest(
                        List.of(new BatchReviewItemDto("r1", 1, 0L),
                                new BatchReviewItemDto("r2", 9, 0L)),
                        null, "tester", "req-" + rid("batch"))));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("VERSION_CONFLICT", ex.code());
        assertTrue(ex.getMessage().contains("r2"));
        assertEquals(0, count("review"));
    }
    // ============================ 气象修订与 STALE ============================

    @Test
    void minimaRevisionMakesOldClearStaleButNeverRewrites() {
        service.configureWeatherRegion(regionReq("wr1", List.of(win(1000, 2000, 500, 300))));
        service.uploadForecast(forecastReq(fentry("wr1", 1000, 2000, 800, 400)));
        createRoute("r1", 0, 10, 100, 10);
        MutationResponse review = service.review(
                new ReviewRequest("r1", 1, 0L, 1L, "tester", "req-" + rid("review")));
        ReviewResultDto dto = reviewData(review);
        assertEquals("CLEAR", dto.conclusion());
        assertEquals("CLEAR", service.getCurrentReview("r1").conclusion());

        // 气象标准修订：值班版本加一，只影响新审核
        service.configureWeatherRegion(regionReq("wr1", List.of(win(1000, 2000, 900, 300))));
        assertEquals(2, dutyVersion());

        // 旧 CLEAR 不被改写，但当前结论标记 STALE，占用被拒绝
        assertEquals("CLEAR", service.getReview(dto.reviewId()).conclusion());
        ReviewResultDto current = service.getCurrentReview("r1");
        assertEquals("STALE", current.conclusion());
        assertFalse(current.current());
        ApiException occ = assertThrows(ApiException.class, () -> service.createOccupancy(
                new OccupancyRequest(dto.reviewId(), "tester", "req-" + rid("occ"))));
        assertEquals(HttpStatus.CONFLICT, occ.status());
        assertEquals("OCCUPANCY_REVIEW_STALE", occ.code());

        // 诊断：固化版本与当前版本对照，失效原因可区分
        ReviewDiagnosticsDto diag = service.getReviewDiagnostics(dto.reviewId());
        assertFalse(diag.current());
        assertEquals(List.of("WEATHER_DUTY_VERSION_CHANGED"), diag.staleReasons());
        assertEquals(1, diag.frozenDutyVersion());
        assertEquals(2, diag.currentDutyVersion());
        assertEquals(1L, diag.frozenForecastVersion());
        assertEquals(1L, diag.latestForecastVersion());
        assertEquals("CLEAR", diag.conclusion());
        assertNotNull(diag.weatherSnapshot());
    }

    @Test
    void forecastRevisionMakesOldReviewStale() {
        service.configureWeatherRegion(regionReq("wr1", List.of(win(1000, 2000, 500, 300))));
        service.uploadForecast(forecastReq(fentry("wr1", 1000, 2000, 800, 400)));
        createRoute("r1", 0, 10, 100, 10);
        MutationResponse review = service.review(
                new ReviewRequest("r1", 1, 0L, 1L, "tester", "req-" + rid("review")));
        ReviewResultDto dto = reviewData(review);
        assertEquals("CLEAR", dto.conclusion());

        // 新预测上传（气象修订）：旧审核对当前查询与占用失效
        service.uploadForecast(forecastReq(fentry("wr1", 1000, 2000, 900, 500)));
        assertEquals(2, latestForecastVersion());
        assertEquals("STALE", service.getCurrentReview("r1").conclusion());
        ReviewDiagnosticsDto diag = service.getReviewDiagnostics(dto.reviewId());
        assertEquals(List.of("FORECAST_VERSION_CHANGED"), diag.staleReasons());
        assertEquals(1L, diag.frozenForecastVersion());
        assertEquals(2L, diag.latestForecastVersion());
        // 历史结论不变
        assertEquals("CLEAR", service.getReview(dto.reviewId()).conclusion());

        // 用新预测重新审核：恢复当前可用
        MutationResponse reReview = service.review(
                new ReviewRequest("r1", 1, 0L, 2L, "tester", "req-" + rid("review")));
        assertEquals("CLEAR", reviewData(reReview).conclusion());
        assertEquals("CLEAR", service.getCurrentReview("r1").conclusion());
    }

    @Test
    void occupancyKeepsFrozenVersionsAfterLaterRevisions() {
        service.configureWeatherRegion(regionReq("wr1", List.of(win(1000, 2000, 500, 300))));
        service.uploadForecast(forecastReq(fentry("wr1", 1000, 2000, 800, 400)));
        createRoute("r1", 0, 10, 100, 10);
        MutationResponse review = service.review(
                new ReviewRequest("r1", 1, 0L, 1L, "tester", "req-" + rid("review")));
        ReviewResultDto dto = reviewData(review);
        MutationResponse occupancy = service.createOccupancy(
                new OccupancyRequest(dto.reviewId(), "tester", "req-" + rid("occ")));
        OccupancyResult occ = objectMapper.convertValue(occupancy.data(), OccupancyResult.class);

        // 占用后的气象修订不改写已创建的占用记录（旧证据保持）
        service.configureWeatherRegion(regionReq("wr1", List.of(win(1000, 2000, 900, 300))));
        service.uploadForecast(forecastReq(fentry("wr1", 1000, 2000, 950, 450)));
        OccupancyResult stored = service.getOccupancy(occ.occupancyId());
        assertEquals(1L, stored.dutyVersion());
        assertEquals(1L, stored.forecastVersion());
        assertEquals(dto.reviewId(), stored.reviewId());

        // 同一审核不能重复占用
        ApiException again = assertThrows(ApiException.class, () -> service.createOccupancy(
                new OccupancyRequest(dto.reviewId(), "tester", "req-" + rid("occ2"))));
        assertEquals(HttpStatus.CONFLICT, again.status());
        assertEquals("OCCUPANCY_REVIEW_STALE", again.code());
    }

    @Test
    void occupancyRejectedWhenReviewStaleAfterRouteReplace() {
        createRoute("r1", 0, 10, 100, 10);
        MutationResponse review = service.review(
                new ReviewRequest("r1", 1, 0L, "req-" + rid("review")));
        ReviewResultDto dto = reviewData(review);
        assertEquals("CLEAR", dto.conclusion());

        service.replaceRoute(new RouteReplaceRequest(
                "r1", 1, pts(0, 0, 100, 0), "req-" + rid("replace")));
        ApiException ex = assertThrows(ApiException.class, () -> service.createOccupancy(
                new OccupancyRequest(dto.reviewId(), "tester", "req-" + rid("occ"))));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("OCCUPANCY_REVIEW_STALE", ex.code());
        assertEquals(0, count("occupancy"));

        // 占用不存在的审核 → 404
        ApiException missing = assertThrows(ApiException.class, () -> service.createOccupancy(
                new OccupancyRequest("rv_ghost", "tester", "req-" + rid("occ"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    // ============================ 幂等 ============================

    @Test
    void regionConfigReplayDoesNotBumpDutyTwice() {
        String requestId = "req-region-replay-" + rid("region");
        WeatherRegionConfigRequest req = new WeatherRegionConfigRequest("wr1", 40, 5, 60, 15,
                List.of(win(1000, 2000, 500, 300)), "tester", requestId);
        MutationResponse first = service.configureWeatherRegion(req);
        assertFalse(first.replayed());
        MutationResponse replay = service.configureWeatherRegion(req);
        assertTrue(replay.replayed());
        assertEquals(1, objectMapper.convertValue(replay.data(), WeatherRegionResult.class)
                .dutyVersion());
        // 重放不推进值班版本、不重复写窗口
        assertEquals(1, dutyVersion());
        assertEquals(1, count("weather_minima_window"));

        // 同键异参 → 409
        ApiException mismatch = assertThrows(ApiException.class, () -> service.configureWeatherRegion(
                new WeatherRegionConfigRequest("wr1", 40, 5, 60, 15,
                        List.of(win(1000, 2000, 600, 300)), "tester", requestId)));
        assertEquals(HttpStatus.CONFLICT, mismatch.status());
    }

    @Test
    void occupancyAndBatchReplayReturnFirstSnapshot() {
        service.configureWeatherRegion(regionReq("wr1", List.of(win(1000, 2000, 500, 300))));
        service.uploadForecast(forecastReq(fentry("wr1", 1000, 2000, 800, 400)));
        createRoute("r1", 0, 10, 100, 10);
        createRoute("r2", 0, -50, 100, -50);

        // 批量审核重放：返回首次完整响应快照，不重复创建
        String batchReqId = "req-batch-replay-" + rid("batch");
        BatchReviewRequest batchReq = new BatchReviewRequest(
                List.of(new BatchReviewItemDto("r1", 1, 0L),
                        new BatchReviewItemDto("r2", 1, 0L)),
                1L, "tester", batchReqId);
        MutationResponse first = service.reviewBatch(batchReq);
        MutationResponse replay = service.reviewBatch(batchReq);
        assertTrue(replay.replayed());
        BatchReviewResult firstResult = objectMapper.convertValue(first.data(), BatchReviewResult.class);
        BatchReviewResult replayResult = objectMapper.convertValue(replay.data(), BatchReviewResult.class);
        assertEquals(firstResult.results().get(0).reviewId(),
                replayResult.results().get(0).reviewId());
        assertEquals(2, count("review"));

        // 占用重放：同一占用记录
        String occReqId = "req-occ-replay-" + rid("occ");
        String reviewId = firstResult.results().get(0).reviewId();
        MutationResponse occFirst = service.createOccupancy(
                new OccupancyRequest(reviewId, "tester", occReqId));
        MutationResponse occReplay = service.createOccupancy(
                new OccupancyRequest(reviewId, "tester", occReqId));
        assertTrue(occReplay.replayed());
        assertEquals(
                objectMapper.convertValue(occFirst.data(), OccupancyResult.class).occupancyId(),
                objectMapper.convertValue(occReplay.data(), OccupancyResult.class).occupancyId());
        assertEquals(1, count("occupancy"));
    }
    @Test
    void duplicateOccupancyOnCurrentReviewRejected() {
        createRoute("r1", 0, 10, 100, 10);
        MutationResponse review = service.review(
                new ReviewRequest("r1", 1, 0L, "req-" + rid("review")));
        ReviewResultDto dto = reviewData(review);
        service.createOccupancy(new OccupancyRequest(dto.reviewId(), "tester", "req-" + rid("occ")));
        // 审核仍是当前结论，但已占用：拒绝重复占用
        ApiException ex = assertThrows(ApiException.class, () -> service.createOccupancy(
                new OccupancyRequest(dto.reviewId(), "tester", "req-" + rid("occ2"))));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("OCCUPANCY_ALREADY_EXISTS", ex.code());
        assertEquals(1, count("occupancy"));
    }

    // ============================ 并发 ============================

    @Test
    void concurrentRegionConfigAndReviewAreSerialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String routeId = "cr-" + i;
                String regionId = "wr-" + i;
                int y = 10 + i * 100;
                createRoute(routeId, 0, y, 100, y);
                long dutyBefore = dutyVersion();
                CyclicBarrier barrier = new CyclicBarrier(2);

                // 审核方：不提交预测版本；若配置先提交则必须 422，否则 CLEAR
                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.review(new ReviewRequest(
                                routeId, 1, 0L, null, "tester", "req-rv-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                // 配置方：创建恰好覆盖本轮航线的气象区域
                WeatherRegionConfigRequest configReq = new WeatherRegionConfigRequest(
                        regionId, 40, y - 5, 60, y + 5,
                        List.of(win(1000, 2000, 500, 300)), "tester", "req-cfg-" + regionId);
                Future<Object> configFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.configureWeatherRegion(configReq);
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object configResult = configFuture.get(15, TimeUnit.SECONDS);
                assertTrue(configResult instanceof MutationResponse, "区域配置必须成功");

                if (reviewResult instanceof MutationResponse mr) {
                    // 审核先拿锁：看到的是配置前的一致快照，区域尚未存在
                    ReviewResultDto dto = reviewData(mr);
                    assertEquals("CLEAR", dto.conclusion());
                    assertEquals(dutyBefore, dto.dutyVersion());
                    assertNull(dto.weatherViolations());
                } else {
                    // 配置先提交：路径经过气象区域但未提交预测，必须 422
                    ApiException ex = (ApiException) reviewResult;
                    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
                    assertEquals("FORECAST_MISSING", ex.code());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ============================ 只读查询 ============================

    @Test
    void readOnlyQueriesDoNotChangeStateAndHistoryOrderIsStable() {
        service.configureWeatherRegion(regionReq("wr1", List.of(win(1000, 2000, 500, 300))));
        service.uploadForecast(forecastReq(fentry("wr1", 1000, 2000, 800, 400)));
        createRoute("r1", 0, 10, 100, 10);
        MutationResponse review = service.review(
                new ReviewRequest("r1", 1, 0L, 1L, "tester", "req-" + rid("review")));
        ReviewResultDto dto = reviewData(review);
        MutationResponse occupancy = service.createOccupancy(
                new OccupancyRequest(dto.reviewId(), "tester", "req-" + rid("occ")));
        OccupancyResult occ = objectMapper.convertValue(occupancy.data(), OccupancyResult.class);

        long dutyBefore = dutyVersion();
        long forecastBefore = latestForecastVersion();
        int reviewCount = count("review");
        int occupancyCount = count("occupancy");
        int dedupCount = count("request_dedup");

        // 明细、历史与诊断查询均为只读
        WeatherRegionDetailDto region = service.getWeatherRegion("wr1");
        assertEquals(1, region.dutyVersion());
        assertEquals(1, region.windows().size());
        assertEquals(1000, region.windows().get(0).windowStartUtc());
        assertEquals(500, region.windows().get(0).visibilityMinM());

        assertEquals(1, service.getForecast(1).entries().size());
        assertTrue(service.getForecast(1).latest());
        assertEquals(occ.occupancyId(), service.getOccupancy(occ.occupancyId()).occupancyId());
        assertEquals("CLEAR", service.getReview(dto.reviewId()).conclusion());
        assertTrue(service.getReviewDiagnostics(dto.reviewId()).current());

        // 再审核一次：历史倒序稳定（最新在前），原结论均保留
        MutationResponse review2 = service.review(
                new ReviewRequest("r1", 1, 0L, 1L, "tester", "req-" + rid("review")));
        List<ReviewResultDto> history = service.getRouteReviewHistory("r1");
        assertEquals(2, history.size());
        assertEquals(reviewData(review2).reviewId(), history.get(0).reviewId());
        assertEquals(dto.reviewId(), history.get(1).reviewId());
        assertNull(history.get(0).current());

        // 读取不产生任何状态变化
        assertEquals(dutyBefore, dutyVersion());
        assertEquals(forecastBefore, latestForecastVersion());
        assertEquals(reviewCount + 1, count("review"));
        assertEquals(occupancyCount, count("occupancy"));
        assertEquals(dedupCount + 1, count("request_dedup"));
    }

    @Test
    void queriesOnMissingResourcesReturn404() {
        ApiException region = assertThrows(ApiException.class,
                () -> service.getWeatherRegion("ghost"));
        assertEquals(HttpStatus.NOT_FOUND, region.status());

        ApiException forecast = assertThrows(ApiException.class,
                () -> service.getForecast(7));
        assertEquals(HttpStatus.NOT_FOUND, forecast.status());

        ApiException occupancy = assertThrows(ApiException.class,
                () -> service.getOccupancy("oc_ghost"));
        assertEquals(HttpStatus.NOT_FOUND, occupancy.status());

        ApiException diagnostics = assertThrows(ApiException.class,
                () -> service.getReviewDiagnostics("rv_ghost"));
        assertEquals(HttpStatus.NOT_FOUND, diagnostics.status());
    }
}