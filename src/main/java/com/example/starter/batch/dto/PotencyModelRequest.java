package com.example.starter.batch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 注册新版效价模型请求。版本由服务按当前最大版本 +1 分配；
 * roundingMode 取值为 HALF_UP/HALF_EVEN/DOWN/UP。
 */
public record PotencyModelRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "scale 不能为空")
        @Min(value = 0, message = "scale 必须在 0～10 之间")
        @Max(value = 10, message = "scale 必须在 0～10 之间")
        Integer scale,
        @NotBlank(message = "roundingMode 不能为空") String roundingMode
) {
}
