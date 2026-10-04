package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量保障改签请求：对选定的一组风险后车整体寻找不冲突时间窗并平移。
 * 任一计划找不到可行窗口时整次 422，已发布时刻、风险标记与时隙占用全部回滚。
 */
public record GuaranteeRescheduleRequest(
        @NotBlank String requestKey,
        @NotNull @Size(min = 1, max = 30) List<@NotBlank String> scheduleKeys) {
}