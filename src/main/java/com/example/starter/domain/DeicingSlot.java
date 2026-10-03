package com.example.starter.domain;

import java.time.Instant;

/**
 * 除冰作业时隙，UTC左闭右开区间[slotStart, slotEnd)。
 * requestId/requestHash用于申请幂等：同参重放返回原记录，异参返回409。
 */
public record DeicingSlot(long id, long vehicleId, long flightId, Instant slotStart, Instant slotEnd,
        SlotStatus status, String requestId, String requestHash, Instant createdAt) {
}