package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 观测记录响应：普通版本携带全部业务字段与归并元数据；删除墓碑版本只返回删除状态和版本，不带业务字段。
 *
 * @param observationId 观测记录唯一标识（记录键）
 * @param version       版本号（generation）
 * @param deleted       是否为删除墓碑
 * @param location      观测地点（墓碑版本不返回）
 * @param reading       观测读数（墓碑版本不返回）
 * @param note          观测备注（墓碑版本不返回）
 * @param siteKey       站点键（簇分组维度；墓碑版本不返回，历史无分组观测为 null）
 * @param type          观测类型（簇分组维度；墓碑版本不返回，历史无分组观测为 null）
 * @param observedAt    观测发生时刻（UTC，ISO-8601；墓碑版本不返回，历史无分组观测为 null）
 * @param deviceId      采集设备标识（墓碑版本不返回，可能为 null）
 * @param mergeStatus   归并状态：ACTIVE 活跃可归并；MERGED 已归并只读
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObservationResponse(
        String observationId,
        int version,
        boolean deleted,
        String location,
        String reading,
        String note,
        String siteKey,
        String type,
        Instant observedAt,
        String deviceId,
        String mergeStatus) {

    /**
     * 由快照构造响应；墓碑版本自动省略业务字段与归并元数据。
     */
    public static ObservationResponse of(ObservationSnapshot snapshot) {
        if (snapshot.deleted()) {
            return new ObservationResponse(snapshot.observationId(), snapshot.version(), true,
                    null, null, null, null, null, null, null, null);
        }
        return new ObservationResponse(snapshot.observationId(), snapshot.version(), false,
                snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.siteKey(), snapshot.obsType(), snapshot.observedAt(),
                snapshot.deviceId(), snapshot.mergeStatus().name());
    }
}
