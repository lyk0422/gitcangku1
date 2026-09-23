package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个中止恢复事件的只读视图。
 *
 * @param eventKey             事件键，赛事内唯一
 * @param checkpointKey        受影响起始检查点代码
 * @param checkpointPosition   受影响起始检查点顺序
 * @param startElapsedMs       中止开始的比赛相对耗时（毫秒）
 * @param resumeElapsedMs      恢复时刻（毫秒）；未恢复为 null
 * @param durationMs           中止时长=resumeElapsedMs-startElapsedMs（毫秒）；未恢复为 null
 * @param status               SUSPENDED-中止中，RESUMED-已恢复
 * @param versionAfterSuspend  中止登记后的赛事版本
 * @param versionAfterResume   恢复重算后的赛事版本；未恢复为 null
 * @param createdAt            中止登记时间，Unix毫秒时间戳
 * @param resumedAt            恢复提交时间，Unix毫秒时间戳；未恢复为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SuspensionEventResponse(
        String eventKey,
        String checkpointKey,
        int checkpointPosition,
        long startElapsedMs,
        Long resumeElapsedMs,
        Long durationMs,
        String status,
        int versionAfterSuspend,
        Integer versionAfterResume,
        long createdAt,
        Long resumedAt
) {
}
