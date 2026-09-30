package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 环境补偿放行单条拒绝原因（HTTP 422）：携带该条提交的实际温湿度读数与原因码。
 *
 * @param key         测量键
 * @param temperature 实际提交温度（℃，十进制字符串）
 * @param humidity    实际提交湿度（%RH，十进制字符串）
 * @param reasons     原因码：ENVIRONMENT_OUT_OF_RANGE 温湿度越界、
 *                    COEFFICIENT_NOT_EFFECTIVE 无生效系数版本、
 *                    MULTIPLE_COEFFICIENT_VERSIONS 同时命中多个版本、
 *                    CERTIFICATE_ENV_RANGE_UNDEFINED 证书未定义环境范围
 */
public record EnvironmentItemFailure(
        String key,
        String temperature,
        String humidity,
        List<String> reasons) {
}
