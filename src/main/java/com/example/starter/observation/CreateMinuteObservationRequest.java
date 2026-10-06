package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建分钟观测记录请求。
 *
 * @param requestId     全局唯一请求标识（幂等去重键）
 * @param operator      操作者标识（非空）
 * @param observationId 分钟观测记录唯一标识
 * @param stationId     站号
 * @param minuteUtc     观测时刻，ISO-8601 UTC 整分钟（如 2026-10-07T10:00:00Z）
 * @param reading       观测读数，十进制字符串，最多三位小数
 */
public record CreateMinuteObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotBlank @Size(max = 64) String observationId,
        @NotBlank @Size(max = 64) String stationId,
        @NotBlank @Size(max = 64) String minuteUtc,
        @NotBlank @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading) {
}
