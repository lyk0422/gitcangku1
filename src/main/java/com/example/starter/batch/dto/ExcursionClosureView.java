package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 异常闭包查询视图：一个 EXCURSION 运输段的异常原因、越界读数、超间隔读数对及处置结果。
 * disposition 为 null 表示该段尚未处置。
 */
public record ExcursionClosureView(
        String segmentKey,
        Instant startAt,
        Instant endAt,
        BigDecimal minTemp,
        BigDecimal maxTemp,
        List<String> reasons,
        List<OutOfRangeReading> outOfRangeReadings,
        List<GapBreach> gapBreaches,
        DispositionView disposition
) {

    /**
     * 越界读数明细。
     */
    public record OutOfRangeReading(Instant recordedAt, BigDecimal temperature) {
    }

    /**
     * 相邻读数间隔超过 30 分钟的读数对；gapMinutes 为实际间隔分钟数。
     */
    public record GapBreach(Instant previousAt, Instant nextAt, long gapMinutes) {
    }

    /**
     * 段处置结果：处置说明、处置人与处置时间。
     */
    public record DispositionView(String disposition, String actorId, Instant disposedAt) {
    }
}
