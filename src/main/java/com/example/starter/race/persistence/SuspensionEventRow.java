package com.example.starter.race.persistence;

import com.example.starter.race.domain.SuspensionStatus;

/**
 * suspension_event 表行记录。
 *
 * @param eventKey           中止事件Key，全局唯一
 * @param raceId             所属赛事ID
 * @param checkpointCode     受影响起始检查点代码
 * @param checkpointPosition 受影响起始检查点顺序（登记时固化）
 * @param startElapsedMs     中止开始累计耗时（毫秒）
 * @param resumeElapsedMs    恢复累计耗时（毫秒）；未恢复为 null
 * @param status             SUSPENDED-中止中，RESUMED-已恢复
 * @param createdAt          登记时间，Unix毫秒时间戳
 * @param resumedAt          恢复时间，Unix毫秒时间戳；未恢复为 null
 */
public record SuspensionEventRow(
        String eventKey,
        String raceId,
        String checkpointCode,
        int checkpointPosition,
        long startElapsedMs,
        Long resumeElapsedMs,
        SuspensionStatus status,
        long createdAt,
        Long resumedAt
) {
}
