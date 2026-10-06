package com.example.starter.observation.geo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 二维坐标观测离线提交（三方合并）请求：坐标与协方差矩阵作为一个完整字段组携带候选值。
 *
 * @param requestId   全局唯一请求标识（幂等去重键）
 * @param baseVersion 离线修改所基于的基线版本号
 * @param x           观测点 X 坐标候选值（米）
 * @param y           观测点 Y 坐标候选值（米）
 * @param a           协方差矩阵元素 a 候选值（平方米）
 * @param b           协方差矩阵元素 b 候选值（平方米）
 * @param c           协方差矩阵元素 c 候选值（平方米）
 * @param operator    操作者标识，非空
 */
public record MergeGeoObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(1) Integer baseVersion,
        @NotBlank @Pattern(regexp = GeoDecimal.DECIMAL_PATTERN, message = GeoDecimal.DECIMAL_MESSAGE) String x,
        @NotBlank @Pattern(regexp = GeoDecimal.DECIMAL_PATTERN, message = GeoDecimal.DECIMAL_MESSAGE) String y,
        @NotBlank @Pattern(regexp = GeoDecimal.DECIMAL_PATTERN, message = GeoDecimal.DECIMAL_MESSAGE) String a,
        @NotBlank @Pattern(regexp = GeoDecimal.DECIMAL_PATTERN, message = GeoDecimal.DECIMAL_MESSAGE) String b,
        @NotBlank @Pattern(regexp = GeoDecimal.DECIMAL_PATTERN, message = GeoDecimal.DECIMAL_MESSAGE) String c,
        @NotBlank @Size(max = 128) String operator) {
}
