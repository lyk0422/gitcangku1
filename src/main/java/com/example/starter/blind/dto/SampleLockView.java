package com.example.starter.blind.dto;

/**
 * 配对锁定结果视图（协调员）：含配对码与锁定时刻冻结的两份摘要。
 *
 * @param experimentId   实验编号
 * @param participantId  合成参与者编号
 * @param pairCode       随机配对码，全局唯一，与参与者无对应规律
 * @param preDigest      锁定时冻结的 PRE 摘要
 * @param postDigest     锁定时冻结的 POST 摘要
 * @param version        锁定时的样本链版本号
 * @param lockedAt       锁定时间，Unix 毫秒，UTC
 */
public record SampleLockView(
        String experimentId,
        String participantId,
        String pairCode,
        String preDigest,
        String postDigest,
        int version,
        long lockedAt
) {
}