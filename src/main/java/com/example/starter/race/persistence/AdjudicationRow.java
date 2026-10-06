package com.example.starter.race.persistence;

/**
 * adjudication 表行记录：三源检查点的一次显式裁定；历史裁定不覆写，
 * 读数修订后仅把 valid 置为 FALSE 并记录 invalidated_at。
 *
 * @param adjudicationId 裁定ID，全局唯一（第二层幂等键）
 * @param raceId         所属赛事ID
 * @param bib            选手参赛号
 * @param checkpointCode 裁定的三源检查点代码
 * @param medianMillis   裁定中位值（毫秒），同时写入原分段计时
 * @param basis          裁定时三条当前读数依据的结构化JSON（sensorId/revision/elapsedMillis）
 * @param valid          是否当前有效
 * @param createdAt      裁定时间，Unix毫秒时间戳
 * @param invalidatedAt  失效时间，Unix毫秒时间戳；仍有效为 null
 */
public record AdjudicationRow(
        String adjudicationId,
        String raceId,
        String bib,
        String checkpointCode,
        long medianMillis,
        String basis,
        boolean valid,
        long createdAt,
        Long invalidatedAt
) {
}
