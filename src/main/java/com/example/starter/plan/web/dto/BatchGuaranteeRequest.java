package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量保障改签请求：对选定的一组风险后车统一寻找不冲突时间窗并整体后移。
 * requestKey 为幂等键；scheduleKeys 1～30 个且不得重复。
 */
public record BatchGuaranteeRequest(
        @NotBlank String requestKey,
        @NotNull @Size(min = 1, max = 30) List<@NotBlank String> scheduleKeys) {
}
