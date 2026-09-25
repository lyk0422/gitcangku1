package com.example.starter.plan.web.dto;

/**
 * 乘务资质风险记录视图（不可变）。
 *
 * @param scheduleKey 受影响计划业务键
 * @param role        受影响乘务角色
 * @param crewId      受影响乘务员标识
 * @param qualCode    被提前终止的资质代码
 * @param reason      风险原因
 * @param recordedAt  记录时刻，UTC 毫秒
 */
public record CrewRiskRecordView(String scheduleKey, String role, String crewId,
                                 String qualCode, String reason, long recordedAt) {
}
