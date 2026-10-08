package com.example.starter.race.persistence;

import com.example.starter.race.domain.NeutralLegView;

/**
 * neutral_leg 表行记录（计时前一次性固定的中立区间定义，启用后不再变更）。
 *
 * @param raceId        所属赛事ID
 * @param legIndex      中立区间序号，按起点顺序从1连续递增
 * @param startPosition 起点检查点顺序；0表示发枪零点
 * @param endPosition   终点检查点顺序，严格大于起点
 * @param createdAt     配置时间，Unix毫秒时间戳
 */
public record NeutralLegRow(
        String raceId,
        int legIndex,
        int startPosition,
        int endPosition,
        long createdAt
) implements NeutralLegView {
}
