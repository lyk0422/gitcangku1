package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建观测点（新类型观测）请求：二维坐标与协方差矩阵作为完整字段组一次提交。
 *
 * @param requestId     全局唯一请求标识（幂等去重键）
 * @param observationId 观测点唯一标识
 * @param x             X 坐标，单位米，十进制字符串，最多三位小数
 * @param y             Y 坐标，单位米，十进制字符串，最多三位小数
 * @param a             协方差矩阵元素 a，单位平方米，十进制字符串，最多三位小数，必须为正
 * @param b             协方差矩阵元素 b，单位平方米，十进制字符串，最多三位小数
 * @param c             协方差矩阵元素 c，单位平方米，十进制字符串，最多三位小数，必须为正
 * @param operator      操作者标识（非空）
 */
public record CreatePositionedObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
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
