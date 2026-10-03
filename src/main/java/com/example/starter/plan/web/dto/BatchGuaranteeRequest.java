package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量保障改签请求：对选定的一组风险后车整体改签，任一计划找不到
 * 不冲突且满足全部接续分钟数的时间窗时整次 422 回滚。items 1~30 条，按提交顺序裁决。
 */
public record BatchGuaranteeRequest(
        @NotBlank String requestKey,
        @NotNull @Size(min = 1, max = 30) List<@Valid Item> items) {

    /**
     * 单个待改签计划：expectedVersion 对当前版本做乐观校验。
     */
    public record Item(@NotBlank String scheduleKey, @NotNull Integer expectedVersion) {
    }
}