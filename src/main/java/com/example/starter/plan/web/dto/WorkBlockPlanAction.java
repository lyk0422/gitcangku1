package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 封锁登记时对单个受影响已发布计划的处理指令。
 * action 为 CANCEL 时撤销该计划；为 RESCHEDULE 时整单改签为 newScheduleKey 指向的同运营日草稿。
 *
 * @param scheduleKey    受影响的已发布计划业务键
 * @param action         处理动作：CANCEL / RESCHEDULE
 * @param newScheduleKey 改签后继草稿业务键；action=RESCHEDULE 时必填，CANCEL 时必须为空
 */
public record WorkBlockPlanAction(
        @NotBlank String scheduleKey,
        @NotBlank String action,
        String newScheduleKey) {
}
