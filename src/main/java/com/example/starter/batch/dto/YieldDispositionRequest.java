package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 超限偏差处置确认请求。requestId 为被处置的核算版本键；
 * 同一处置人重复确认同一版本幂等返回，两名不同处置人确认后门禁解除。
 */
public record YieldDispositionRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "requestId 不能为空") String requestId
) {
}
