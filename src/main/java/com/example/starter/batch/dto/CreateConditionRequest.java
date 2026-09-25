package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 创建条件放行请求。仅对已通过全部必做检验但尚未批准（PENDING_RELEASE，
 * 或上一条条件放行已到期降级）的批次有效。
 *
 * @param commandKey 创建命令幂等键
 * @param conditionKey 条件放行业务键，全局唯一，只能被创建一次
 * @param expiresAt 条件有效期到期时刻（UTC），必须晚于当前时刻
 * @param conditions 1～5 条条件子项说明
 */
public record CreateConditionRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "conditionKey 不能为空") String conditionKey,
        @NotNull(message = "expiresAt 不能为空") Instant expiresAt,
        @NotNull(message = "conditions 不能为空")
        @Size(min = 1, max = 5, message = "conditions 必须包含 1～5 条条件说明")
        List<@Valid ConditionItemInput> conditions
) {
}
