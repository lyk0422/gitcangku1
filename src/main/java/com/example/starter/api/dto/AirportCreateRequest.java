package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建备降机场请求。容量为正整数属于业务语义校验：为空由参数校验拦截（400），
 * 非正在服务层判定并返回 422。
 *
 * @param airportId 备降机场唯一标识
 * @param capacity  同一时段窗口允许同时占用的预留数量，必须为正（非正返回 422）
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record AirportCreateRequest(
        @NotBlank @Size(max = 64) String airportId,
        @NotNull Integer capacity,
        @NotBlank @Size(max = 64) String requestId) {
}
