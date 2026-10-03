package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 披露快照明细：首次发布时胜出版本内容、最近一次冲突裁决与质量标记，以及全部更正附页。
 * 快照本体不可变；appendices 只增不改，按 seq 升序。
 *
 * @param observationId  观测记录唯一标识
 * @param version        发布时胜出版本号
 * @param deleted        发布时是否为删除墓碑
 * @param location       胜出版本观测地点（墓碑为 null）
 * @param reading        胜出版本观测读数（墓碑为 null）
 * @param note           胜出版本观测备注（墓碑为 null）
 * @param resolutionId   最近一次冲突解决记录标识（无解决历史为 null）
 * @param conflictFields 最近一次裁决的冲突字段名列表（无解决历史为 null）
 * @param selections     最近一次裁决的人工选择（无解决历史为 null）
 * @param qualityFlags   质量标记：CLEAN/CONFLICT_RESOLVED/UNRESOLVED_CONFLICT/DELETED
 * @param operator       首次发布的操作者标识
 * @param releasedAtUtc  首次发布时刻（UTC）
 * @param appendixCount  附页总数（实际值）
 * @param appendices     更正附页，按 seq 升序
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DisclosureResponse(
        String observationId,
        int version,
        boolean deleted,
        String location,
        String reading,
        String note,
        String resolutionId,
        List<String> conflictFields,
        Map<String, String> selections,
        List<String> qualityFlags,
        String operator,
        Instant releasedAtUtc,
        int appendixCount,
        List<AppendixResponse> appendices) {
}