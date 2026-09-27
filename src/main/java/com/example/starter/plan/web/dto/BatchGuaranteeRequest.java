package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量保障改签请求。requestKey 为幂等键；任一条目找不到不冲突时间窗
 * 或不满足全部接续分钟数时整次 422 回滚。
 */
public record BatchGuaranteeRequest(
        @NotBlank String requestKey,
        @NotNull @Size(min = 1, max = 20) List<@Valid BatchGuaranteeItem> items) {
}
