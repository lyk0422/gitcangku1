package com.example.starter.salinity.model;

import java.time.Instant;

/**
 * 航次聚合。
 *
 * @param code         航次业务编号
 * @param status       航次状态（OPEN/SEALED）
 * @param sealedAtUtc  封存完成时刻（UTC），未封存为 {@code null}
 * @param createdAtUtc 航次创建时刻（UTC，即首份样本入库时刻）
 */
public record Voyage(String code,
                     VoyageStatus status,
                     Instant sealedAtUtc,
                     Instant createdAtUtc) {
}
