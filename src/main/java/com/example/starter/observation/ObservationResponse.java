package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 观测记录响应：普通版本携带全部业务字段；删除墓碑版本只返回删除状态和版本，不带业务字段。
 * 位移字段组（dx/dy/w）仅在观测携带位移时出现，普通观测响应保持原契约。
 *
 * @param observationId 观测记录唯一标识
 * @param version       版本号
 * @param deleted       是否为删除墓碑
 * @param location      观测地点（墓碑版本不返回）
 * @param reading       观测读数（墓碑版本不返回）
 * @param note          观测备注（墓碑版本不返回）
 * @param dx            位移 X 分量（米，十进制字符串）；无位移或墓碑版本不返回
 * @param dy            位移 Y 分量（米，十进制字符串）；无位移或墓碑版本不返回
 * @param w             边长权重（米，正数十进制字符串）；无位移或墓碑版本不返回
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObservationResponse(
        String observationId,
        int version,
        boolean deleted,
        String location,
        String reading,
        String note,
        String dx,
        String dy,
        String w) {

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
                snapshot.dx(), snapshot.dy(), snapshot.w());
    }
}
