package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 激活封锁切换请求。requestId 为激活幂等键；mappings 必须与提交的映射集合一致
 * （映射换序视为同参），激活事务内重新计算影响集合并重新校验。
 */
public record DisruptionActivateRequest(
        @NotBlank String requestId,
        @NotNull @NotEmpty List<@Valid DisruptionMappingItem> mappings) {
}
