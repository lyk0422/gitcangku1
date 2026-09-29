package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 封锁登记事务内对单条受影响已发布计划的处理指令。
 * 仅给 scheduleKey 表示撤销该计划；同时给 newScheduleKey 表示整单改签为该草稿计划。
 * expectedOldVersion / expectedNewVersion 仅改签时使用，分别乐观校验旧计划与新草稿版本。
 */
public record BlockClearanceRequest(
        @NotBlank String scheduleKey,
        String newScheduleKey,
        Integer expectedOldVersion,
        Integer expectedNewVersion) {

    /** 是否为改签指令（否则为撤销指令）。 */
    public boolean reschedule() {
        return newScheduleKey != null && !newScheduleKey.isBlank();
    }
}
