package com.example.starter.race.persistence;

/**
 * result_snapshot_suspension 表行记录：封榜时冻结的已恢复中止事件（完整事件版本）。
 *
 * @param raceId          所属快照的赛事ID
 * @param eventKey        中止事件Key
 * @param checkpointCode  受影响起始检查点代码
 * @param startElapsedMs  中止开始累计耗时（毫秒）
 * @param resumeElapsedMs 恢复累计耗时（毫秒）
 */
public record SnapshotSuspensionRow(
        String raceId,
        String eventKey,
        String checkpointCode,
        long startElapsedMs,
        long resumeElapsedMs
) {
}
