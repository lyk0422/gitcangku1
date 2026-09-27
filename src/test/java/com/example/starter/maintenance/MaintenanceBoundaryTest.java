package com.example.starter.maintenance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 维护区间端点语义测试：使用固定 UTC 时钟精确验证左闭右开边界。
 */
@SpringBootTest
class MaintenanceBoundaryTest {

    private static final Instant T = Instant.parse("2026-03-01T08:00:00Z");

    /** 固定在 T（UTC）的时钟，替换生产环境系统时钟。 */
    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(T, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MaintenanceService maintenanceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM cluster_adjudication");
        jdbcTemplate.update("DELETE FROM cluster_merge_snapshot");
        jdbcTemplate.update("DELETE FROM station_observation");
        jdbcTemplate.update("DELETE FROM maintenance_window");
        jdbcTemplate.update("DELETE FROM observation_station");
        jdbcTemplate.update("DELETE FROM request_log");
    }

    private void registerStation(String requestId, String stationId) {
        maintenanceService.registerStation(
                new RegisterStationRequest(requestId, stationId, Instant.parse("2000-01-01T00:00:00Z")));
    }

    private BatchSubmissionRequest batch(String requestId, String submissionId) {
        return new BatchSubmissionRequest(requestId, List.of(new BatchSubmissionRequest.Item(
                submissionId, "cl-1", "st-1", T, "场地", "1.0", "备注")));
    }

    @Test
    void adjacentWindowsCanCoexistAndEndpointsAreHalfOpen() {
        registerStation("req-s1", "st-1");
        // 两个窗口共享端点：[T, T+1h) 与 [T+1h, T+2h)，左闭右开下不重叠
        maintenanceService.registerWindow("st-1", new RegisterWindowRequest(
                "req-w1", "win-1", T, T.plusSeconds(3600), 1));
        MaintenanceService.Outcome<WindowResponse> second = maintenanceService.registerWindow("st-1",
                new RegisterWindowRequest("req-w2", "win-2", T.plusSeconds(3600), T.plusSeconds(7200), 1));
        assertThat(second.status()).isEqualTo(201);

        // 提交时刻恰好为窗口起点 T：左闭，命中 win-1
        MaintenanceService.Outcome<BatchSubmissionResponse> atStart = maintenanceService.submitBatch(
                batch("req-b1", "sub-start"));
        assertThat(atStart.body().items().get(0).maintenanceFlag())
                .isEqualTo(StationObservationRecord.FLAG_MAINTENANCE_AFFECTED);
        assertThat(atStart.body().items().get(0).windowId()).isEqualTo("win-1");
        assertThat(atStart.body().submittedAtUtc()).isEqualTo(T);

        // 撤回 win-1 后登记新窗口 win-4 [T, T+30m)（与 win-2 不重叠）：提交时刻 T 命中新窗口
        maintenanceService.withdrawWindow("win-1", new WithdrawWindowRequest("req-wd1"));
        maintenanceService.registerWindow("st-1", new RegisterWindowRequest(
                "req-w4", "win-4", T, T.plusSeconds(1800), 2));
        MaintenanceService.Outcome<BatchSubmissionResponse> atStartAfterChange = maintenanceService.submitBatch(
                batch("req-b2", "sub-win4"));
        assertThat(atStartAfterChange.body().items().get(0).windowId()).isEqualTo("win-4");
        assertThat(atStartAfterChange.body().items().get(0).windowVersion()).isEqualTo(2);

        // 撤回 win-2/win-4，登记 [T-1h, T)：提交时刻恰好为窗口右端点 T：右开，不命中
        maintenanceService.withdrawWindow("win-2", new WithdrawWindowRequest("req-wd2"));
        maintenanceService.withdrawWindow("win-4", new WithdrawWindowRequest("req-wd4"));
        maintenanceService.registerWindow("st-1", new RegisterWindowRequest(
                "req-w3", "win-3", T.minusSeconds(3600), T, 1));
        MaintenanceService.Outcome<BatchSubmissionResponse> atEnd = maintenanceService.submitBatch(
                batch("req-b3", "sub-end"));
        assertThat(atEnd.body().items().get(0).maintenanceFlag())
                .isEqualTo(StationObservationRecord.FLAG_NONE);
        assertThat(atEnd.body().items().get(0).windowId()).isNull();
        assertThat(atEnd.body().affectedCount()).isZero();
    }
}
