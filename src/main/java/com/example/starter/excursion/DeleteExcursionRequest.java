package com.example.starter.excursion;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 删除越限观测请求：expectedVersion 匹配当前版本时生成新版本墓碑。
 *
 * @param requestId       全局唯一请求标识（幂等去重键）
 * @param operator        操作者标识，非空
 * @param expectedVersion 期望的当前版本号（乐观锁）
 */
public record DeleteExcursionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @Min(1) int expectedVersion) {
}
