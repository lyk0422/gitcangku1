package com.example.starter.plan.model;

import java.time.LocalDate;

/**
 * 容量交换单主记录。
 *
 * @param id          主键
 * @param swapKey     交换单业务键，跨请求全局唯一
 * @param opDate      统一运营日（Asia/Shanghai 日历日）
 * @param status      交换单状态
 * @param itemCount   参与计划数量
 * @param requestHash 创建请求规范化后的 SHA-256
 * @param createdAt   创建时刻，UTC 毫秒
 * @param activatedAt 激活时刻，UTC 毫秒；未激活为 null
 */
public record CapacitySwap(long id, String swapKey, LocalDate opDate, SwapStatus status,
                           int itemCount, String requestHash, long createdAt, Long activatedAt) {
}
