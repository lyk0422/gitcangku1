package com.example.starter.blind.dto;

/**
 * 协调员领取处理包的普通视图：只含领取ID与随机包装码。
 * 严禁包含处理代码 treatment、包编号 kitNo、匹配数量或任何可解码编号。
 *
 * @param pickupId      领取ID，随机生成，全局唯一
 * @param experimentId  实验编号
 * @param participantId 合成参与者编号
 * @param packageCode   随机无含义包装码，与处理代码无对应规律
 * @param pickedAt      领取时间，Unix 毫秒，UTC
 */
public record KitPickupView(
        String pickupId,
        String experimentId,
        String participantId,
        String packageCode,
        long pickedAt
) {
}