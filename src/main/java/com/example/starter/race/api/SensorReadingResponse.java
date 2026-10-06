package com.example.starter.race.api;

/**
 * 传感器读数响应。
 *
 * @param bib            选手参赛号
 * @param checkpointCode 三源检查点代码
 * @param sensorId       传感器ID
 * @param elapsedMillis  当前累计耗时（毫秒）
 * @param revision       当前读数修订号（初始1，每次修订加一）
 * @param createdAt      首次登记时间，Unix毫秒时间戳
 * @param updatedAt      最近一次修订时间，Unix毫秒时间戳
 */
public record SensorReadingResponse(
        String bib,
        String checkpointCode,
        String sensorId,
        long elapsedMillis,
        int revision,
        long createdAt,
        long updatedAt
) {
}
