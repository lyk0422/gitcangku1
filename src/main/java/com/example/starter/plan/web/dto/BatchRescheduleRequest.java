package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量原子改签请求：先校验全部计划的完整最终状态（时隙、编组隔离、区段通行），
 * 再在一个事务中提交；任一计划非法则整次不改变时隙或编组。
 * requestKey 为幂等键，operator 为操作者标识。
 */
public record BatchRescheduleRequest(
        @NotBlank String requestKey,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(min = 1, max = 20) List<@Valid BatchRescheduleItem> items) {
}
