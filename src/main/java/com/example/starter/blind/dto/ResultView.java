package com.example.starter.blind.dto;

/**
 * 结果提交/修订响应视图：结果值是协调员自报数据，不含处理代码或席位号等盲底。
 *
 * @param experimentId  实验编号
 * @param participantId 合成参与者编号
 * @param version       结果版本，首次提交为1，每次修订加一
 * @param value         整数结果值，范围 0~100
 * @param updatedAt     该版本写入时间，Unix 毫秒，UTC
 */
public record ResultView(
        String experimentId,
        String participantId,
        int version,
        int value,
        long updatedAt
) {
}
