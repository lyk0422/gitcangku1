package com.example.starter.race.persistence;

import com.example.starter.race.domain.EventStatus;

/**
 * race_event 表行记录：一次分组中止/恢复事件。
 *
 * @param eventKey           中止事件ID，全局唯一
 * @param raceId             所属赛事ID
 * @param checkpointKey      受影响起始检查点代码
 * @param checkpointPosition 受影响起始检查点顺序（登记时固化）
 * @param startElapsedMs     中止开始累计耗时点（毫秒）
 * @param resumeElapsedMs    恢复累计耗时点（毫秒）；未恢复为 null
 * @param status             SUSPENDED-中止中，RESUMED-已恢复
 * @param createdAt          中止登记时间，Unix毫秒时间戳
 * @param resumedAt          恢复登记时间；未恢复为 null
 */
public record RaceEventRow(
        String eventKey,
        String raceId,
        String checkpointKey,
        int checkpointPosition,
        long startElapsedMs,
        Long resumeElapsedMs,
        EventStatus status,
        long createdAt,
        Long resumedAt
) {
}
