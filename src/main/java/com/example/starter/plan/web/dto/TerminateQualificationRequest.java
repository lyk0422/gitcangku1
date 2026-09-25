package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 提前终止乘务员资质请求：expectedVersion 乐观校验；
 * 同一事务内回查所有未来已发布计划并写入不可变风险记录，任一回查失败整次回滚。
 */
public record TerminateQualificationRequest(
        @NotBlank String requestKey,
        @NotNull Integer expectedVersion) {
}
