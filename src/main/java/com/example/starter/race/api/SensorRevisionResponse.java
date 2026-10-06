package com.example.starter.race.api;

/**
 * 传感器读数修订响应。
 *
 * @param bib            选手参赛号
 * @param checkpointCode 三源检查点代码
 * @param sensorId       被修订的传感器ID
 * @param sensorRevision 修订后的新修订号
 * @param elapsedMillis  修订后的传感器累计读数（毫秒）
 * @param raceVersion    修订成功后的赛事版本号
 */
public record SensorRevisionResponse(
        String bib,
        String checkpointCode,
        String sensorId,
        int sensorRevision,
        long elapsedMillis,
        int raceVersion
) {
}
