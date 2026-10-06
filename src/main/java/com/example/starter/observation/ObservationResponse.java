package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 观测记录响应：普通版本携带全部业务字段；删除墓碑版本只返回删除状态和版本，不带业务字段。
 * 带量纲记录额外返回量纲、原始单位与当前精确规范读数（规范读数不舍入，展示舍入由调用方负责）。
 *
 * @param observationId     观测记录唯一标识
 * @param version           版本号
 * @param deleted           是否为删除墓碑
 * @param location          观测地点（墓碑版本不返回）
 * @param reading           观测读数原文（提交时原始单位下的值；墓碑版本不返回）
 * @param note              观测备注（墓碑版本不返回）
 * @param dimension         读数量纲：LENGTH / TEMPERATURE；旧无单位读数不返回
 * @param readingUnit       读数原始单位：m / cm / C / K；无单位读数不返回
 * @param normalizedReading 当前精确规范读数（长度 m、温度 C）；无量纲记录不返回
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObservationResponse(
        String observationId,
        int version,
        boolean deleted,
        String location,
        String reading,
        String note,
        String dimension,
        String readingUnit,
        String normalizedReading) {

    /**
     * 由快照构造响应；墓碑版本自动省略业务字段。
     */
    public static ObservationResponse of(ObservationSnapshot snapshot) {
        if (snapshot.deleted()) {
            return new ObservationResponse(snapshot.observationId(), snapshot.version(), true,
                    null, null, null, null, null, null);
        }
        return new ObservationResponse(snapshot.observationId(), snapshot.version(), false,
                snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.dimension(), snapshot.readingUnit(), snapshot.readingNormalized());
    }
}
