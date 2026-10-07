package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 实际进入 / 退出确认请求。requestId 为全局幂等键；operator 为操作者，非空；
 * expectedVersion 对计划当前版本做乐观校验。确认时刻由服务端时钟产生，请求不含时刻字段。
 */
public record ActualActionRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull Integer expectedVersion) {
}
