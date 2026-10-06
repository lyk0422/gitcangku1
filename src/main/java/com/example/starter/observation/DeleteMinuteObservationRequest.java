package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 删除分钟观测记录请求：仅当 expectedVersion 匹配当前版本时生成新版本墓碑。
 *
 * @param requestId       全局唯一请求标识（幂等去重键）
 * @param operator        操作者标识（非空）
 * @param expectedVersion 期望删除时的当前版本号
 */
public record DeleteMinuteObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Min(1) Integer expectedVersion) {
}
