package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 豁免包签发请求。为一个精确 routeVersion 配置 1~10 个不重复区域项。
 *
 * @param permitKey    豁免包唯一标识（签发后不可再用）
 * @param routeId      绑定的航线标识
 * @param routeVersion 绑定的精确航线版本
 * @param items        区域项列表（1~10 项，regionKey 不可重复）
 * @param requestId    写操作全局唯一请求标识，用于幂等重放
 */
public record PermitIssueRequest(
        @NotBlank @Size(max = 64) String permitKey,
        @NotBlank @Size(max = 64) String routeId,
        @NotNull @Min(1) Integer routeVersion,
        @NotEmpty @Valid @Size(min = 1, max = 10) List<PermitItemRequest> items,
        @NotBlank @Size(max = 64) String requestId) {
}
