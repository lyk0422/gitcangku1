package com.example.starter.observation;

import java.time.LocalDateTime;

/**
 * 质量标记复核不可变记录：对应 quality_flag_review 表一行，
 * 复核成功时追加，固化标记版本与复核版本一致性、结论、理由以及置信度变化。
 *
 * @param id               自增主键
 * @param flagKey          被复核的质量标记标识
 * @param observationId    所属观测记录唯一标识
 * @param flagVersion      标记绑定版本（复核时与当前版本一致）
 * @param reviewVersion    复核时观测当前版本（等于 flagVersion）
 * @param category         质量问题类别（固化自标记）
 * @param conclusion       复核结论：CONFIRMED / DISMISSED
 * @param reason           复核理由
 * @param reviewedBy       复核角色（与标记提交角色不同）
 * @param confidenceAfter  复核生效后该版本的置信度
 * @param confidenceDelta  本次复核实际扣减幅度（非正数：-20 或 0）
 * @param createdAt        复核记录写入时间（服务器时区）
 */
public record QualityFlagReview(
        Long id,
        String flagKey,
        String observationId,
        int flagVersion,
        int reviewVersion,
        QualityCategory category,
        ReviewConclusion conclusion,
        String reason,
        String reviewedBy,
        int confidenceAfter,
        int confidenceDelta,
        LocalDateTime createdAt) {
}
