package com.example.starter.excursion;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 越限观测离线提交（三方合并）请求：携带 baseVersion 与三个可编辑字段的完整候选值。
 *
 * @param requestId   全局唯一请求标识（幂等去重键）
 * @param operator    操作者标识，非空
 * @param baseVersion 离线端基于的基线版本号
 * @param stationId   候选站号
 * @param minuteUtc   候选 UTC 整分钟时刻，ISO-8601（YYYY-MM-DDThh:mm:00Z）
 * @param reading     候选读数，十进制字符串，最多三位小数
 */
public record MergeExcursionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @Min(1) int baseVersion,
        @NotBlank @Size(max = 64) String stationId,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z",
                message = "minuteUtc must be an ISO-8601 UTC timestamp") String minuteUtc,
        @NotBlank @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?",
                message = "reading must be a decimal string with at most 3 fraction digits") String reading) {
}
