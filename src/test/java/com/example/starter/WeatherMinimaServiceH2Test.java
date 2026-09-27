package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.ForecastEntryDto;
import com.example.starter.api.dto.ForecastUploadRequest;
import com.example.starter.api.dto.ForecastUploadResult;
import com.example.starter.api.dto.ForecastVersionDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupationCreateRequest;
import com.example.starter.api.dto.OccupationResult;
import com.example.starter.api.dto.ReviewBatchItem;
import com.example.starter.api.dto.ReviewBatchRequest;
import com.example.starter.api.dto.ReviewBatchResult;
import com.example.starter.api.dto.ReviewDiagnosticsDto;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.WeatherMinimaResult;
import com.example.starter.api.dto.WeatherMinimaSetRequest;
import com.example.starter.api.dto.WeatherMinimaWindowDto;
import com.example.starter.api.dto.WeatherViolationDto;
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
 * 空域气象最低标准业务的 H2 数据库测试（MODE=MySQL）。
 * 覆盖标准配置、预测上传、气象审查（WEATHER_BLOCKED）、时效冻结（STALE）、
 * 占用、批量审核、幂等与真实并发边界。
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
        jdbc.update("UPDATE weather_meta SET standards_version = 0, forecast_version = 0 WHERE id = 1");
    }

    private String rid(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    private static List<RoutePointDto> pts(int... xy) {
        return java.util.stream.IntStream.range(0, xy.length / 2)
                .mapToObj(i -> new RoutePointDto(xy[2 * i], xy[2 * i + 1]))
                .toList();
    }

    private void createZone(String zoneId, int xMin, int yMin, int xMax, int yMax) {
        service.createZone(new ZoneCreateRequest(zoneId, xMin, yMin, xMax, yMax,
                "req-" + rid("zone")));
    }

    private void createRoute(String routeId, int... xy) {
        service.createRoute(new RouteCreateRequest(routeId, pts(xy), "req-" + rid("route")));
    }

    private void setMinima(String zoneId, List<WeatherMinimaWindowDto> windows) {
        service.setWeatherMinima(new WeatherMinimaSetRequest(
                zoneId, windows, "tester", "req-" + rid("minima")));
    }

    private MutationResponse uploadForecast(List<ForecastEntryDto> entries) {
        return service.uploadForecast(new ForecastUploadRequest(
                entries, "tester", "req-" + rid("forecast")));
    }

    private ReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    // ============================ 标准配置 ============================

    @Test
    void minimaSetReplaceAndQueryKeepsStableOrder() {
        createZone("z1", 40, 5, 60, 15);
        // 故意乱序提交，响应与查询必须按起点升序稳定返回
        MutationResponse resp = service.setWeatherMinima(new WeatherMinimaSetRequest(
                "z1",
                List.of(new WeatherMinimaWindowDto(2000L, 3000L, 1500, 300),
                        new WeatherMinimaWindowDto(0L, 1000L, 1000, 200)),
                "tester", "req-" + rid("minima")));
        WeatherMinimaResult result = objectMapper.convertValue(resp.data(), WeatherMinimaResult.class);
        assertEquals(1L, result.standardsVersion());
        assertEquals(2, result.windowCount());
        assertEquals(0L, result.windows().get(0).effectiveFrom());
        assertEquals(2000L, result.windows().get(1).effectiveFrom());

        // 整体替换：新标准集合生效，标准版本加一
        setMinima("z1", List.of(new WeatherMinimaWindowDto(5000L, 6000L, 800, 100)));
        WeatherMinimaResult current = service.getWeatherMinima("z1");
        assertEquals(2L, current.standardsVersion());
        assertEquals(1, current.windowCount());
        assertEquals(5000L, current.windows().get(0).effectiveFrom());
        assertEquals(800, current.windows().get(0).minVisibilityMeters());
    }

    @Test
    void minimaNegativeOverlapAndDegenerateAre422() {
        createZone("z1", 0, 0, 10, 10);
        // 负值标准 → 422
        ApiException negative = assertThrows(ApiException.class,
                () -> setMinima("z1", List.of(new WeatherMinimaWindowDto(0L, 100L, -1, 200))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, negative.status());
        assertEquals("NEGATIVE_MINIMA", negative.code());

        // 时段重叠（左闭右开：[0,100) 与 [50,150) 重叠）→ 422
        ApiException overlap = assertThrows(ApiException.class,
                () -> setMinima("z1", List.of(
                        new WeatherMinimaWindowDto(0L, 100L, 100, 100),
                        new WeatherMinimaWindowDto(50L, 150L, 100, 100))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, overlap.status());
        assertEquals("MINIMA_WINDOW_OVERLAP", overlap.code());

        // 退化时段 → 422
        ApiException degenerate = assertThrows(ApiException.class,
                () -> setMinima("z1", List.of(new WeatherMinimaWindowDto(100L, 100L, 100, 100))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, degenerate.status());

        // 全部失败：没有任何写入，标准版本未推进
        assertEquals(0L, jdbc.queryForObject(
                "SELECT standards_version FROM weather_meta WHERE id = 1", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM weather_minima", Integer.class));

        // 左闭右开边界相邻不算重叠：[0,100) 与 [100,200) 合法
        setMinima("z1", List.of(
                new WeatherMinimaWindowDto(0L, 100L, 100, 100),
                new WeatherMinimaWindowDto(100L, 200L, 100, 100)));
        assertEquals(2, service.getWeatherMinima("z1").windowCount());

        // 区域不存在 → 404
        ApiException missing = assertThrows(ApiException.class,
                () -> setMinima("ghost", List.of(new WeatherMinimaWindowDto(0L, 1L, 1, 1))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    // ============================ 预测上传 ============================

    @Test
    void forecastUploadVersionsAndQuery() {
        createZone("z1", 0, 0, 10, 10);
        createZone("z2", 20, 20, 30, 30);
        // 从未上传时查询当前版本 → 404
        ApiException noForecast = assertThrows(ApiException.class, service::getCurrentForecast);
        assertEquals(HttpStatus.NOT_FOUND, noForecast.status());

        MutationResponse up1 = uploadForecast(List.of(
                new ForecastEntryDto("z2", 0L, 1000L, 5000, 400),
                new ForecastEntryDto("z1", 0L, 1000L, 3000, 300)));
        ForecastUploadResult r1 = objectMapper.convertValue(up1.data(), ForecastUploadResult.class);
        assertEquals(1L, r1.forecastVersion());
        assertEquals(2, r1.entryCount());

        // 明细查询按 zoneId 稳定排序
        ForecastVersionDto current = service.getCurrentForecast();
        assertEquals(1L, current.forecastVersion());
        assertEquals("z1", current.entries().get(0).zoneId());
        assertEquals("z2", current.entries().get(1).zoneId());

        // 第二次上传生成新版本，旧版本仍可查（历史不可变）
        uploadForecast(List.of(new ForecastEntryDto("z1", 0L, 1000L, 6000, 500)));
        assertEquals(2L, service.getCurrentForecast().forecastVersion());
        assertEquals(2, service.getForecast(1L).entryCount());

        // 不存在的版本 → 404
        ApiException missing = assertThrows(ApiException.class, () -> service.getForecast(99L));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    @Test
    void forecastValidationFailuresAre422or404AndAtomic() {
        createZone("z1", 0, 0, 10, 10);
        // 负值 → 422
        ApiException negative = assertThrows(ApiException.class,
                () -> uploadForecast(List.of(new ForecastEntryDto("z1", 0L, 100L, -5, 100))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, negative.status());

        // 同区域时段重叠 → 422
        ApiException overlap = assertThrows(ApiException.class,
                () -> uploadForecast(List.of(
                        new ForecastEntryDto("z1", 0L, 100L, 100, 100),
                        new ForecastEntryDto("z1", 50L, 200L, 100, 100))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, overlap.status());
        assertEquals("FORECAST_WINDOW_OVERLAP", overlap.code());

        // 引用不存在区域 → 404
        ApiException missingZone = assertThrows(ApiException.class,
                () -> uploadForecast(List.of(new ForecastEntryDto("ghost", 0L, 100L, 1, 1))));
        assertEquals(HttpStatus.NOT_FOUND, missingZone.status());

        // 整批原子：一条非法则全部不写入，版本不推进
        assertEquals(0L, jdbc.queryForObject(
                "SELECT forecast_version FROM weather_meta WHERE id = 1", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM weather_forecast", Integer.class));
    }

    // ============================ 气象审查 ============================

    @Test
    void reviewWeatherBlockedReturnsActualRequiredAndDeficit() {
        createZone("z1", 40, 5, 60, 15);
        createRoute("r1", 0, 10, 100, 10);
        setMinima("z1", List.of(new WeatherMinimaWindowDto(0L, 1000L, 5000, 300)));
        // 预测能见度 3200（差 1800）、云底 250（差 50）均低于标准
        uploadForecast(List.of(new ForecastEntryDto("z1", 0L, 1000L, 3200, 250)));

        MutationResponse resp = service.review(
                new ReviewRequest("r1", 1, 1L, 1L, "req-" + rid("review")));
        ReviewResultDto dto = dataOf(resp);
        assertEquals("WEATHER_BLOCKED", dto.conclusion());
        assertEquals(1L, dto.forecastVersion());
        assertEquals(1L, dto.standardsVersion());
        assertEquals(2, dto.weatherViolations().size());
        WeatherViolationDto visibility = dto.weatherViolations().stream()
                .filter(v -> "VISIBILITY".equals(v.metric())).findFirst().orElseThrow();
        assertEquals(3200, visibility.actualValue());
        assertEquals(5000, visibility.requiredValue());
        assertEquals(1800, visibility.deficit());
        WeatherViolationDto cloudBase = dto.weatherViolations().stream()
                .filter(v -> "CLOUD_BASE".equals(v.metric())).findFirst().orElseThrow();
        assertEquals(250, cloudBase.actualValue());
        assertEquals(300, cloudBase.requiredValue());
        assertEquals(50, cloudBase.deficit());

        // 历史查询保留 WEATHER_BLOCKED 原结论与明细
        ReviewResultDto history = service.getReview(dto.reviewId());
        assertEquals("WEATHER_BLOCKED", history.conclusion());
        assertEquals(2, history.weatherViolations().size());
    }

    @Test
    void reviewWeatherOkFallsBackToGeometricConclusion() {
        createZone("z1", 40, 5, 60, 15);
        createRoute("r1", 0, 10, 100, 10);
        setMinima("z1", List.of(new WeatherMinimaWindowDto(0L, 1000L, 5000, 300)));
        // 预测满足标准：气象不阻断，但几何命中禁飞区仍 BLOCKED
        uploadForecast(List.of(new ForecastEntryDto("z1", 0L, 1000L, 8000, 600)));
        MutationResponse resp = service.review(
                new ReviewRequest("r1", 1, 1L, 1L, "req-" + rid("review")));
        ReviewResultDto dto = dataOf(resp);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(List.of("z1"), dto.hitZoneIds());
        assertTrue(dto.weatherViolations().isEmpty());
    }

    @Test
    void reviewMissingForecastIs422AndCreatesNothing() {
        createZone("z1", 40, 5, 60, 15);
        createRoute("r1", 0, 10, 100, 10);
        setMinima("z1", List.of(new WeatherMinimaWindowDto(0L, 1000L, 5000, 300)));
        // 预测只覆盖 [0,400)，标准时段 [0,1000) 未完整覆盖 → 422
        uploadForecast(List.of(new ForecastEntryDto("z1", 0L, 400L, 8000, 600)));

        String reviewKey = "req-" + rid("review");
        ApiException ex = assertThrows(ApiException.class, () -> service.review(
                new ReviewRequest("r1", 1, 1L, 1L, reviewKey)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("FORECAST_MISSING", ex.code());
        // 失败不产生任何审核记录，也不占用 requestId（前置操作的去重记录不受影响）
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?", Integer.class, reviewKey));
    }

    @Test
    void reviewWithStaleForecastVersionIs409() {
        createZone("z1", 40, 5, 60, 15);
        createRoute("r1", 0, 10, 100, 10);
        setMinima("z1", List.of(new WeatherMinimaWindowDto(0L, 1000L, 100, 100)));
        uploadForecast(List.of(new ForecastEntryDto("z1", 0L, 1000L, 8000, 600)));
        // 新预测上传后版本推进到 2，提交旧版本 1 → 409
        uploadForecast(List.of(new ForecastEntryDto("z1", 0L, 1000L, 8000, 600)));
        ApiException ex = assertThrows(ApiException.class, () -> service.review(
                new ReviewRequest("r1", 1, 1L, 1L, "req-" + rid("review"))));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("VERSION_CONFLICT", ex.code());
    }

    @Test
    void reviewWithoutForecastKeepsLegacyBehavior() {
        createZone("z1", 40, 5, 60, 15);
        createRoute("r1", 0, 10, 100, 10);
        setMinima("z1", List.of(new WeatherMinimaWindowDto(0L, 1000L, 99999, 99999)));
        // 不提交预测版本：不做气象检查，几何命中即 BLOCKED
        MutationResponse resp = service.review(
                new ReviewRequest("r1", 1, 1L, "req-" + rid("review")));
        ReviewResultDto dto = dataOf(resp);
        assertEquals("BLOCKED", dto.conclusion());
        assertNull(dto.forecastVersion());
        assertNull(dto.standardsVersion());
        assertNull(dto.weatherViolations());
    }

    // ============================ 时效冻结 ============================

    @Test
    void weatherRevisionStalesOldClearButNeverRewritesHistory() {
        createZone("z1", 40, 5, 60, 15);
        createRoute("r1", 0, 10, 100, 10);
        setMinima("z1", List.of(new WeatherMinimaWindowDto(0L, 1000L, 100, 100)));
        uploadForecast(List.of(new ForecastEntryDto("z1", 0L, 1000L, 8000, 600)));
        MutationResponse resp = service.review(
                new ReviewRequest("r1", 1, 1L, 1L, "req-" + rid("review")));
        ReviewResultDto dto = dataOf(resp);
        // 几何命中但气象满足标准 → BLOCKED（几何）；换一条不命中的航线验证 CLEAR 冻结
        createRoute("r2", 0, 50, 100, 50);
        MutationResponse clearResp = service.review(
                new ReviewRequest("r2", 1, 1L, 1L, "req-" + rid("review")));
        ReviewResultDto clear = dataOf(clearResp);
        assertEquals("CLEAR", clear.conclusion());
        assertTrue(service.getCurrentReview("r2").current());

        // 气象标准修订：旧 CLEAR 不被改写，但当前查询必须 STALE
        setMinima("z1", List.of(new WeatherMinimaWindowDto(0L, 1000L, 200, 200)));
        ReviewResultDto stale = service.getCurrentReview("r2");
        assertEquals("STALE", stale.conclusion());
        assertFalse(stale.current());
        assertEquals("CLEAR", service.getReview(clear.reviewId()).conclusion());

        // 诊断：standardsVersion 不再匹配，其余版本仍匹配
        ReviewDiagnosticsDto diag = service.getReviewDiagnostics(clear.reviewId());
        assertTrue(diag.stale());
        assertEquals("CLEAR", diag.conclusion());
        assertEquals("STALE", diag.effectiveConclusion());
        ReviewDiagnosticsDto.VersionCheck standards = diag.versions().stream()
                .filter(v -> "standardsVersion".equals(v.name())).findFirst().orElseThrow();
        assertEquals(1L, standards.reviewed());
        assertEquals(2L, standards.current());
        assertFalse(standards.match());
        assertTrue(diag.versions().stream()
                .filter(v -> !"standardsVersion".equals(v.name())).allMatch(v -> v.match()));

        // 用新标准版本重新审核后恢复可用；预测修订同样使结果 STALE
        MutationResponse reReview = service.review(
                new ReviewRequest("r2", 1, 1L, 1L, "req-" + rid("review")));
        assertTrue(dataOf(reReview).current());
        uploadForecast(List.of(new ForecastEntryDto("z1", 0L, 1000L, 8000, 600)));
        assertEquals("STALE", service.getCurrentReview("r2").conclusion());
        ReviewDiagnosticsDto diag2 = service.getReviewDiagnostics(dataOf(reReview).reviewId());
        assertFalse(diag2.versions().stream()
                .filter(v -> "forecastVersion".equals(v.name())).findFirst().orElseThrow().match());
    }

    // ============================ 占用 ============================

    @Test
    void occupationUsesFrozenWeatherVersionAndRejectsStale() {
        createZone("z1", 40, 5, 60, 15);
        createRoute("r1", 0, 50, 100, 50);
        setMinima("z1", List.of(new WeatherMinimaWindowDto(0L, 1000L, 100, 100)));
        uploadForecast(List.of(new ForecastEntryDto("z1", 0L, 1000L, 8000, 600)));
        MutationResponse review = service.review(
                new ReviewRequest("r1", 1, 1L, 1L, "req-" + rid("review")));
        ReviewResultDto dto = dataOf(review);
        assertEquals("CLEAR", dto.conclusion());

        // 创建占用：固化审核时的全部版本
        MutationResponse occ = service.createOccupation(new OccupationCreateRequest(
                dto.reviewId(), "r1", "ops-1", "req-" + rid("occ")));
        OccupationResult created = objectMapper.convertValue(occ.data(), OccupationResult.class);
        assertEquals(dto.reviewId(), created.reviewId());
        assertEquals(1, created.routeVersion());
        assertEquals(1L, created.airspaceVersion());
        assertEquals(1L, created.forecastVersion());
        assertEquals(1L, created.standardsVersion());

        // 幂等重放返回同一占用
        MutationResponse replay = service.createOccupation(new OccupationCreateRequest(
                dto.reviewId(), "r1", "ops-1", occ.requestId()));
        assertTrue(replay.replayed());
        assertEquals(created.occupationId(),
                objectMapper.convertValue(replay.data(), OccupationResult.class).occupationId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM occupation", Integer.class));

        // 气象修订后审核 STALE：新占用被拒绝，已创建占用不受影响
        uploadForecast(List.of(new ForecastEntryDto("z1", 0L, 1000L, 8000, 600)));
        ApiException stale = assertThrows(ApiException.class,
                () -> service.createOccupation(new OccupationCreateRequest(
                        dto.reviewId(), "r1", "ops-1", "req-" + rid("occ"))));
        assertEquals(HttpStatus.CONFLICT, stale.status());
        assertEquals("REVIEW_STALE", stale.code());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM occupation", Integer.class));
        assertEquals(created.occupationId(),
                service.getRouteOccupations("r1").get(0).occupationId());
    }

    @Test
    void occupationRejectsNonClearMismatchAndMissing() {
        createZone("z1", 40, 5, 60, 15);
        createRoute("r1", 0, 10, 100, 10);
        MutationResponse blocked = service.review(
                new ReviewRequest("r1", 1, 1L, "req-" + rid("review")));
        ReviewResultDto blockedDto = dataOf(blocked);
        assertEquals("BLOCKED", blockedDto.conclusion());

        // 非 CLEAR 结论 → 409
        ApiException notClear = assertThrows(ApiException.class,
                () -> service.createOccupation(new OccupationCreateRequest(
                        blockedDto.reviewId(), "r1", "ops", "req-" + rid("occ"))));
        assertEquals(HttpStatus.CONFLICT, notClear.status());
        assertEquals("OCCUPATION_NOT_CLEARED", notClear.code());

        // 审核不存在 → 404
        ApiException missing = assertThrows(ApiException.class,
                () -> service.createOccupation(new OccupationCreateRequest(
                        "rv_ghost", "r1", "ops", "req-" + rid("occ"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        // 航线与审核记录不一致 → 409
        createRoute("r2", 0, 50, 100, 50);
        MutationResponse clear = service.review(
                new ReviewRequest("r2", 1, 1L, "req-" + rid("review")));
        ApiException mismatch = assertThrows(ApiException.class,
                () -> service.createOccupation(new OccupationCreateRequest(
                        dataOf(clear).reviewId(), "r1", "ops", "req-" + rid("occ"))));
        assertEquals(HttpStatus.CONFLICT, mismatch.status());
        assertEquals("OCCUPATION_ROUTE_MISMATCH", mismatch.code());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM occupation", Integer.class));
    }

    // ============================ 批量审核 ============================

    @Test
    void batchReviewIsAtomicWhenAnyRouteMissesForecast() {
        createZone("z1", 40, 5, 60, 15);
        createRoute("r1", 0, 10, 100, 10);   // 经过 z1（有标准）
        createRoute("r2", 0, 50, 100, 50);   // 不经过任何区域
        setMinima("z1", List.of(new WeatherMinimaWindowDto(0L, 1000L, 100, 100)));
        // 预测只覆盖 z1 的 [0,400)，r1 缺完整预测 → 整批 422，零结果
        uploadForecast(List.of(new ForecastEntryDto("z1", 0L, 400L, 8000, 600)));

        String batchKey = "req-" + rid("batch");
        ApiException ex = assertThrows(ApiException.class, () -> service.reviewBatch(
                new ReviewBatchRequest(List.of(
                        new ReviewBatchItem("r2", 1, 1L, 1L),
                        new ReviewBatchItem("r1", 1, 1L, 1L)),
                        "tester", batchKey)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("FORECAST_MISSING", ex.code());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?", Integer.class, batchKey));

        // 补齐预测后整批成功，结果顺序与提交一致
        uploadForecast(List.of(new ForecastEntryDto("z1", 0L, 1000L, 8000, 600)));
        MutationResponse ok = service.reviewBatch(new ReviewBatchRequest(List.of(
                new ReviewBatchItem("r2", 1, 1L, 2L),
                new ReviewBatchItem("r1", 1, 1L, 2L)),
                "tester", "req-" + rid("batch")));
        ReviewBatchResult batch = objectMapper.convertValue(ok.data(), ReviewBatchResult.class);
        assertEquals(2, batch.results().size());
        assertEquals("r2", batch.results().get(0).routeId());
        assertEquals("CLEAR", batch.results().get(0).conclusion());
        assertEquals("r1", batch.results().get(1).routeId());
        assertEquals("BLOCKED", batch.results().get(1).conclusion());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));

        // 批量幂等重放：同键同参返回首次结果，不新增记录
        MutationResponse replay = service.reviewBatch(new ReviewBatchRequest(List.of(
                new ReviewBatchItem("r2", 1, 1L, 2L),
                new ReviewBatchItem("r1", 1, 1L, 2L)),
                "tester", ok.requestId()));
        assertTrue(replay.replayed());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));
    }

    @Test
    void batchReviewRejectsStaleVersionAtomically() {
        createRoute("r1", 0, 0, 10, 10);
        createRoute("r2", 20, 20, 30, 30);
        // 第二条航线版本错误 → 整批 409，第一条也不创建
        ApiException ex = assertThrows(ApiException.class, () -> service.reviewBatch(
                new ReviewBatchRequest(List.of(
                        new ReviewBatchItem("r1", 1, 0L, null),
                        new ReviewBatchItem("r2", 9, 0L, null)),
                        "tester", "req-" + rid("batch"))));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));
    }

    // ============================ 历史与诊断查询 ============================

    @Test
    void routeReviewHistoryKeepsStableOrderAndOriginalConclusions() {
        createZone("z1", 40, 5, 60, 15);
        createRoute("r1", 0, 10, 100, 10);
        MutationResponse first = service.review(
                new ReviewRequest("r1", 1, 1L, "req-" + rid("review")));
        service.revokeZone(new com.example.starter.api.dto.ZoneRevokeRequest(
                "z1", "req-" + rid("revoke")));
        MutationResponse second = service.review(
                new ReviewRequest("r1", 1, 2L, "req-" + rid("review")));

        List<ReviewResultDto> history = service.getRouteReviews("r1");
        assertEquals(2, history.size());
        // 稳定倒序：与数据库 ORDER BY created_at DESC, review_id DESC 一致
        List<String> expectedOrder = jdbc.queryForList(
                "SELECT review_id FROM review WHERE route_id = 'r1' "
                        + "ORDER BY created_at DESC, review_id DESC", String.class);
        assertEquals(expectedOrder,
                history.stream().map(ReviewResultDto::reviewId).toList());
        // 每条保留各自原结论，历史查询不携带 current 标记
        java.util.Map<String, String> conclusionByReviewId = new java.util.HashMap<>();
        for (ReviewResultDto dto : history) {
            conclusionByReviewId.put(dto.reviewId(), dto.conclusion());
            assertNull(dto.current());
        }
        assertEquals("BLOCKED", conclusionByReviewId.get(dataOf(first).reviewId()));
        assertEquals("CLEAR", conclusionByReviewId.get(dataOf(second).reviewId()));

        // 诊断对不存在的审核 → 404
        ApiException ex = assertThrows(ApiException.class,
                () -> service.getReviewDiagnostics("rv_ghost"));
        assertEquals(HttpStatus.NOT_FOUND, ex.status());
    }

    // ============================ 幂等 ============================

    @Test
    void weatherEndpointsAreIdempotentAndFailureDoesNotConsumeKey() {
        createZone("z1", 0, 0, 10, 10);
        String minimaKey = "fixed-minima-key";
        WeatherMinimaSetRequest minimaReq = new WeatherMinimaSetRequest("z1",
                List.of(new WeatherMinimaWindowDto(0L, 100L, 100, 100)), "tester", minimaKey);
        MutationResponse first = service.setWeatherMinima(minimaReq);
        assertFalse(first.replayed());
        MutationResponse replay = service.setWeatherMinima(minimaReq);
        assertTrue(replay.replayed());
        // 重放不推进标准版本
        assertEquals(1L, jdbc.queryForObject(
                "SELECT standards_version FROM weather_meta WHERE id = 1", Long.class));
        // 同键异参 → 409（操作者不同也算异参）
        ApiException mismatch = assertThrows(ApiException.class,
                () -> service.setWeatherMinima(new WeatherMinimaSetRequest("z1",
                        List.of(new WeatherMinimaWindowDto(0L, 100L, 100, 100)),
                        "other-operator", minimaKey)));
        assertEquals(HttpStatus.CONFLICT, mismatch.status());

        // 失败不占键：负值 422 后同键可成功
        String failKey = "fixed-minima-fail";
        assertThrows(ApiException.class, () -> service.setWeatherMinima(
                new WeatherMinimaSetRequest("z1",
                        List.of(new WeatherMinimaWindowDto(0L, 100L, -1, 100)),
                        "tester", failKey)));
        MutationResponse ok = service.setWeatherMinima(new WeatherMinimaSetRequest("z1",
                List.of(new WeatherMinimaWindowDto(0L, 200L, 100, 100)), "tester", failKey));
        assertFalse(ok.replayed());

        // 预测上传同键重放：版本只推进一次
        String forecastKey = "fixed-forecast-key";
        ForecastUploadRequest forecastReq = new ForecastUploadRequest(
                List.of(new ForecastEntryDto("z1", 0L, 100L, 100, 100)), "tester", forecastKey);
        service.uploadForecast(forecastReq);
        MutationResponse forecastReplay = service.uploadForecast(forecastReq);
        assertTrue(forecastReplay.replayed());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT forecast_version FROM weather_meta WHERE id = 1", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM weather_forecast", Integer.class));
    }

    // ============================ 并发 ============================

    @Test
    void concurrentForecastUploadAndReviewNeverMixesVersions() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String zoneId = "cw-z" + i;
                String routeId = "cw-r" + i;
                int y = 10 + i * 100;
                createZone(zoneId, 40, y - 5, 60, y + 5);
                createRoute(routeId, 0, y, 100, y);
                setMinima(zoneId, List.of(new WeatherMinimaWindowDto(0L, 1000L, 100, 100)));
                // 先上传一版可完整覆盖的预测，审核将提交该版本
                uploadForecast(List.of(new ForecastEntryDto(zoneId, 0L, 1000L, 8000, 600)));
                long forecastBefore = jdbc.queryForObject(
                        "SELECT forecast_version FROM weather_meta WHERE id = 1", Long.class);
                long airspaceVersion = jdbc.queryForObject(
                        "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class);

                CyclicBarrier barrier = new CyclicBarrier(2);
                String fid = zoneId;
                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.review(new ReviewRequest(
                                routeId, 1, airspaceVersion, forecastBefore,
                                "req-cw-review-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> uploadFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.uploadForecast(new ForecastUploadRequest(
                                List.of(new ForecastEntryDto(fid, 0L, 1000L, 8000, 600)),
                                "tester", "req-cw-forecast-" + fid));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object uploadResult = uploadFuture.get(15, TimeUnit.SECONDS);
                assertTrue(uploadResult instanceof MutationResponse, "预测上传必须成功");

                if (reviewResult instanceof MutationResponse mr) {
                    // 审核先拿锁：固化的必须是审核时一致的旧预测版本
                    ReviewResultDto dto = dataOf(mr);
                    assertEquals(forecastBefore, dto.forecastVersion());
                } else {
                    // 上传先提交：预测版本已推进，审核必须 409，不能产生混杂结论
                    ApiException ex = (ApiException) reviewResult;
                    assertEquals(HttpStatus.CONFLICT, ex.status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameMinimaRequestIdTakesEffectOnce() throws Exception {
        createZone("cz", 0, 0, 10, 10);
        String requestId = "concurrent-minima-key";
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.setWeatherMinima(new WeatherMinimaSetRequest("cz",
                            List.of(new WeatherMinimaWindowDto(0L, 100L, 100, 100)),
                            "tester", requestId));
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
            assertEquals(1, firstCount, "仅一次请求真正生效");
            assertEquals(n - 1, replayCount);
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT standards_version FROM weather_meta WHERE id = 1", Long.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM weather_minima", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }
}
