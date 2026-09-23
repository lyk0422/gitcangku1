package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 失效影响明细：激活时对每条已放行结果冻结到失效根的最短血缘路径。只增不改，可按 impactVersion 重现。
 *
 * @param id             影响明细 ID（自增）
 * @param impactVersion  影响版本号，同一失效单激活共享
 * @param measurementId  受影响测量记录 ID
 * @param measurementKey 受影响测量键
 * @param standardId     结果绑定的标准器版本业务键
 * @param path           冻结的到失效根最短血缘路径（standardId 以 &gt; 连接，等长取字典序）
 * @param previousStatus 冻结前状态：RELEASED
 * @param createdAt      冻结时间（UTC）
 */
public record ImpactItem(
        long id,
        String impactVersion,
        long measurementId,
        String measurementKey,
        String standardId,
        String path,
        String previousStatus,
        Instant createdAt) {
}
