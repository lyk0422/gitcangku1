package com.example.starter.kiln;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试支撑：清库、造程序/批次与合成测温点。
 */
final class KilnTestSupport {

    static final Instant BASE = Instant.parse("2026-02-01T00:00:00Z");

    private KilnTestSupport() {
    }

    static void clean(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM kiln_release_record");
        jdbc.update("DELETE FROM kiln_anomaly_mark");
        jdbc.update("DELETE FROM kiln_temp_point");
        jdbc.update("DELETE FROM kiln_upload_request");
        jdbc.update("DELETE FROM kiln_batch");
        jdbc.update("DELETE FROM kiln_segment");
        jdbc.update("DELETE FROM kiln_program");
    }

    /** 三段程序：0-1h 800±20，1-2h 1100±25，2-3h 950±15。 */
    static KilnDtos.CreateProgramRequest standardProgram() {
        return new KilnDtos.CreateProgramRequest("STD", "v1", List.of(
                new KilnDtos.SegmentInput(BASE, BASE.plus(1, ChronoUnit.HOURS), 800.0, 20.0),
                new KilnDtos.SegmentInput(BASE.plus(1, ChronoUnit.HOURS), BASE.plus(2, ChronoUnit.HOURS), 1100.0, 25.0),
                new KilnDtos.SegmentInput(BASE.plus(2, ChronoUnit.HOURS), BASE.plus(3, ChronoUnit.HOURS), 950.0, 15.0)));
    }

    /** 每 5 分钟一个点、温度取所属段目标值的合法轨迹（含起止边界）。 */
    static List<KilnDtos.PointInput> fullTrajectory() {
        List<KilnDtos.PointInput> points = new ArrayList<>();
        Instant end = BASE.plus(3, ChronoUnit.HOURS);
        for (Instant t = BASE; !t.isAfter(end); t = t.plus(5, ChronoUnit.MINUTES)) {
            points.add(new KilnDtos.PointInput(t, targetAt(t)));
        }
        return points;
    }

    static double targetAt(Instant t) {
        if (t.isBefore(BASE.plus(1, ChronoUnit.HOURS))) {
            return 800.0;
        }
        if (t.isBefore(BASE.plus(2, ChronoUnit.HOURS))) {
            return 1100.0;
        }
        return 950.0;
    }

    static KilnDtos.UploadPointsRequest upload(String requestId, List<KilnDtos.PointInput> points) {
        return new KilnDtos.UploadPointsRequest(requestId, points);
    }
}