package com.example.starter.web.dto;

import com.example.starter.domain.DeicingSlot;
import java.time.Instant;

/** 时隙视图，时刻均为UTC。 */
public record SlotResponse(long slotId, long vehicleId, long flightId, Instant slotStart,
        Instant slotEnd, String status, String requestId) {

    public static SlotResponse of(DeicingSlot slot) {
        return new SlotResponse(slot.id(), slot.vehicleId(), slot.flightId(), slot.slotStart(),
                slot.slotEnd(), slot.status().name(), slot.requestId());
    }
}