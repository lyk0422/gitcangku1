package com.example.starter.blind.dto;

/**
 * 样本配对锁定结果视图（返回给执行锁定的协调员）；不含盲码、席位与处理代码。
 *
 * @param pairCode        随机无含义配对码，全局唯一
 * @param experimentId    实验编号
 * @param participantId   合成参与者编号
 * @param preDigest       锁定时冻结的 PRE 摘要
 * @param postDigest      锁定时冻结的 POST 摘要
 * @param preCollectedAt  PRE 采集时刻，Unix 毫秒，UTC
 * @param postCollectedAt POST 采集时刻，Unix 毫秒，UTC
 * @param lockedAt        锁定时间，Unix 毫秒，UTC
 */
public record SamplePairView(
        String pairCode,
        String experimentId,
        String participantId,
        String preDigest,
        String postDigest,
        long preCollectedAt,
        long postCollectedAt,
        long lockedAt
) {
}