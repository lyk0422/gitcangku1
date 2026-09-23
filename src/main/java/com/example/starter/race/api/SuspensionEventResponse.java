package com.example.starter.race.api;

import com.example.starter.race.domain.EventStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 中止/恢复事件响应与事件历史条目。
 *
 * @param eventKey        中止事件ID
 * @param raceId          所属赛事ID
 * @param checkpointKey   受影响起始检查点代码
 * @param startElapsedMs  中止开始累计耗时点（毫秒）
 * @param resumeElapsedMs 恢复累计耗时点（毫秒）；未恢复为 null
 * @param durationMs      中止时长=resumeElapsedMs-startElapsedMs（毫秒）；未恢复为 null
 * @param status          SUSPENDED-中止中，RESUMED-已恢复
 * @param createdAt       中止登记时间，Unix毫秒时间戳
 * @param resumedAt       恢复登记时间；未恢复为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SuspensionEventResponse(
        String eventKey,
        String raceId,
        String checkpointKey,
        long startElapsedMs,
        Long resumeElapsedMs,
        Long durationMs,
        EventStatus status,
        long createdAt,
        Long resumedAt
) {
}
