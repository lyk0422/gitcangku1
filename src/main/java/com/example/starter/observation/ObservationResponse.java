package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 观测记录响应：普通版本携带全部业务字段；删除墓碑版本只返回删除状态和版本，不带业务字段。
 *
 * @param observationId 观测记录唯一标识
 * @param version       版本号
 * @param deleted       是否为删除墓碑
 * @param observationType 观测类型：FIELD / SECTION（墓碑版本不返回）
 * @param location      观测地点（FIELD；墓碑版本不返回）
 * @param reading       观测读数（FIELD；墓碑版本不返回）
 * @param note          观测备注（FIELD；墓碑版本不返回）
 * @param sectionNo     断面号（SECTION；墓碑版本不返回）
 * @param lateralMm     横向坐标，整数毫米（SECTION；墓碑版本不返回）
 * @param elevationMm   高程，整数毫米（SECTION；墓碑版本不返回）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObservationResponse(
        String observationId,
        int version,
        boolean deleted,
        String observationType,
        String location,
        String reading,
        String note,
        String sectionNo,
        Integer lateralMm,
        Integer elevationMm) {

    /**
     * 由快照构造响应；墓碑版本自动省略业务字段。
     */
    public static ObservationResponse of(ObservationSnapshot snapshot) {
        if (snapshot.deleted()) {
            return new ObservationResponse(snapshot.observationId(), snapshot.version(), true,
                    null, null, null, null, null, null, null);
        }
        return new ObservationResponse(snapshot.observationId(), snapshot.version(), false,
                snapshot.observationType().name(),
                snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.sectionNo(), snapshot.lateralMm(), snapshot.elevationMm());
    }
}
