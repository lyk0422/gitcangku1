package com.example.starter.race.persistence;

/**
 * result_snapshot_suspension 表行记录：封榜时冻结的完整中止恢复事件版本（只读）。
 *
 * @param raceId             所属快照的赛事ID
 * @param eventKey           中止事件键
 * @param checkpointCode     受影响起始检查点代码
 * @param checkpointPosition 受影响起始检查点顺序
 * @param startElapsedMs     中止开始的比赛相对耗时（毫秒）
 * @param resumeElapsedMs    恢复时刻（毫秒）；封榜时仍未恢复为 null
 * @param durationMs         中止时长（毫秒）；未恢复为 null
 * @param status             封榜时事件状态：SUSPENDED / RESUMED
 * @param displayOrder       展示顺序，从0开始，按登记时间与事件键
 */
public record SnapshotSuspensionRow(
        String raceId,
        String eventKey,
        String checkpointCode,
        int checkpointPosition,
        long startElapsedMs,
        Long resumeElapsedMs,
        Long durationMs,
        String status,
        int displayOrder
) {
}
