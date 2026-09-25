package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 登记/替换计划编组请求。expectedVersion 对计划做乐观校验；
 * 车厢按编号去重并规范化升序；长度必须为正，站台必须存在；已取消计划不可改。
 */
public record RegisterConsistRequest(
        @NotBlank String requestKey,
        @NotNull Integer expectedVersion,
        @NotNull @Positive Integer trainLength,
        @NotBlank String platformCode,
        @NotBlank String operator,
        @NotNull @NotEmpty @Size(max = 100) List<@NotBlank String> cars) {
}
