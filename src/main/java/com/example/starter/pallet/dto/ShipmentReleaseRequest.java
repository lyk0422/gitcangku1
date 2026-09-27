package com.example.starter.pallet.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 装运放行请求。操作人通过 X-Actor-Id 请求头传入。
 */
public record ShipmentReleaseRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey
) {
}
