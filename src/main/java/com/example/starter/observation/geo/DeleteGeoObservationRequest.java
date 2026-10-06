package com.example.starter.observation.geo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 删除二维坐标观测记录请求：仅当 expectedVersion 匹配当前版本时生成新版本墓碑。
 *
 * @param requestId       全局唯一请求标识（幂等去重键）
 * @param expectedVersion 期望删除时的当前版本号
 * @param operator        操作者标识，非空
 */
public record DeleteGeoObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(1) Integer expectedVersion,
        @NotBlank @Size(max = 128) String operator) {
}
