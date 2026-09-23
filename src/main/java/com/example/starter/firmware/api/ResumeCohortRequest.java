package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 队列人工恢复请求：仅 paused 队列可恢复，清零队列本轮统计并重新接受结算。
 */
public record ResumeCohortRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 256) String reason) {
}
