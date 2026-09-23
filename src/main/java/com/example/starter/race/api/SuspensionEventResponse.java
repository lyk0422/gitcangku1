package com.example.starter.race.api;

import com.example.starter.race.domain.SuspensionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 中止事件响应。
 *
 * @param eventKey         中止事件Key
 * @param raceId           所属赛事ID
 * @param checkpointKey    受影响起始检查点代码
 * @param startElapsedMs   中止开始累计耗时（毫秒）
 * @param resumeElapsedMs  恢复累计耗时（毫秒）；未恢复为 null
 * @param status           SUSPENDED / RESUMED
 * @param createdAt        登记时间，Unix毫秒时间戳
 * @param resumedAt        恢复时间，Unix毫秒时间戳；未恢复为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SuspensionEventResponse(
        String eventKey,
        String raceId,
        String checkpointKey,
        long startElapsedMs,
        Long resumeElapsedMs,
        SuspensionStatus status,
        long createdAt,
        Long resumedAt
) {
}
