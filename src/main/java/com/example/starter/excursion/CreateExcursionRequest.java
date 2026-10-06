package com.example.starter.excursion;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建越限观测请求。
 *
 * @param requestId     全局唯一请求标识（幂等去重键）
 * @param operator      操作者标识，非空
 * @param observationId 观测记录唯一标识
 * @param stationId     站号
 * @param minuteUtc     UTC 整分钟时刻，ISO-8601（YYYY-MM-DDThh:mm:00Z），秒与毫秒必须为零
 * @param reading       观测读数，十进制字符串，最多三位小数
 */
public record CreateExcursionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotBlank @Size(max = 64) String observationId,
        @NotBlank @Size(max = 64) String stationId,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z",
                message = "minuteUtc must be an ISO-8601 UTC timestamp") String minuteUtc,
        @NotBlank @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?",
                message = "reading must be a decimal string with at most 3 fraction digits") String reading) {
}
