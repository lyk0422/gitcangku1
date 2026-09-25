package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 紧急例外凭据：必须同时提供事件号与两名不同的已登记确认人。
 * 可随发布启动（单个或批量）与新任务拉取一起提交，冻结窗口内命中范围时据此放行。
 */
public record EmergencyGrant(
        @NotBlank @Size(max = 64) String eventNo,
        @NotBlank @Size(max = 64) String confirmer1,
        @NotBlank @Size(max = 64) String confirmer2) {
}
