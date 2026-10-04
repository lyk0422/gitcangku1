package com.example.starter.blind.dto;

/**
 * 样本配对锁定请求体；期望版本可选，提供时必须与当前有效样本链版本一致。
 *
 * @param expectedPreVersion  期望的 PRE 链版本；null 表示不校验
 * @param expectedPostVersion 期望的 POST 链版本；null 表示不校验
 */
public record LockSamplePairRequest(
        Long expectedPreVersion,
        Long expectedPostVersion
) {
}