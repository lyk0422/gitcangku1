package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.FreezeOrder;

import java.util.List;

/**
 * 冻结令视图。
 */
public record FreezeView(long freezeId, int version, String status, String startUtc, String endUtc,
                         List<String> models, List<Long> releaseIds, Integer enforcedVersion,
                         String revokedAtUtc) {

    public static FreezeView of(FreezeOrder order) {
        return new FreezeView(order.id(), order.version(), order.status().name(), order.startUtc(),
                order.endUtc(), order.models(), order.releaseIds(), order.enforcedVersion(),
                order.revokedAtUtc());
    }
}
