package com.example.starter.blind.dto;

/**
 * 结果提交/修订视图：只含结果值与版本号，不含处理代码或席位信息。
 *
 * @param experimentId  实验编号
 * @param participantId 合成参与者编号
 * @param value         整数结果值，0~100
 * @param version       结果版本号，首次提交为 1，每次修订加一
 * @param submittedAt   提交时间，Unix 毫秒，UTC
 */
public record ResultView(
        String experimentId,
        String participantId,
        int value,
        int version,
        long submittedAt
) {
}
