package com.example.starter.firmware.api;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 紧急例外：事件号加两名不同已登记确认人。三者缺一不可，否则视为例外不全。
 */
public record EmergencyException(
        @Size(max = 64) String incidentId,
        List<@Size(max = 64) String> approvers) {
}
