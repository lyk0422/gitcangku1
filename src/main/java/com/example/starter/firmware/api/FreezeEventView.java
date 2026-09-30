package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.FreezeEvent;

/**
 * 版本冻结审计流水视图，只增不改。
 */
public record FreezeEventView(long eventId, long freezeId, String version, String eventType,
                             String operator, String reason, String eventAtUtc) {

    public static FreezeEventView of(FreezeEvent event) {
        return new FreezeEventView(event.id(), event.freezeId(), event.version(), event.eventType().name(),
                event.operator(), event.reason(), event.eventAtUtc());
    }
}
