package com.example.starter.race.persistence;

import com.example.starter.race.domain.CheckpointView;

/**
 * checkpoint 表行记录（赛事检查点配置，配置后不可修改）。
 *
 * @param raceId         所属赛事ID
 * @param checkpointCode 检查点代码，赛事内唯一
 * @param position       检查点顺序，从1连续递增
 * @param threeSource    是否启用三源计时共识：true 须登记三个不同传感器读数并显式裁定
 * @param createdAt      配置时间，Unix毫秒时间戳
 */
public record CheckpointRow(
        String raceId,
        String checkpointCode,
        int position,
        boolean threeSource,
        long createdAt
) implements CheckpointView {
}
