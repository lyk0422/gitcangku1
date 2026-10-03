package com.example.starter.prediction.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.starter.prediction.PassPrediction;

/**
 * 预测版本视图。
 */
public record PredictionView(
        long id,
        String predictionKey,
        int version,
        String satelliteId,
        Instant windowStartUtc,
        Instant windowEndUtc,
        BigDecimal minElevationDeg,
        long plannedDataMb,
        String status,
        Instant createdAtUtc) {

    /**
     * 从实体转换。
     */
    public static PredictionView from(PassPrediction prediction) {
        return new PredictionView(
                prediction.id(),
                prediction.predictionKey(),
                prediction.version(),
                prediction.satelliteId(),
                prediction.windowStartUtc(),
                prediction.windowEndUtc(),
                prediction.minElevationDeg(),
                prediction.plannedDataMb(),
                prediction.status(),
                prediction.createdAtUtc());
    }
}