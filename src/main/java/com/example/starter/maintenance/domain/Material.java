package com.example.starter.maintenance.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 材料批次。撤销只置状态，批次标识与历史引用永不删除。
 *
 * @param materialId    材料批次号，全局唯一
 * @param materialName  材料名称
 * @param unit          计量单位，登记后不可变
 * @param active        是否可用；false 表示已撤销（REVOKED）
 * @param registeredAt  登记时刻（UTC）
 * @param revokedAt     撤销时刻（UTC）；未撤销为 null
 * @param revokeReason  撤销原因；未撤销为 null
 */
public record Material(
        String materialId,
        String materialName,
        String unit,
        boolean active,
        Instant registeredAt,
        Instant revokedAt,
        String revokeReason) {

    /** 证据摘要中记录的用量快照。 */
    public record Summary(String materialId, BigDecimal quantity, String unit, int packageVersion) {
    }
}
