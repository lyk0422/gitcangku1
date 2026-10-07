package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 实际进入/驶出请求。requestId 为全局幂等键；operator 为非空操作者；
 * expectedVersion 对计划做乐观校验，并参与幂等同参判定。
 */
public record ActualActionRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull Integer expectedVersion) {
}
