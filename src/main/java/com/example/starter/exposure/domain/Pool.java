package com.example.starter.exposure.domain;

/**
 * 缺额轮转选择池 PO。池成员与权重创建后固定，版本恒为 1。
 *
 * @param poolId       选择池编号，全局唯一
 * @param poolVersion  池版本号，当前恒为 1；选择请求可携带期望版本校验，不一致返回 409
 * @param createdBy    创建操作者
 * @param createdAtUtc 创建时刻（epoch 毫秒，UTC）
 */
public record Pool(
        String poolId,
        int poolVersion,
        String createdBy,
        long createdAtUtc
) {
}
