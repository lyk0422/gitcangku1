package com.example.starter.domain;

import java.time.Instant;

/**
 * 可复用标签及其当前持有站点。
 * holderStationCode 同一时刻只有一个值，交接确认后才变更；
 * registeredAt 为登记时刻（UTC）。
 */
public record TagRow(
        String tagCode,
        String holderStationCode,
        TagStatus status,
        Instant registeredAt) {
}