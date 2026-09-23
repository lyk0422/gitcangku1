package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 签发豁免包请求。permitKey 全局唯一；一个豁免包精确绑定一个 routeVersion；
 * 含 1~10 个区域项，regionKey 不可重复；签发后不可修改。
 *
 * @param permitKey    豁免包唯一标识
 * @param routeVersion 绑定的精确航线版本
 * @param items        区域项列表（1~10 个，regionKey 不可重复）
 * @param requestId    写操作全局唯一请求标识，用于幂等重放
 */
public record PermitIssueRequest(
        @NotBlank @Size(max = 64) String permitKey,
        @NotNull Integer routeVersion,
        @NotNull @Valid @Size(min = 1, max = 10) List<PermitItemRequest> items,
        @NotBlank @Size(max = 64) String requestId) {
}
