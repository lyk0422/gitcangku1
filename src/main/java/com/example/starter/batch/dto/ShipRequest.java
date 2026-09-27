package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 装运放行请求。放行前核对整批最终状态与完整隔离关系，一次事务提交并固化快照。
 */
public record ShipRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey
) {
}
