package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建 4x4 二值栅格观测请求：保存完整 16 格初始图，初始版本为 1。
 *
 * @param requestId     全局唯一请求标识（幂等去重键）
 * @param observationId 栅格观测唯一标识
 * @param operator      操作者标识（客户端来源），非空
 * @param cells         16 个格值，按行优先排列，取值 0 或 1；勾选格必须非空且四邻接连通
 */
public record CreateMaskRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(min = 16, max = 16) List<@NotNull Integer> cells) {
}
