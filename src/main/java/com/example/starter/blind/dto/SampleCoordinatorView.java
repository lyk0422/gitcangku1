package com.example.starter.blind.dto;

/**
 * 协调员样本查询视图：仅一级码、接收标识与有结果标识。
 * 严禁包含二级码、分析员编号或结果值。
 *
 * @param experimentId  实验编号
 * @param participantId 合成参与者编号
 * @param primaryCode   一级随机码
 * @param received      是否已被分析员接收
 * @param hasResult     是否已录入结果（不含结果值）
 */
public record SampleCoordinatorView(
        String experimentId,
        String participantId,
        String primaryCode,
        boolean received,
        boolean hasResult
) {
}
