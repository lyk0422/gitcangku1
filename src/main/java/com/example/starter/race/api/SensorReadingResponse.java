package com.example.starter.race.api;

/**
 * 裁定依据中的单条传感器读数（裁定时冻结，含当前修订号）。
 *
 * @param sensorId       传感器ID
 * @param sensorRevision 裁定时该读数的修订号
 * @param elapsedMillis  裁定时该传感器的累计读数（毫秒）
 */
public record SensorReadingResponse(
        String sensorId,
        int sensorRevision,
        long elapsedMillis
) {
}
