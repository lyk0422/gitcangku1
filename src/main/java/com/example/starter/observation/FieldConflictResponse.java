package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 字段冲突响应：登记时的三方/墓碑信息及裁决后的逐字段来源与最终值。
 * 未决或不适用的字段省略（NON_NULL）；证据查询按观测标识、字段名稳定排序返回。
 *
 * @param observationId 冲突所属观测记录唯一标识
 * @param field         冲突字段名：location / reading / note
 * @param type          冲突类型：FIELD 三方字段冲突 / RESTORE 墓碑待恢复
 * @param status        状态：OPEN / RESOLVED
 * @param baseVersion   基线版本号（FIELD 冲突；RESTORE 选择 BASE 来源时也回填恢复依据版本）
 * @param baseValue     基线字段值；墓碑登记时省略
 * @param localValue    服务端当前字段值；墓碑登记时省略（墓碑不能直接提供候选值）
 * @param remoteValue   离线候选字段值；墓碑登记时省略
 * @param chosenSource  裁决来源：LOCAL / REMOTE / BASE / EXPLICIT；未决省略
 * @param chosenValue   裁决最终字段值；未决省略
 * @param restoreBasis  墓碑恢复依据；非墓碑恢复省略
 * @param resolvedAtUtc 冲突关闭时刻（UTC）；未决省略
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldConflictResponse(
        String observationId,
        String field,
        String type,
        String status,
        Integer baseVersion,
        String baseValue,
        String localValue,
        String remoteValue,
        String chosenSource,
        String chosenValue,
        String restoreBasis,
        Instant resolvedAtUtc) {

    public static FieldConflictResponse of(FieldConflictRecord record) {
        return new FieldConflictResponse(
                record.observationId(),
                record.fieldName(),
                record.conflictType(),
                record.status(),
                record.baseVersion(),
                record.baseValue(),
                record.localValue(),
                record.remoteValue(),
                record.chosenSource(),
                record.chosenValue(),
                record.restoreBasis(),
                record.resolvedAtUtc());
    }
}
