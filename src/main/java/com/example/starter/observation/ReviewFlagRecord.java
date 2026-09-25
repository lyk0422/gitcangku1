package com.example.starter.observation;

import java.time.Instant;

/**
 * 待复审标记：对应 review_flag 表的一行。已人工裁决的观测再发生附页提交/撤销时生成，
 * 裁决结果本身冻结不改写。
 *
 * @param flagId        待复审标记自增标识
 * @param observationId 观测记录唯一标识
 * @param resolutionId  被标记的裁决记录标识（生成标记时最近一次裁决）
 * @param corrVersion   触发标记的附页版本号（撤销事件时为被撤销的附页版本）
 * @param event         触发事件：SUBMIT 提交附页 / REVOKE 撤销附页
 * @param status        标记状态：PENDING 待复审
 * @param createdAtUtc  标记生成时刻（UTC）
 */
public record ReviewFlagRecord(
        long flagId,
        String observationId,
        String resolutionId,
        int corrVersion,
        String event,
        String status,
        Instant createdAtUtc) {

    /**
     * 触发事件：提交附页。
     */
    public static final String EVENT_SUBMIT = "SUBMIT";

    /**
     * 触发事件：撤销附页。
     */
    public static final String EVENT_REVOKE = "REVOKE";

    /**
     * 标记状态：待复审。
     */
    public static final String STATUS_PENDING = "PENDING";
}
