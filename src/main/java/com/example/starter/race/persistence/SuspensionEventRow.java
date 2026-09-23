package com.example.starter.race.persistence;

import com.example.starter.race.domain.SuspensionView;

/**
 * suspension_event 表行记录（中止恢复事件）。
 *
 * @param eventKey            事件键，赛事内唯一
 * @param raceId              所属赛事ID
 * @param checkpointCode      受影响起始检查点代码
 * @param checkpointPosition  受影响起始检查点顺序（登记时固化）
 * @param startElapsedMs      中止开始的比赛相对耗时（毫秒）
 * @param resumeElapsedMs     恢复时刻（毫秒）；未恢复为 null
 * @param durationMs          中止时长=resumeElapsedMs-startElapsedMs（毫秒）；未恢复为 null
 * @param status              事件状态：SUSPENDED / RESUMED
 * @param versionAfterSuspend 中止登记后的赛事版本
 * @param versionAfterResume  恢复重算后的赛事版本；未恢复为 null
 * @param createdAt           中止登记时间，Unix毫秒时间戳
 * @param resumedAt           恢复提交时间，Unix毫秒时间戳；未恢复为 null
 */
public record SuspensionEventRow(
        String eventKey,
        String raceId,
        String checkpointCode,
        int checkpointPosition,
        long startElapsedMs,
        Long resumeElapsedMs,
        Long durationMs,
        String status,
        int versionAfterSuspend,
        Integer versionAfterResume,
        long createdAt,
        Long resumedAt
) implements SuspensionView {

    public boolean resumed() {
        return "RESUMED".equals(status);
    }

    @Override
    public String checkpointKey() {
        return checkpointCode;
    }
}
