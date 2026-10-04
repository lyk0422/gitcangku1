package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量保障改签请求：对选定的风险后车整体延后指定分钟数。
 * 任一项找不到不冲突时间窗（时隙冲突或接续分钟数不满足）则整次 422 回滚。
 */
public record GuaranteeRescheduleRequest(
        @NotBlank String requestKey,
        @NotNull @Size(min = 1, max = 20) List<@Valid Item> items) {

    /**
     * 批量项：目标后车计划与整体延后分钟数（1~1440，越界返回 422）。
     */
    public record Item(@NotBlank String scheduleKey, @NotNull Integer shiftMinutes) {
    }
}