package com.example.starter.race.api;

import java.util.List;

/**
 * 三源计时裁定响应。
 *
 * @param adjudicationId      全局唯一裁定ID，同时作为写入分段计时的 timingId
 * @param bib                 选手参赛号
 * @param checkpointCode      三源检查点代码
 * @param medianElapsedMillis 三条当前读数的中位值（毫秒），已写入原分段计时
 * @param readings            裁定时冻结的三条传感器读数及各自修订号（中位依据）
 * @param raceVersion         裁定成功后的赛事版本号
 * @param createdAt           裁定时间，Unix毫秒时间戳
 */
public record SensorAdjudicationResponse(
        String adjudicationId,
        String bib,
        String checkpointCode,
        long medianElapsedMillis,
        List<SensorReadingResponse> readings,
        int raceVersion,
        long createdAt
) {
}
