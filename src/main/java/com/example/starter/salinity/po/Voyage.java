package com.example.starter.salinity.po;

import java.time.Instant;

/**
 * 航次持久化对象。
 *
 * @param id          自增主键
 * @param voyageCode  航次编号，业务唯一，非空
 * @param status      航次状态，非空；采样中为 {@link VoyageStatus#OPEN}，封存后为 {@link VoyageStatus#SEALED}
 * @param sealedUtc   封存 UTC 时刻；未封存时为 {@code null}
 * @param createdUtc  创建 UTC 时刻，非空
 */
public record Voyage(
        Long id,
        String voyageCode,
        VoyageStatus status,
        Instant sealedUtc,
        Instant createdUtc
) {
}
