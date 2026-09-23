package com.example.starter.plan.model;

import java.time.LocalDate;

/**
 * 容量交换单主记录。
 *
 * @param id               主键
 * @param swapKey          交换单业务键，跨请求全局唯一
 * @param opDate           运营日期（Asia/Shanghai 日历日）
 * @param status           交换单状态
 * @param requestHash      预览请求参数规范化（交换项与占用列表换序无关）后的 SHA-256
 * @param previewConflicts 预览时按完整后态计算的冲突明细 JSON（创建时固化）；无冲突为 "[]"
 * @param createdAt        预览创建时刻，UTC 毫秒
 * @param activatedAt      激活成功时刻，UTC 毫秒；未激活为 null
 */
public record CapacitySwap(long id, String swapKey, LocalDate opDate, CapacitySwapStatus status,
                           String requestHash, String previewConflicts, long createdAt,
                           Long activatedAt) {
}
