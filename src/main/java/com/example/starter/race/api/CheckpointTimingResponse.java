package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 分段通过记录响应。
 *
 * @param timingId       全局唯一分段记录ID
 * @param bib            选手参赛号
 * @param checkpointCode 检查点代码
 * @param position       检查点顺序
 * @param elapsedMillis  通过该检查点的累计耗时（毫秒）
 * @param medicalHold    提交时选手是否处于生效医疗暂停：true 为被排除计时，不参与排名
 * @param holdId         排除该计时的医疗暂停ID；未被排除为 null
 * @param createdAt      提交时间，Unix毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckpointTimingResponse(
        String timingId,
        String bib,
        String checkpointCode,
        int position,
        long elapsedMillis,
        boolean medicalHold,
        String holdId,
        long createdAt
) {
}
