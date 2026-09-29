package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量原子改签请求：同一事务内按顺序完成全部单项改签，
 * 任一项状态、版本、关联或时隙（含区段封锁）校验失败则整体回滚，
 * 全部计划与封锁状态保持原样。requestKey 为幂等键。
 */
public record BatchRescheduleRequest(
        @NotBlank String requestKey,
        @NotNull @Size(min = 1, max = 30) List<@Valid BatchRescheduleItem> plans) {
}
