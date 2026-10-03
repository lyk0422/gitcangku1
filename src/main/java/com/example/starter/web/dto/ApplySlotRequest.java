package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** 除冰时隙申请请求，时刻为UTC（ISO-8601），时隙左闭右开。 */
public record ApplySlotRequest(
        @NotBlank(message = "requestId不能为空") String requestId,
        @NotNull(message = "flightId不能为空") Long flightId,
        @NotNull(message = "vehicleId不能为空") Long vehicleId,
        @NotNull(message = "slotStart不能为空") Instant slotStart,
        @NotNull(message = "slotEnd不能为空") Instant slotEnd) {
}