package com.example.starter.race.api;

/**
 * 传感器采样登记响应。
 *
 * @param sampleId       全局唯一采样记录ID
 * @param bib            选手参赛号
 * @param checkpointCode 三源检查点代码
 * @param sensorId       传感器ID
 * @param sensorRevision 当前修订号（登记成功时为1）
 * @param elapsedMillis  传感器累计读数（毫秒）
 * @param createdAt      登记时间，Unix毫秒时间戳
 */
public record SensorSampleResponse(
        String sampleId,
        String bib,
        String checkpointCode,
        String sensorId,
        int sensorRevision,
        long elapsedMillis,
        long createdAt
) {
}
