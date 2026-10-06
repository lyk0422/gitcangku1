package com.example.starter.race.persistence;

/**
 * sensor_adjudication 表行记录（一次三源共识裁定，历史保留不覆写）。
 *
 * @param adjudicationId     全局唯一裁定ID（第二层幂等键），同时作为写入分段计时的 timingId
 * @param raceId             所属赛事ID
 * @param bib                选手参赛号
 * @param checkpointCode     三源检查点代码
 * @param medianElapsedMillis 三条当前读数的中位值（毫秒）
 * @param sensorReadings     裁定时冻结的三条传感器读数及各自修订号（JSON数组）
 * @param raceVersion        裁定成功后的赛事版本号
 * @param superseded         是否已因采样修订失效：false-有效，true-已失效
 * @param operator           裁定操作者
 * @param createdAt          裁定时间，Unix毫秒时间戳
 */
public record SensorAdjudicationRow(
        String adjudicationId,
        String raceId,
        String bib,
        String checkpointCode,
        long medianElapsedMillis,
        String sensorReadings,
        int raceVersion,
        boolean superseded,
        String operator,
        long createdAt
) {
}
