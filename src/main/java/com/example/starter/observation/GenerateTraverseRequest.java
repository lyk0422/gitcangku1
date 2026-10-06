package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 闭合导线成果生成请求：3 至 10 个不同观测及其期望当前版本，顺序有意义（逐边构成导线）。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param operator  操作者标识
 * @param legs      导线边引用列表，顺序即边序；每个观测至多引用一次
 */
public record GenerateTraverseRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(min = 3, max = 10) List<@Valid TraverseLegRef> legs) {
}
