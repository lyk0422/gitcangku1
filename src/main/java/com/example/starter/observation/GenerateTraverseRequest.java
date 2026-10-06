package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 闭合导线成果生成请求：3 至 10 条不同观测按顺序组成闭合导线，顺序有意义。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param resultId  成果唯一标识
 * @param operator  执行成果生成的操作者标识
 * @param edges     导线边引用列表，3 至 10 条且观测记录互不相同，顺序有意义
 */
public record GenerateTraverseRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String resultId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(min = 3, max = 10) List<@Valid TraverseEdgeRef> edges) {
}
