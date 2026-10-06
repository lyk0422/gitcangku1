package com.example.starter.race.persistence;

import com.example.starter.race.domain.SensorQuorum;

/**
 * sensor_sample 表行记录（三源检查点某选手某传感器的原始读数）。
 *
 * @param sampleId       全局唯一采样记录ID（第二层幂等键）
 * @param raceId         所属赛事ID
 * @param bib            选手参赛号
 * @param checkpointCode 三源检查点代码
 * @param sensorId       传感器ID，同一选手同一检查点内唯一
 * @param sensorRevision 该传感器读数修订号，初始1，每次修订加一
 * @param elapsedMillis  传感器累计读数（毫秒，1~86400000）
 * @param operator       最近一次登记/修订的操作者
 * @param createdAt      登记时间，Unix毫秒时间戳
 * @param updatedAt      最近一次修订时间，Unix毫秒时间戳
 */
public record SensorSampleRow(
        String sampleId,
        String raceId,
        String bib,
        String checkpointCode,
        String sensorId,
        int sensorRevision,
        long elapsedMillis,
        String operator,
        long createdAt,
        long updatedAt
) implements SensorQuorum.SensorReadingView {
}
