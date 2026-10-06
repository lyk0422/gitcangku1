package com.example.starter.race.api;

import java.util.List;

/**
 * 三源检查点裁定响应。
 *
 * @param adjudicationId 裁定ID
 * @param bib            选手参赛号
 * @param checkpointCode 三源检查点代码
 * @param medianMillis   裁定中位值（毫秒），已写入原分段计时
 * @param readings       裁定引用的三条当前读数依据（按 sensorId 字典序）
 * @param valid          是否当前有效；读数修订后旧裁定失效
 * @param createdAt      裁定时间，Unix毫秒时间戳
 */
public record AdjudicationResponse(
        String adjudicationId,
        String bib,
        String checkpointCode,
        long medianMillis,
        List<SensorReadingBasis> readings,
        boolean valid,
        long createdAt
) {
}
