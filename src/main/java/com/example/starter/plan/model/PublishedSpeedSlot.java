package com.example.starter.plan.model;

import java.math.BigDecimal;

/**
 * 已发布计划中一条带速度的占用（档案变更的后态校验候选）。
 *
 * @param scheduleKey 所属计划业务键
 * @param trainNo     列车编号
 * @param sectionId   区段 ID
 * @param speedMps    占用速度 v，米每秒，非空
 */
public record PublishedSpeedSlot(String scheduleKey, String trainNo, String sectionId,
                                 BigDecimal speedMps) {
}
