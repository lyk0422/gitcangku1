package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 观测记录响应：普通版本携带全部业务字段（读数限定符与读数值同时给出）；
 * 删除墓碑版本只返回删除状态和版本，不带任何读数字段。
 *
 * @param observationId    观测记录唯一标识
 * @param version          版本号
 * @param deleted          是否为删除墓碑
 * @param location         观测地点（墓碑版本不返回）
 * @param reading          观测读数（非负十进制字符串原文，墓碑版本不返回）
 * @param readingQualifier 读数限定符 EXACT/LT/GT，与读数值为不可拆分的一组（墓碑版本不返回）
 * @param note             观测备注（墓碑版本不返回）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObservationResponse(
        String observationId,
        int version,
        boolean deleted,
        String location,
        String reading,
        String readingQualifier,
        String note) {

    /**
     * 由快照构造响应；墓碑版本自动省略业务字段（含读数限定符与读数值）。
     */
    public static ObservationResponse of(ObservationSnapshot snapshot) {
        if (snapshot.deleted()) {
            return new ObservationResponse(snapshot.observationId(), snapshot.version(), true,
                    null, null, null, null);
        }
        return new ObservationResponse(snapshot.observationId(), snapshot.version(), false,
                snapshot.location(), snapshot.reading(), snapshot.readingQualifier(), snapshot.note());
    }
}