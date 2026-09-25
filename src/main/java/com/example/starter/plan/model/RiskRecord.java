package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 不可变乘务资质风险记录。资质被提前终止时，对每个受影响的未来已发布计划写入一条；
 * 计划不会被自动取消，而是进入风险门禁状态等待两角色均替换为合格人员。
 *
 * @param id                主键
 * @param planId            受影响计划 id
 * @param crewId            涉及乘务员 id
 * @param role              涉及角色
 * @param qualificationCode 被提前终止的资质代码
 * @param reason            风险原因代码
 * @param createdAtUtc      记录创建时刻（UTC）
 */
public record RiskRecord(long id, long planId, String crewId, CrewRole role,
                         String qualificationCode, String reason, Instant createdAtUtc) {
}
