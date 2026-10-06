package com.example.starter.plan.model;

import java.math.BigDecimal;

/**
 * 发布/改签成功时冻结的制动包络判定快照；后续档案修改不改写历史。
 *
 * @param id          主键
 * @param planId      所属计划 id
 * @param occupancyId 占用 id
 * @param trainNo     冻结的列车编号
 * @param sectionId   冻结的区段 ID
 * @param speedV      冻结的占用速度 v，米每秒
 * @param reactionT   冻结的反应时间 t，秒
 * @param baseDecelA  冻结的基础减速度 a，米每平方秒
 * @param protectD    冻结的防护距离 d，米
 * @param lhsValue    精确判定左值 2*a*v*t + v*v
 * @param rhsValue    精确判定右值 2*a*d，通过条件为左值不大于右值
 * @param createdAt   快照冻结时刻，UTC 毫秒
 */
public record EnvelopeSnapshot(long id, long planId, long occupancyId, String trainNo,
                               String sectionId, BigDecimal speedV, BigDecimal reactionT,
                               BigDecimal baseDecelA, BigDecimal protectD, BigDecimal lhsValue,
                               BigDecimal rhsValue, long createdAt) {
}
