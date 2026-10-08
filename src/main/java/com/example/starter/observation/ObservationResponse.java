package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 观测记录响应：普通版本携带全部业务字段；删除墓碑版本只返回删除状态和版本，不带业务字段。
 * 普通观测（PLAIN）返回 location/reading/note；圆弧观测（ARC）返回 startAngle/arcLength。
 *
 * @param observationId 观测记录唯一标识
 * @param version       版本号
 * @param deleted       是否为删除墓碑
 * @param obsType       观测类型：PLAIN / ARC（墓碑版本不返回）
 * @param location      观测地点（仅 PLAIN 非墓碑版本返回）
 * @param reading       观测读数（仅 PLAIN 非墓碑版本返回）
 * @param note          观测备注（仅 PLAIN 非墓碑版本返回）
 * @param startAngle    圆弧起始角，千分之一度（仅 ARC 非墓碑版本返回）
 * @param arcLength     圆弧正向弧长，千分之一度（仅 ARC 非墓碑版本返回）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObservationResponse(
        String observationId,
        int version,
        boolean deleted,
        ObservationType obsType,
        String location,
        String reading,
        String note,
        Integer startAngle,
        Integer arcLength) {

    /**
     * 由快照构造响应；墓碑版本自动省略业务字段。
     */
    public static ObservationResponse of(ObservationSnapshot snapshot) {
        if (snapshot.deleted()) {
            return new ObservationResponse(snapshot.observationId(), snapshot.version(), true,
                    null, null, null, null, null, null);
        }
        if (snapshot.obsType() == ObservationType.ARC) {
            return new ObservationResponse(snapshot.observationId(), snapshot.version(), false,
                    ObservationType.ARC, null, null, null, snapshot.startAngle(), snapshot.arcLength());
        }
        return new ObservationResponse(snapshot.observationId(), snapshot.version(), false,
                ObservationType.PLAIN, snapshot.location(), snapshot.reading(), snapshot.note(), null, null);
    }
}
