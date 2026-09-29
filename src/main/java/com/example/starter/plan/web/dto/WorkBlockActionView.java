package com.example.starter.plan.web.dto;

/**
 * 封锁处理结果视图：登记事务内对单条受影响计划执行的动作（历史不可变）。
 *
 * @param scheduleKey       被处理的原计划业务键；撤销/改签后原计划保留 CANCELLED 与原始区段时段
 * @param action            CANCELLED 撤销 / RESCHEDULED 整单改签
 * @param newScheduleKey    改签后继新计划业务键；撤销时为 null
 */
public record WorkBlockActionView(String scheduleKey, String action, String newScheduleKey) {
}
