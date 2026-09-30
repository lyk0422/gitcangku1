package com.example.starter.film.api;

import java.time.Instant;
import java.util.List;

/**
 * 交付回执结果。
 *
 * @param receiptNo  回执编号
 * @param masterNo   交付引用的母版编号
 * @param requestId  幂等请求号
 * @param segments   曾交付母版的片段快照，按序号升序
 * @param createdAt  交付时间，UTC
 * @param replayed   true 表示交付重试返回原回执，未生成第二张回执
 */
public record DeliveryResponse(String receiptNo, String masterNo, String requestId,
                               List<MasterSegmentView> segments, Instant createdAt, boolean replayed) {
}
