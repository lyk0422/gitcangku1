package com.example.starter.consent.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 批量续签请求：先校验每份旧委托版本，任一冲突整批不生效。
 *
 * @param requestId 幂等请求标识
 * @param items     续签项列表，按委托键排序后规范化
 */
public record DelegateRenewRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotEmpty List<@Valid DelegateRenewItem> items) {
}
