package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 观测记录响应：普通版本携带全部业务字段；删除墓碑版本只返回删除状态和版本，不带业务字段。
 *
 * @param observationId 观测记录唯一标识
 * @param version       版本号
 * @param deleted       是否为删除墓碑
 * @param location      观测地点（墓碑版本不返回）
 * @param reading       当前规范读数：带量纲记录为精确规范值（长度 m/温度 C），旧无单位记录为原始值（墓碑版本不返回）
 * @param readingUnit   当前读数的原始上报单位（墓碑版本与旧无单位记录不返回）
 * @param dimension     量纲：LENGTH / TEMPERATURE（墓碑版本与旧无单位记录不返回）
 * @param note          观测备注（墓碑版本不返回）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObservationResponse(
        String observationId,
        int version,
        boolean deleted,
        String location,
        String reading,
        String readingUnit,
        String dimension,
        String note) {

    /**
     * 由快照构造响应；墓碑版本自动省略业务字段。
     */
    public static ObservationResponse of(ObservationSnapshot snapshot) {
        if (snapshot.deleted()) {
            return new ObservationResponse(snapshot.observationId(), snapshot.version(), true,
                    null, null, null, null, null);
        }
        return new ObservationResponse(snapshot.observationId(), snapshot.version(), false,
                snapshot.location(), snapshot.reading(), snapshot.readingUnit(), snapshot.dimension(),
                snapshot.note());
    }
}
