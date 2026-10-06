package com.example.starter.observation.geo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建二维坐标观测记录请求。坐标单位米，协方差矩阵单位平方米，均为十进制字符串、最多三位小数。
 *
 * @param requestId     全局唯一请求标识（幂等去重键）
 * @param observationId 观测记录唯一标识
 * @param x             观测点 X 坐标（米）
 * @param y             观测点 Y 坐标（米）
 * @param a             协方差矩阵元素 a（平方米），必须为正且 a*c &gt; b*b
 * @param b             协方差矩阵元素 b（平方米）
 * @param c             协方差矩阵元素 c（平方米），必须为正且 a*c &gt; b*b
 * @param operator      操作者标识，非空
 */
public record CreateGeoObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @NotBlank @Pattern(regexp = GeoDecimal.DECIMAL_PATTERN, message = GeoDecimal.DECIMAL_MESSAGE) String x,
        @NotBlank @Pattern(regexp = GeoDecimal.DECIMAL_PATTERN, message = GeoDecimal.DECIMAL_MESSAGE) String y,
        @NotBlank @Pattern(regexp = GeoDecimal.DECIMAL_PATTERN, message = GeoDecimal.DECIMAL_MESSAGE) String a,
        @NotBlank @Pattern(regexp = GeoDecimal.DECIMAL_PATTERN, message = GeoDecimal.DECIMAL_MESSAGE) String b,
        @NotBlank @Pattern(regexp = GeoDecimal.DECIMAL_PATTERN, message = GeoDecimal.DECIMAL_MESSAGE) String c,
        @NotBlank @Size(max = 128) String operator) {
}
