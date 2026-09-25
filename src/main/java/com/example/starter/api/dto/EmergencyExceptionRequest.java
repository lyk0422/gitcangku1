package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 风险航线转紧急例外请求。仅当固化的关闭窗口快照允许紧急例外时，
 * 附事件号可转为合格紧急例外并恢复已批准状态。
 *
 * @param routeId   航线标识
 * @param eventNo   紧急事件号（必填）
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record EmergencyExceptionRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotBlank @Size(max = 64) String eventNo,
        @NotBlank @Size(max = 64) String requestId) {
}
