package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 一次性配置赛事检查点请求；仅 OPEN 赛事且尚无任何分段记录时可用，配置后不可修改。
 *
 * @param checkpointCodes 检查点编码列表（1~20个），赛事内唯一，顺序从1连续递增
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record ConfigureCheckpointsRequest(
        @NotNull @Size(min = 1, max = 20) List<@NotBlank String> checkpointCodes,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
