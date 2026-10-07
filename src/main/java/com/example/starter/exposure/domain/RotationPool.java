package com.example.starter.exposure.domain;

/**
 * 缺额轮转池 PO。成员与权重创建后固定；池缺额不按 UTC 日区分，跨日不清零。
 *
 * @param poolId               轮转池编号，全局唯一
 * @param version              池配置版本，当前恒为 1；选择请求须携带相等的期望版本
 * @param pendingReservationId 待决选择产生的预占单编号；RESERVED 期间阻塞下一轮选择，
 *                             无待决选择时为 null
 * @param createdAtUtc         创建时刻（epoch 毫秒，UTC）
 */
public record RotationPool(
        String poolId,
        int version,
        String pendingReservationId,
        long createdAtUtc
) {
}
