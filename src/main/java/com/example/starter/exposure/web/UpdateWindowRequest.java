package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 调整公告归因窗口请求。调整后窗口版本号 +1，历史版本与历史归因不重算。
 *
 * @param requestId                 写操作全局唯一幂等键
 * @param operator                  操作者标识，参与幂等指纹
 * @param attributionWindowMinutes  新的 UTC 归因窗口，单位分钟，取值 1～1440
 */
public record UpdateWindowRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotNull @Min(1) @Max(1440) Integer attributionWindowMinutes
) {
}
