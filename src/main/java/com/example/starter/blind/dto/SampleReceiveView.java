package com.example.starter.blind.dto;

/**
 * 接收样本响应（分析员视图）：只含二级码。
 * 严禁包含参与者编号、一级码、处理代码或实验内关联信息。
 *
 * @param secondaryCode 二级随机码，仅接收分析员本人可用
 * @param status        样本状态：RECEIVED
 * @param receivedAt    接收时间，Unix 毫秒，UTC
 */
public record SampleReceiveView(
        String secondaryCode,
        String status,
        long receivedAt
) {
}
