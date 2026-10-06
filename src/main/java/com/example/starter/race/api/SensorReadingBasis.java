package com.example.starter.race.api;

/**
 * 裁定依据中的一条传感器读数（裁定时的当前修订快照）。
 *
 * @param sensorId      传感器ID
 * @param revision      裁定引用的读数修订号
 * @param elapsedMillis 裁定引用的累计耗时（毫秒）
 */
public record SensorReadingBasis(
        String sensorId,
        int revision,
        long elapsedMillis
) {
}
