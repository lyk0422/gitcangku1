package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 原子改签请求：指定一个已发布旧计划与同一运营日的新草稿，携带两者的期望版本。
 *
 * @param requestKey          写操作幂等键
 * @param oldScheduleKey      已发布旧计划业务键（改签后取消，原始占用保留）
 * @param expectedOldVersion  旧计划期望版本，与当前版本不一致返回 409
 * @param newScheduleKey      新草稿计划业务键（改签后发布），必须与旧计划不同且同运营日
 * @param expectedNewVersion  新草稿期望版本，与当前版本不一致返回 409
 */
public record RescheduleRequest(
        @NotBlank String requestKey,
        @NotBlank String oldScheduleKey,
        @NotNull @Positive Integer expectedOldVersion,
        @NotBlank String newScheduleKey,
        @NotNull @Positive Integer expectedNewVersion) {
}
