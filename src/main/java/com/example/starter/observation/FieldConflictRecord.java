package com.example.starter.observation;

import java.time.Instant;

/**
 * 字段冲突登记记录：对应 field_conflict 表的一行。
 *
 * <p>FIELD 为存活成员按三方规则重算出的字段冲突；RESTORE 为墓碑成员登记的待恢复字段，
 * 其 base/local/remote 值均为 null（墓碑不能直接提供候选值）。
 *
 * @param id                  自增主键；登记阶段尚未生成时为 null
 * @param bundleKey           所属关联簇唯一标识
 * @param observationId       冲突所属观测记录唯一标识
 * @param fieldName           冲突字段名：location / reading / note
 * @param conflictType       冲突类型：FIELD / RESTORE
 * @param baseVersion         登记时的基线版本号；墓碑登记为 null
 * @param baseValue           基线字段值快照；墓碑登记为 null
 * @param localValue          服务端当前字段值快照；墓碑登记为 null
 * @param remoteValue         离线候选字段值快照；墓碑登记为 null
 * @param status              状态：OPEN / RESOLVED
 * @param chosenSource        裁决选择的来源；未决为 null
 * @param chosenValue         裁决最终字段值；未决为 null
 * @param restoreBasis        墓碑恢复依据；未恢复为 null
 * @param arbitrationRequestId 关闭该冲突的裁决请求标识；未决为 null
 * @param resolvedAtUtc       冲突关闭时刻（UTC）；未决为 null
 */
public record FieldConflictRecord(
        Long id,
        String bundleKey,
        String observationId,
        String fieldName,
        String conflictType,
        Integer baseVersion,
        String baseValue,
        String localValue,
        String remoteValue,
        String status,
        String chosenSource,
        String chosenValue,
        String restoreBasis,
        String arbitrationRequestId,
        Instant resolvedAtUtc) {

    public static final String FIELD = "FIELD";
    public static final String RESTORE = "RESTORE";
    public static final String OPEN = "OPEN";
    public static final String RESOLVED = "RESOLVED";

    public boolean open() {
        return OPEN.equals(status);
    }
}
