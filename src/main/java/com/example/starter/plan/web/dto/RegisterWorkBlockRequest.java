package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 登记区段维护封锁请求。requestKey 为幂等键。
 * 窗口 [startUtc, endUtc) 按 UTC 左闭右开比较，相邻端点不冲突，且必须完全落在 opDate 运营日内。
 *
 * @param clearances 与封锁窗口相交的全部已发布计划的处理指令，整单原子；
 *                   无相交计划时传空列表；撤销项给 scheduleKey，改签项另给 newScheduleKey 与期望版本
 */
public record RegisterWorkBlockRequest(
        @NotBlank String requestKey,
        @NotBlank String blockKey,
        @NotNull LocalDate opDate,
        @NotBlank String sectionId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @NotNull List<@Valid BlockClearanceRequest> clearances) {
}
