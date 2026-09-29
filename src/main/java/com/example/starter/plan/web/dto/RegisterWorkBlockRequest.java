package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * 区段维护封锁登记请求。窗口按 UTC 左闭右开比较，相邻端点不算冲突。
 * plans 必须列出登记时刻所有与窗口相交的已发布计划，并在同一事务内全部撤销或改签；
 * 遗漏任一相交计划则整单 422 回滚，封锁不生效。无相交计划时传空列表。
 */
public record RegisterWorkBlockRequest(
        @NotBlank String requestKey,
        @NotBlank String blockKey,
        @NotBlank String sectionId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @NotNull @Size(max = 30) List<@Valid WorkBlockPlanAction> plans) {
}
