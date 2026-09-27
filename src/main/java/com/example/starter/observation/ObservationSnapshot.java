package com.example.starter.observation;

/**
 * 观测记录快照：对应 observation_current 当前状态或 observation_version 历史版本的一行。
 *
 * @param observationId       观测记录唯一标识
 * @param version             版本号，从 1 开始
 * @param location            观测地点（可编辑字段）；删除墓碑版本为 null
 * @param reading             观测读数，十进制字符串，最多三位小数，比较按数值；删除墓碑版本为 null
 * @param note                观测备注（可编辑字段）；删除墓碑版本为 null
 * @param deleted             是否为删除墓碑：true 时业务字段无意义
 * @param maintenanceAffected 该版本固化的维护受影响标记：true 表示胜出来自命中维护窗口的观测；历史快照永不改写
 * @param maintenanceWindowId 该版本固化的维护窗口标识；无窗口命中为 null（null 语义稳定，不表示"当前无窗口"）
 * @param maintenanceWindowVersion 该版本固化的维护版本号；无窗口命中为 null
 */
public record ObservationSnapshot(
        String observationId,
        int version,
        String location,
        String reading,
        String note,
        boolean deleted,
        boolean maintenanceAffected,
        String maintenanceWindowId,
        Integer maintenanceWindowVersion) {

    /**
     * 常规创建/合并/删除路径使用的构造：不携带维护窗口信息（受影响标记 false，窗口字段为 null）。
     */
    public ObservationSnapshot(String observationId, int version, String location, String reading,
                               String note, boolean deleted) {
        this(observationId, version, location, reading, note, deleted, false, null, null);
    }
}
