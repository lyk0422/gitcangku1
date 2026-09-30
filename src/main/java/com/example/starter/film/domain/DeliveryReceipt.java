package com.example.starter.film.domain;

import java.time.Instant;

/**
 * 交付回执；交付成功即不可变，保存曾交付母版的片段快照 JSON。
 *
 * @param id           回执主键
 * @param filmId       所属影片 ID
 * @param receiptNo    回执编号，全局唯一；交付重试必须返回原回执，不生成第二张
 * @param masterNo     交付引用的不可变母版编号
 * @param requestId    幂等请求号；同一影片内唯一
 * @param snapshotJson 交付瞬间固定的母版片段快照（序号、版次、摘要），母版撤销后仍保留
 * @param createdAt    交付时间，UTC 时间线
 */
public record DeliveryReceipt(long id, long filmId, String receiptNo, String masterNo, String requestId,
                              String snapshotJson, Instant createdAt) {
}
