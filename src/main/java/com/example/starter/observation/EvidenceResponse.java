package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 授权证据查询响应：不受禁运限制，返回当前内容、禁运设置与未解决冲突标记。
 *
 * @param observationId   观测记录唯一标识
 * @param version         当前版本号
 * @param deleted         当前状态是否为删除墓碑
 * @param location        观测地点（墓碑为 null）
 * @param reading         观测读数（墓碑为 null）
 * @param note            观测备注（墓碑为 null）
 * @param embargoUntilUtc 禁运截止时刻（UTC）；未设禁运为 null
 * @param disclosureScope 披露范围；未设禁运为 null
 * @param embargoSetAtUtc 禁运设置时刻（UTC）；未设禁运为 null
 * @param pendingConflict 是否存在未解决冲突
 * @param conflictFields  未解决冲突字段名列表；无未解决冲突为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvidenceResponse(
        String observationId,
        int version,
        boolean deleted,
        String location,
        String reading,
        String note,
        Instant embargoUntilUtc,
        String disclosureScope,
        Instant embargoSetAtUtc,
        boolean pendingConflict,
        List<String> conflictFields) {
}