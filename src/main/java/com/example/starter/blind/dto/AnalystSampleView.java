package com.example.starter.blind.dto;

/**
 * 分析员样本查询视图：仅本人二级码、状态与结果。
 * 严禁包含参与者编号、一级码或处理代码。
 *
 * @param secondaryCode 二级随机码
 * @param status        样本状态：RECEIVED / RESULT
 * @param resultValue   整数结果；null 表示未录入
 * @param receivedAt    接收时间，Unix 毫秒，UTC
 * @param resultAt      录入时间，Unix 毫秒，UTC；null 表示未录入
 */
public record AnalystSampleView(
        String secondaryCode,
        String status,
        Integer resultValue,
        long receivedAt,
        Long resultAt
) {
}
