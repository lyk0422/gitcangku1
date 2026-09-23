package com.example.starter.race.persistence;

/**
 * checkpoint_timing_net 表行记录（选手逐检查点净分段，每次恢复一致重算后覆盖）。
 *
 * @param raceId         所属赛事ID
 * @param bib            选手参赛号
 * @param checkpointCode 检查点代码
 * @param position       检查点顺序，从1递增
 * @param netElapsedMs   净分段累计耗时（毫秒）
 * @param compensationMs 该检查点累计补偿毫秒数
 * @param updatedAt      最近一次净值重算时间，Unix毫秒时间戳
 */
public record CheckpointTimingNetRow(
        String raceId,
        String bib,
        String checkpointCode,
        int position,
        long netElapsedMs,
        long compensationMs,
        long updatedAt
) {
}
