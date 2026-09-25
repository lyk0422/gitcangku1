package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 风险航班转为合格紧急例外请求。仅 RUNWAY_RISK 状态航班可执行；
 * 转换后航线类型变为 EMERGENCY、附事件号并回到 PENDING，须重新批量审查。
 *
 * @param flightId  航班唯一标识
 * @param eventNo   紧急事件号（必填）
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record FlightEmergencyConvertRequest(
        @NotBlank @Size(max = 64) String flightId,
        @NotBlank @Size(max = 64) String eventNo,
        @NotBlank @Size(max = 64) String requestId) {
}
