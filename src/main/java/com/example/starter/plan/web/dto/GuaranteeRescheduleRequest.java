package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量保障改签请求：对选定的一组 CONNECTION_AT_RISK 后车计划，
 * 在同一事务内整体延后至同时满足线路时隙与全部接续分钟数的时刻；
 * 任一计划找不到不冲突时间窗则整次 422 回滚。requestKey 为幂等键。
 */
public record GuaranteeRescheduleRequest(
        @NotBlank String requestKey,
        @NotNull @Size(min = 1, max = 30) List<@NotBlank String> scheduleKeys) {
}
