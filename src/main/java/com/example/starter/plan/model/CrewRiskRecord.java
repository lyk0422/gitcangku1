package com.example.starter.plan.model;

/**
 * 乘务资质风险记录：资质提前终止回查命中已发布计划时写入，追加后不可变。
 *
 * @param id          主键
 * @param planId      受影响计划 id
 * @param scheduleKey 受影响计划业务键
 * @param role        受影响乘务角色
 * @param crewId      受影响乘务员标识
 * @param qualCode    被提前终止的资质代码
 * @param reason      风险原因（QUAL_TERMINATED 资质提前终止）
 * @param recordedAt  记录时刻，UTC 毫秒
 */
public record CrewRiskRecord(long id, long planId, String scheduleKey, CrewRole role,
                             String crewId, String qualCode, String reason, long recordedAt) {
}
