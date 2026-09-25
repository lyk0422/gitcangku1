package com.example.starter.exposure.web;

import java.util.List;

/**
 * 批量预占视图：整批全成或全败（事务回滚，不留半成品）。
 *
 * @param campaignId   公告编号
 * @param version      裁决时公告活动版本（进入 requestKey 指纹）
 * @param category     裁决时公告活动类别
 * @param createdAtUtc 批量创建时刻，epoch 毫秒，UTC
 * @param reservations 每个访客一张预占单（顺序与去重后的访客集合一致）
 */
public record BatchApplyResponse(
        String campaignId,
        int version,
        String category,
        long createdAtUtc,
        List<ReservationResponse> reservations
) {
}
