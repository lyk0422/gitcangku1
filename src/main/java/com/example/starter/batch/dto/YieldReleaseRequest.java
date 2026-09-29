package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 产率门禁放行请求。仅在偏差合格或超限版本经两名不同处置人确认后可放行。
 */
public record YieldReleaseRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey
) {
}
