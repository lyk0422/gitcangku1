package com.example.starter.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 锁定请求中引用的高风险豁免：以豁免 ID 精确引用，服务端校验其适用依赖、版本与状态。
 */
public record ExceptionRef(
        @NotNull @Positive Long exceptionId) {
}
