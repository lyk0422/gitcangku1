package com.example.starter.race.api;

/**
 * 检查点配置响应中的单个检查点。
 *
 * @param checkpointCode 检查点代码，赛事内唯一
 * @param position       检查点顺序，从1连续递增
 */
public record CheckpointResponse(
        String checkpointCode,
        int position
) {
}
