package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyCreateRequest;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.WeatherForecastEntryDto;
import com.example.starter.api.dto.WeatherForecastUploadRequest;
import com.example.starter.api.dto.WeatherForecastUploadResult;
import com.example.starter.api.dto.WeatherMinimaConfigRequest;
import com.example.starter.api.dto.WeatherMinimaDetailDto;
import com.example.starter.api.dto.WeatherWindowDto;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.api.dto.ZoneRevokeRequest;
import com.example.starter.service.AirspaceReviewService;
import com.example.starter.service.WeatherService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 气象标准配置、预测上传、审查与占用的真实并发测试（H2 行级锁串行化）。
 * 用 CyclicBarrier 协调真实并发操作并设置超时，核对最终业务数据而非打印。
 */
@SpringBootTest
class WeatherConcurrencyH2Test {

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

    private long weatherVersion() {
        Long v = jdbc.queryForObject(
                "SELECT weather_version FROM weather_meta WHERE id = 1", Long.class);
        return v == null ? 0L : v;
    }

    @Test
    void concurrentMinimaConfigOnSameZoneIsSerialized() throws Exception {
        reviewService.createZone(new ZoneCreateRequest("z1", 40, 5, 60, 15, rid("zone")));
        reviewService.revokeZone(new ZoneRevokeRequest("z1", rid("revoke")));
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 两个线程同时整体替换同一区域的标准集合（不同窗口、不同 requestId）
            Future<MutationResponse> f1 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return weatherService.configureMinima(new WeatherMinimaConfigRequest(
                        rid("minima"), "op-a", "z1",
                        List.of(new WeatherWindowDto(1000L, 2000L, 1000, 300))));
            });
            Future<MutationResponse> f2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return weatherService.configureMinima(new WeatherMinimaConfigRequest(
                        rid("minima"), "op-b", "z1",
                        List.of(new WeatherWindowDto(5000L, 6000L, 700, 100))));
            });
            MutationResponse r1 = f1.get(20, TimeUnit.SECONDS);
            MutationResponse r2 = f2.get(20, TimeUnit.SECONDS);
            // 两次配置都成功且串行：版本恰好加两次，最终状态是其中一份完整集合
            assertEquals(2L, weatherVersion());
            WeatherMinimaDetailDto detail = weatherService.getMinimaDetail("z1");
            assertEquals(1, detail.windows().size());
            long start = detail.windows().get(0).startUtc();
            assertTrue(start == 1000L || start == 5000L,
                    "最终标准必须是某一次提交的完整集合，实际起点: " + start);
            assertEquals(2, (r1.replayed() ? 0 : 1) + (r2.replayed() ? 0 : 1));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void occupancyAndWeatherRevisionAreSerializedInSubmissionOrder() throws Exception {
        // 准备一条 CLEAR 审查（固化气象版本 fv）
        reviewService.createZone(new ZoneCreateRequest("z1", 40, 5, 60, 15, rid("zone")));
        reviewService.revokeZone(new ZoneRevokeRequest("z1", rid("revoke")));
        reviewService.createRoute(new RouteCreateRequest("r1", pts(0, 10, 100, 10), rid("route")));
        weatherService.configureMinima(new WeatherMinimaConfigRequest(
                rid("minima"), "op-a", "z1",
                List.of(new WeatherWindowDto(1000L, 2000L, 1000, 300))));
        MutationResponse up = weatherService.uploadForecast(new WeatherForecastUploadRequest(
                rid("forecast"), "op-a",
                List.of(new WeatherForecastEntryDto("z1", 1000L, 2000L, 1500, 500))));
        long fv = objectMapper.convertValue(up.data(), WeatherForecastUploadResult.class)
                .forecastVersion();
        Long airspace = jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class);
        MutationResponse clear = reviewService.review(
                new ReviewRequest("r1", 1, airspace, rid("review"), fv));
        ReviewResultDto clearDto = objectMapper.convertValue(clear.data(), ReviewResultDto.class);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 线程 A 创建占用；线程 B 上传新预测（气象修订使旧审查 STALE）
            Future<Object> fa = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    return weatherService.createOccupancy(
                            new OccupancyCreateRequest(rid("occ"), "op-a", clearDto.reviewId()));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Future<MutationResponse> fb = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return weatherService.uploadForecast(new WeatherForecastUploadRequest(
                        rid("forecast"), "op-b",
                        List.of(new WeatherForecastEntryDto("z1", 1000L, 2000L, 900, 500))));
            });
            Object a = fa.get(20, TimeUnit.SECONDS);
            fb.get(20, TimeUnit.SECONDS);

            Long occupancyCount = jdbc.queryForObject("SELECT COUNT(*) FROM occupancy", Long.class);
            if (a instanceof MutationResponse occResp) {
                // 占用先裁决：占用成功且固化审查时的气象版本 fv
                OccupancyResult occ = objectMapper.convertValue(occResp.data(), OccupancyResult.class);
                assertEquals(fv, occ.weatherVersion());
                assertEquals(1L, occupancyCount);
            } else {
                // 修订先裁决：占用必须拒绝 STALE 审查，且不留占用记录
                ApiException ex = (ApiException) a;
                assertEquals(HttpStatus.CONFLICT, ex.status());
                assertEquals("OCCUPANCY_REVIEW_STALE", ex.code());
                assertEquals(0L, occupancyCount);
            }
            // 两种裁决下修订都已生效，气象版本恰好推进一次
            assertEquals(fv + 1, weatherVersion());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}