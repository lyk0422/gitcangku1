package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 授权证据查询响应：不受禁运限制，返回当前内容与禁运状态全貌。
 *
 * @param observationId   观测记录唯一标识
 * @param version         当前版本号
 * @param deleted         当前是否为删除墓碑
 * @param location        当前观测地点（墓碑为 null）
 * @param reading         当前观测读数（墓碑为 null）
 * @param note            当前观测备注（墓碑为 null）
 * @param embargoed       当前是否处于禁运期内
 * @param embargoUntilUtc 禁运截止时刻（UTC）；未设置禁运为 null
 * @param disclosureScope 披露范围；未设置禁运为 null
 * @param openConflicts   当前未解决冲突数量（实际值）
 * @param released        是否已生成首次披露快照
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvidenceResponse(
        String observationId,
        int version,
        boolean deleted,
        String location,
        String reading,
        String note,
        boolean embargoed,
        Instant embargoUntilUtc,
        String disclosureScope,
        int openConflicts,
        boolean released) {
}