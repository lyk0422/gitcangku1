package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 观测记录响应：普通版本携带全部业务字段；删除墓碑版本只返回删除状态和版本，不带业务字段。
 *
 * @param observationId 观测记录唯一标识
 * @param version       版本号
 * @param generation    合并代次：初始 1，墓碑显式恢复后加一，删除不变
 * @param deleted       是否为删除墓碑
 * @param location      观测地点（墓碑版本不返回）
 * @param reading       观测读数（墓碑版本不返回）
 * @param note          观测备注（墓碑版本不返回）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObservationResponse(
        String observationId,
        int version,
        int generation,
        boolean deleted,
        String location,
        String reading,
        String note) {

    /**
     * 由快照构造响应；墓碑版本自动省略业务字段。
     */
    public static ObservationResponse of(ObservationSnapshot snapshot) {
        if (snapshot.deleted()) {
            return new ObservationResponse(snapshot.observationId(), snapshot.version(),
                    snapshot.generation(), true, null, null, null);
        }
        return new ObservationResponse(snapshot.observationId(), snapshot.version(),
                snapshot.generation(), false,
                snapshot.location(), snapshot.reading(), snapshot.note());
    }
}
