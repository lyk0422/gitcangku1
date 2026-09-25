package com.example.starter.observation;

import java.time.LocalDateTime;

/**
 * 质量标记：对应 quality_flag 表的一行，附加于标记时的观测当前版本。
 *
 * @param observationId 观测记录唯一标识
 * @param flagKey       质量标记标识，同一观测内唯一
 * @param category      质量问题类别
 * @param description   质量问题说明
 * @param submittedRole 标记提交人角色
 * @param boundVersion  标记时观测的当前版本号
 * @param status        标记状态：PENDING_REVIEW / CONFIRMED / DISMISSED / STALE
 * @param createdAt     标记创建时间（服务器时区）
 */
public record QualityFlag(
        String observationId,
        String flagKey,
        QualityCategory category,
        String description,
        String submittedRole,
        int boundVersion,
        FlagStatus status,
        LocalDateTime createdAt) {
}
