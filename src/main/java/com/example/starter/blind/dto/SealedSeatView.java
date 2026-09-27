package com.example.starter.blind.dto;

/**
 * 封存序号明细视图；遵循盲态纪律，不返回席位号 seatNo 与处理代码 treatment，
 * 仅返回不透明条目编号、区组号与封存时间，区组号本身不暴露区组内位置。
 *
 * @param entryId      封存条目自增编号（不透明，无业务含义）
 * @param retirementId 封存该序号的退役批次编号
 * @param experimentId 实验编号
 * @param blockNo      被封存序号所在区组号
 * @param createdAt    封存时间，Unix 毫秒，UTC
 */
public record SealedSeatView(
        long entryId,
        String retirementId,
        String experimentId,
        int blockNo,
        long createdAt
) {
}
