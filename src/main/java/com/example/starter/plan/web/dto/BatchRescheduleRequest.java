package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 封锁生效后的批量改签请求：把多条仍占用区段的已发布计划整单原子改签。
 * requestKey 为幂等键；任一项校验或时隙裁决失败，全部计划状态与封锁状态保持原样（整体回滚）。
 */
public record BatchRescheduleRequest(
        @NotBlank String requestKey,
        @NotEmpty List<@Valid @NotNull BlockClearanceRequest> clearances) {
}
