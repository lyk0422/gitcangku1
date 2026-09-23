package com.example.starter.observation;

import java.time.Instant;

/**
 * 观测记录快照：对应 observation_current 当前状态或 observation_version 历史版本的一行。
 *
 * @param observationId 观测记录唯一标识（记录键）
 * @param version       版本号，从 1 开始；在簇归并预览中作为冻结的 generation
 * @param location      观测地点（可编辑业务字段）；删除墓碑版本为 null
 * @param reading       观测读数，十进制字符串，最多三位小数，比较按数值；删除墓碑版本为 null
 * @param note          观测备注（可编辑业务字段）；删除墓碑版本为 null
 * @param deleted       是否为删除墓碑：true 时业务字段无意义
 * @param siteKey       站点键（簇分组维度）；不参与归并的历史观测为 null
 * @param obsType       观测类型（簇分组维度）；不参与归并的历史观测为 null
 * @param observedAt    观测发生时刻（UTC，簇时间范围重算依据）；不参与归并的历史观测为 null
 * @param deviceId      采集设备标识（归并证据冻结项，非按字段选源的业务字段）；可为 null
 * @param mergeStatus   归并状态：ACTIVE 可归并；MERGED 已归并、只读且拒绝更新/恢复
 */
public record ObservationSnapshot(
        String observationId,
        int version,
        String location,
        String reading,
        String note,
        boolean deleted,
        String siteKey,
        String obsType,
        Instant observedAt,
        String deviceId,
        MergeStatus mergeStatus) {

    /**
     * 兼容既有离线合并流程的便捷构造：不携带簇归并元数据，状态默认 ACTIVE。
     */
    public ObservationSnapshot(String observationId, int version, String location, String reading,
                               String note, boolean deleted) {
        this(observationId, version, location, reading, note, deleted, null, null, null, null, MergeStatus.ACTIVE);
    }
}
