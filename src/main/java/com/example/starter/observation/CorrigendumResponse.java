package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 更正附录响应：返回不可变附录要点及应用该附录后的字段有效值。
 *
 * @param corrigendumId      全局唯一附录标识
 * @param observationId      观测记录唯一标识
 * @param requestId          生成该附录的请求标识
 * @param corrigendumVersion 附录版本号，从 1 开始
 * @param previousVersion    前一附录版本号；首份附录为 0
 * @param snapshotVersion    引用的原始快照版本号
 * @param changes            本次附录的字段差异（旧值 → 新值）
 * @param operator           提交附录的观测记录员标识
 * @param submittedAtUtc     附录提交完成时刻（UTC，ISO-8601）
 * @param location           应用本附录后地点的当前有效值
 * @param reading            应用本附录后读数的当前有效值（十进制原文）
 * @param note               应用本附录后备注的当前有效值
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CorrigendumResponse(
        String corrigendumId,
        String observationId,
        String requestId,
        int corrigendumVersion,
        int previousVersion,
        int snapshotVersion,
        List<FieldChange> changes,
        String operator,
        Instant submittedAtUtc,
        String location,
        String reading,
        String note) {

    /**
     * 由附录记录与应用后的有效值快照构造响应。
     */
    public static CorrigendumResponse of(CorrigendumRecord record, EffectiveValues effective) {
        return new CorrigendumResponse(
                record.corrigendumId(),
                record.observationId(),
                record.requestId(),
                record.corrigendumVersion(),
                record.previousVersion(),
                record.snapshotVersion(),
                List.copyOf(record.changes()),
                record.operator(),
                record.submittedAtUtc(),
                effective.location(),
                effective.reading(),
                effective.note());
    }
}
