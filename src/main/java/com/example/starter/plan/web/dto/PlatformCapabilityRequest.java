package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * 站台管理方登记/更新站台有效能力的请求。仅追加新版本，不改写历史。
 *
 * @param requestKey         幂等键
 * @param platformId         站台 ID
 * @param maxLengthM         站台可容纳编组长度上限，单位米，必须为正
 * @param supportedPowerTypes 站台兼容供电制式代码列表，至少一项：AC25KV / DC1500V / DC3000V / NONE
 * @param accessible         站台是否具备无障碍登乘能力
 */
public record PlatformCapabilityRequest(
        @NotBlank String requestKey,
        @NotBlank String platformId,
        @NotNull @Positive @Max(10000) Integer maxLengthM,
        @NotEmpty List<@NotBlank String> supportedPowerTypes,
        @NotNull Boolean accessible) {
}
