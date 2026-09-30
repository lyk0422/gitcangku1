package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 封榜快照中固化的申诉与裁决依据（只读历史记录）。
 *
 * @param protestId      申诉ID
 * @param bib            申诉选手参赛号
 * @param status         封榜时申诉终态：ACCEPTED / REJECTED
 * @param depositAmount  保证金快照金额（人民币元，正整数）
 * @param reason         申诉理由
 * @param decisionReason 裁决理由；未填写时为 null
 * @param createdAt      申诉创建时间，Unix毫秒时间戳
 * @param decidedAt      裁决时间，Unix毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SnapshotProtestResponse(
        String protestId,
        String bib,
        String status,
        int depositAmount,
        String reason,
        String decisionReason,
        long createdAt,
        long decidedAt
) {
}
