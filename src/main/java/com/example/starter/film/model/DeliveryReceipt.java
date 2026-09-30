package com.example.starter.film.model;

import java.time.LocalDateTime;

/**
 * 交付回执。保存曾交付的母版快照，审核撤销不删除既有回执；同一交付 requestId 只有一张回执。
 *
 * @param id              行ID
 * @param requestId       交付请求ID（幂等键），全局唯一
 * @param filmId          所属影片ID
 * @param masterId        引用的母版ID
 * @param masterNo        引用的母版编号（冗余快照，便于历史展示）
 * @param snapshotDigest  交付时母版全部片段摘要的定格值，{@code 序号:版次:摘要} 按序号拼接
 * @param createdAt       交付时间，Asia/Shanghai
 */
public record DeliveryReceipt(
        Long id,
        String requestId,
        Long filmId,
        Long masterId,
        String masterNo,
        String snapshotDigest,
        LocalDateTime createdAt
) {
}
