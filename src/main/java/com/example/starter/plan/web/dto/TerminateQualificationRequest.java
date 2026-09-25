package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 提前终止资质请求。expectedVersion 做乐观校验；终止不可逆，
 * 提交时在全局发布锁内回查所有未来已发布计划并写入不可变风险记录。
 */
public record TerminateQualificationRequest(
        @NotBlank String requestKey,
        @NotNull Integer expectedVersion,
        @NotBlank String operator) {
}
