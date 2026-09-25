package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 抢占记录视图：固化双方计划、涉及区段与各自等级。
 *
 * @param preemptKey        抢占幂等键
 * @param opDate            运营日期（Asia/Shanghai 日历日）
 * @param winnerScheduleKey 抢占方计划业务键
 * @param winnerLevel       抢占方计划等级
 * @param loserScheduleKey  被抢占计划业务键
 * @param loserLevel        被抢占计划等级
 * @param sections          涉及区段及当时等级
 * @param createdAt         抢占提交时刻，UTC 毫秒
 */
public record PreemptionView(String preemptKey, LocalDate opDate,
                             String winnerScheduleKey, int winnerLevel,
                             String loserScheduleKey, int loserLevel,
                             List<PreemptionSectionView> sections,
                             long createdAt) {
}
