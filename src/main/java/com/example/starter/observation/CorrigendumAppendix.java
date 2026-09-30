package com.example.starter.observation;

import java.time.Instant;

/**
 * 不可变观测更正附录：对应 corrigendum_appendix 表的一行，成功提交后原子落库，永不修改。
 * 冲突裁决生效后指向的历史快照只读，构成更正链的 V0；字段差异完全由附录链（V1..Vn）表达。
 *
 * @param appendixId               全局唯一附录记录标识
 * @param observationId            观测记录唯一标识
 * @param requestId                生成该附录的请求标识
 * @param appendixVersion          附录版本号，从 1 开始单调递增；裁决生效快照恒为 V0
 * @param previousAppendixVersion  前一附录版本号；V1 为 null，V2 起为前一附录版本
 * @param resolutionId             该观测簇冲突裁决记录标识
 * @param snapshotVersion          更正链 V0 基线快照在 observation_version 中的版本号（建链时裁决指向的 newVersion），附录引用但不修改
 * @param fieldName                本次更正字段名：location / reading / note
 * @param oldValue                 更正前旧值完整原文：V1 为 V0 基线值，其后为前一附录生效值
 * @param newValue                 更正后新值完整原文（不允许为空）
 * @param operator                 提交更正附录的观测记录员标识
 * @param correctedAtUtc           更正完成时刻（UTC）
 */
public record CorrigendumAppendix(
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
        Instant correctedAtUtc) {
}
