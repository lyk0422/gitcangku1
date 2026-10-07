package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 删除栅格观测请求：expectedVersion 匹配当前版本时生成新版本墓碑；墓碑不参与合并。
 *
 * @param requestId       全局唯一请求标识（幂等去重键）
 * @param expectedVersion 期望删除时的当前版本号
 * @param operator        操作者标识（客户端来源），非空
 */
public record DeleteMaskRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(1) Integer expectedVersion,
        @NotBlank @Size(max = 128) String operator) {
}
