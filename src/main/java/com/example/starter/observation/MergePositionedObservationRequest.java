package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 观测点离线提交（三方合并）请求：坐标与协方差矩阵作为一个完整字段组携带完整候选值。
 *
 * @param requestId   全局唯一请求标识（幂等去重键）
 * @param baseVersion 离线修改所基于的基线版本号
 * @param x           X 坐标候选值，单位米，十进制字符串，最多三位小数
 * @param y           Y 坐标候选值，单位米，十进制字符串，最多三位小数
 * @param a           协方差矩阵元素 a 候选值，单位平方米，必须为正
 * @param b           协方差矩阵元素 b 候选值，单位平方米
 * @param c           协方差矩阵元素 c 候选值，单位平方米，必须为正
 * @param operator    操作者标识（非空）
 */
public record MergePositionedObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(1) Integer baseVersion,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "x must be a decimal string with at most 3 fraction digits")
        String x,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "y must be a decimal string with at most 3 fraction digits")
        String y,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "a must be a decimal string with at most 3 fraction digits")
        String a,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "b must be a decimal string with at most 3 fraction digits")
        String b,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "c must be a decimal string with at most 3 fraction digits")
        String c,
        @NotBlank @Size(max = 128) String operator) {
}
