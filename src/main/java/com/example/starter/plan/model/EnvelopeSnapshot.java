package com.example.starter.plan.model;

import java.math.BigDecimal;

/**
 * 发布/改签成功时冻结的应急制动包络判定快照，追加后不可变；
 * 后续列车或区段档案修改不改写历史。
 *
 * @param planId                所属计划 id
 * @param occupancySeq          占用在计划内的序号
 * @param trainNo               列车编号（冻结值）
 * @param sectionId             区段 ID（冻结值）
 * @param speedMps              占用速度 v，米每秒（冻结值）
 * @param reactionTimeS         列车反应时间 t，秒（冻结值）
 * @param baseDeceleration      基础减速度 a，米每平方秒（冻结值）
 * @param protectionDistanceM   区段防护距离 d，米（冻结值）
 * @param trainProfileVersion   判定时列车档案版本（冻结值）
 * @param sectionProfileVersion 判定时区段档案版本（冻结值）
 * @param lhsScaled             精确判定依据左侧：2*a*v*t + v^2，未舍入精确值
 * @param rhsScaled             精确判定依据右侧：2*a*d，未舍入精确值；lhs &lt;= rhs 判定为不越限
 */
public record EnvelopeSnapshot(long planId, int occupancySeq, String trainNo, String sectionId,
                               BigDecimal speedMps, BigDecimal reactionTimeS,
                               BigDecimal baseDeceleration, BigDecimal protectionDistanceM,
                               int trainProfileVersion, int sectionProfileVersion,
                               BigDecimal lhsScaled, BigDecimal rhsScaled) {
}
