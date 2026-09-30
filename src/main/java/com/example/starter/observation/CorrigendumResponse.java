package com.example.starter.observation;

import java.time.Instant;

/**
 * 更正附录创建响应：返回不可变附录要点及该附录版本生效后的字段有效值。
 *
 * @param appendixId               全局唯一附录记录标识
 * @param observationId            观测记录唯一标识
 * @param requestId                生成该附录的请求标识
 * @param appendixVersion          本次附录版本号（V1 起）
 * @param previousAppendixVersion  前一附录版本号；V1 为 null
 * @param resolutionId             前置冲突裁决记录标识
 * @param snapshotVersion          原始快照版本号（V0）
 * @param fieldName                本次更正字段名
 * @param oldValue                 字段旧值完整原文
 * @param newValue                 字段新值完整原文
 * @param operator                 提交更正附录的观测记录员标识
 * @param correctedAtUtc           更正完成时刻（UTC，ISO-8601）
 * @param effectiveLocation        本附录生效后的观测地点
 * @param effectiveReading         本附录生效后的观测读数
 * @param effectiveNote            本附录生效后的观测备注
 */
public record CorrigendumResponse(
        String appendixId,
        String observationId,
        String requestId,
        int appendixVersion,
        Integer previousAppendixVersion,
        String resolutionId,
        int snapshotVersion,
        String fieldName,
        String oldValue,
        String newValue,
        String operator,
        Instant correctedAtUtc,
        String effectiveLocation,
        String effectiveReading,
        String effectiveNote) {

    /**
     * 由附录记录与其生效后的有效值构造响应。
     */
    public static CorrigendumResponse of(CorrigendumAppendix appendix, EffectiveValues effective) {
        return new CorrigendumResponse(
                appendix.appendixId(),
                appendix.observationId(),
                appendix.requestId(),
                appendix.appendixVersion(),
                appendix.previousAppendixVersion(),
                appendix.resolutionId(),
                appendix.snapshotVersion(),
                appendix.fieldName(),
                appendix.oldValue(),
                appendix.newValue(),
                appendix.operator(),
                appendix.correctedAtUtc(),
                effective.location(),
                effective.reading(),
                effective.note());
    }
}
