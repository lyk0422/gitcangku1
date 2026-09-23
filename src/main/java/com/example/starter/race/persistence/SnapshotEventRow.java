package com.example.starter.race.persistence;

/**
 * result_snapshot_event 表行记录：封榜时固化的中止事件（完整事件版本）。
 *
 * @param raceId          所属快照的赛事ID
 * @param eventKey        中止事件ID
 * @param checkpointKey   受影响起始检查点代码
 * @param startElapsedMs  中止开始累计耗时点（毫秒）
 * @param resumeElapsedMs 恢复累计耗时点（毫秒）
 * @param eventOrder      事件顺序，从0开始，按中止开始点升序
 */
public record SnapshotEventRow(
        String raceId,
        String eventKey,
        String checkpointKey,
        long startElapsedMs,
        long resumeElapsedMs,
        int eventOrder
) {
}
