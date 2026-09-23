package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 观测记录响应：普通版本携带全部业务字段；删除墓碑版本只返回删除状态和版本，不带业务字段。
 *
 * @param observationId   观测记录唯一标识
 * @param version         代次（generation）
 * @param deleted         是否为删除墓碑
 * @param location        观测地点（墓碑版本不返回）
 * @param reading         观测读数（墓碑版本不返回）
 * @param note            观测备注（墓碑版本不返回）
 * @param siteKey         站点标识（墓碑版本仍返回）
 * @param observationType 观测类型（墓碑版本仍返回）
 * @param observedAt      观测发生时刻（UTC，墓碑版本仍返回）
 * @param deviceId        采集设备标识，可为空
 * @param status          归并状态：ACTIVE / MERGED
 * @param origin          记录来源：RAW / CANONICAL
 * @param mergedInto      归并目标主记录键；未归并不返回
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
        String observationType,
        Instant observedAt,
        String deviceId,
        String status,
        String origin,
        String mergedInto) {

    /**
     * 由快照构造响应；墓碑版本自动省略业务字段，但保留站点/类型/时间等匹配维度。
     */
    public static ObservationResponse of(ObservationSnapshot snapshot) {
        if (snapshot.deleted()) {
            return new ObservationResponse(snapshot.observationId(), snapshot.version(), true,
                    null, null, null,
                    snapshot.siteKey(), snapshot.observationType(), snapshot.observedAt(), snapshot.deviceId(),
                    snapshot.status().name(), snapshot.origin().name(), snapshot.mergedInto());
        }
        return new ObservationResponse(snapshot.observationId(), snapshot.version(), false,
                snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.siteKey(), snapshot.observationType(), snapshot.observedAt(), snapshot.deviceId(),
                snapshot.status().name(), snapshot.origin().name(), snapshot.mergedInto());
    }
}
