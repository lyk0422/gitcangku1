package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 栅格离线合并请求：客户端基于 baseVersion 提交修改后的完整 16 格全图，不是局部补丁。
 *
 * @param requestId   全局唯一请求标识（幂等去重键）
 * @param baseVersion 离线修改所基于的基版号
 * @param operator    操作者标识（客户端来源），非空
 * @param cells       客户端完整图：16 个格值，按行优先排列，取值 0 或 1
 */
public record MergeMaskRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(1) Integer baseVersion,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(min = 16, max = 16) List<@NotNull Integer> cells) {
}
