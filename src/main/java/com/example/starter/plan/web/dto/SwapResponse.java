package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 容量交换单响应：预览结果与激活证据共用。
 *
 * @param swapKey     交换单业务键
 * @param opDate      运营日期（Asia/Shanghai 日历日）
 * @param status      交换单状态：PREVIEW / ACTIVATED
 * @param createdAt   预览创建时刻，UTC
 * @param activatedAt 激活成功时刻，UTC；未激活为 null
 * @param items       交换项，按计划业务键稳定升序，段内按 (sectionId,start,end) 稳定排序
 * @param conflicts   按完整后态计算的冲突明细；预览时可能非空，激活成功必为空列表
 */
public record SwapResponse(String swapKey, LocalDate opDate, String status,
                           Instant createdAt, Instant activatedAt,
                           List<SwapItemView> items,
                           List<Map<String, Object>> conflicts) {
}
