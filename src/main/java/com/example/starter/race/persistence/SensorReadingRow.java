package com.example.starter.race.persistence;

/**
 * sensor_reading 表行记录：三源检查点上某选手某传感器的原始计时读数。
 *
 * @param raceId         所属赛事ID
 * @param bib            选手参赛号
 * @param checkpointCode 启用三源模式的检查点代码
 * @param sensorId       传感器ID，同一选手同一检查点下唯一
 * @param elapsedMillis  该传感器上报的累计耗时（毫秒）
 * @param revision       读数修订号，初始1，每次修订加一
 * @param createdAt      首次登记时间，Unix毫秒时间戳
 * @param updatedAt      最近一次修订时间，Unix毫秒时间戳
 */
public record SensorReadingRow(
        String raceId,
        String bib,
        String checkpointCode,
        String sensorId,
        long elapsedMillis,
        int revision,
        long createdAt,
        long updatedAt
) {
}
