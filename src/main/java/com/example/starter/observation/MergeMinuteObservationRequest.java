package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 分钟观测离线提交（三方合并）请求：携带基线版本与三个可编辑字段的完整候选值。
 *
 * @param requestId   全局唯一请求标识（幂等去重键）
 * @param operator    操作者标识（非空）
 * @param baseVersion 离线修改所基于的基线版本号
 * @param stationId   站号候选值
 * @param minuteUtc   观测时刻候选值，ISO-8601 UTC 整分钟
 * @param reading     观测读数候选值，十进制字符串，最多三位小数
 */
public record MergeMinuteObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Min(1) Integer baseVersion,
        @NotBlank @Size(max = 64) String stationId,
        @NotBlank @Size(max = 64) String minuteUtc,
        @NotBlank @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading) {
}
