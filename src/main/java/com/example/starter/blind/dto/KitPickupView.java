package com.example.starter.blind.dto;

/**
 * 处理包领取视图：协调员只能得到随机包装码与领取编号。
 * 严禁包含处理代码、处理包编号、匹配数量或任何可解码编号。
 *
 * @param pickupId      领取编号，全局唯一
 * @param experimentId  实验编号
 * @param participantId 合成参与者编号
 * @param wrapperCode   随机无含义包装码，与处理代码无对应规律
 * @param createdAt     领取时间，Unix 毫秒，UTC
 */
public record KitPickupView(
        String pickupId,
        String experimentId,
        String participantId,
        String wrapperCode,
        long createdAt
) {
}