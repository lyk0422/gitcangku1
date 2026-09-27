package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 观测记录响应：普通版本携带全部业务字段；删除墓碑版本只返回删除状态和版本，不带业务字段。
 * 维护字段为版本固化元数据：任何版本都返回 maintenanceAffected；窗口标识/版本仅在固化时存在（null 省略）。
 *
 * @param observationId             观测记录唯一标识
 * @param version                   版本号
 * @param deleted                   是否为删除墓碑
 * @param location                  观测地点（墓碑版本不返回）
 * @param reading                   观测读数（墓碑版本不返回）
 * @param note                      观测备注（墓碑版本不返回）
 * @param maintenanceAffected       该版本固化的维护受影响标记（人工裁决受影响观测胜出为 true）
 * @param maintenanceWindowId       该版本固化的维护窗口标识（未命中窗口时省略）
 * @param maintenanceWindowVersion  该版本固化的维护版本号（未命中窗口时省略）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObservationResponse(
        String observationId,
        int version,
        boolean deleted,
        String location,
        String reading,
        String note,
        boolean maintenanceAffected,
        String maintenanceWindowId,
        Integer maintenanceWindowVersion) {

    /**
     * 由快照构造响应；墓碑版本自动省略业务字段，维护标记仍按快照固化值返回。
     */
    public static ObservationResponse of(ObservationSnapshot snapshot) {
        if (snapshot.deleted()) {
            return new ObservationResponse(snapshot.observationId(), snapshot.version(), true, null, null, null,
                    snapshot.maintenanceAffected(), snapshot.maintenanceWindowId(),
                    snapshot.maintenanceWindowVersion());
        }
        return new ObservationResponse(snapshot.observationId(), snapshot.version(), false,
                snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.maintenanceAffected(), snapshot.maintenanceWindowId(),
                snapshot.maintenanceWindowVersion());
    }
}
