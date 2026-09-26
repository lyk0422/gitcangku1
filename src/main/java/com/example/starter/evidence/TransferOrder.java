package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 移交令版本，对应 transfer_order 表。有效期按 UTC 左闭右开解释：[validFrom, validTo)。
 *
 * @param orderVersion 移交令版本，全局唯一
 * @param validFrom    有效期起点（UTC，左闭，含该时刻）
 * @param validTo      有效期终点（UTC，右开，不含该时刻）
 * @param createdAt    登记时间（UTC）
 */
public record TransferOrder(
        String orderVersion,
        LocalDateTime validFrom,
        LocalDateTime validTo,
        LocalDateTime createdAt) {
}
