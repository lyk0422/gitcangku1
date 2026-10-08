package com.example.starter.blind.dto;

/**
 * 录入结果响应（分析员视图）：只含本人二级码与结果。
 *
 * @param secondaryCode 二级随机码
 * @param status        样本状态：RESULT
 * @param resultValue   录入的整数结果
 * @param resultAt      录入时间，Unix 毫秒，UTC
 */
public record SampleResultView(
        String secondaryCode,
        String status,
        int resultValue,
        long resultAt
) {
}
