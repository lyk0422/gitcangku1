package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

/**
 * 披露快照响应：首次发布的不可变内容（胜出版本、冲突裁决、质量标记）及后续更正附页。
 *
 * @param observationId    观测记录唯一标识
 * @param winningVersion   发布时点胜出（当前）版本号
 * @param deleted          发布时点当前状态是否为删除墓碑
 * @param location         发布时点胜出版本观测地点；删除墓碑为 null
 * @param reading          发布时点胜出版本观测读数；删除墓碑为 null
 * @param note             发布时点胜出版本观测备注；删除墓碑为 null
 * @param resolutions      发布时点冲突裁决摘要，按解决时刻升序
 * @param qualityFlags     发布时点质量标记：DELETED / CONFLICT_RESOLVED / CLEAN
 * @param releaseRequestId 触发首次发布的请求标识
 * @param operator         执行首次发布的操作者标识
 * @param releasedAtUtc    首次公开发布时刻（UTC）
 * @param appendices       首次发布后的更正附页，按 seq 升序
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DisclosureResponse(
        String observationId,
        int winningVersion,
        boolean deleted,
        String location,
        String reading,
        String note,
        List<ResolutionSummary> resolutions,
        List<String> qualityFlags,
        String releaseRequestId,
        String operator,
        Instant releasedAtUtc,
        List<AppendixRecord> appendices) {

    private static final TypeReference<List<ResolutionSummary>> RESOLUTIONS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> FLAGS_TYPE = new TypeReference<>() {
    };

    /**
     * 由快照记录与附页列表组装响应；内嵌 JSON 原文在此反序列化。
     */
    public static DisclosureResponse of(DisclosureSnapshotRecord record, List<AppendixRecord> appendices,
                                        ObjectMapper objectMapper) {
        return new DisclosureResponse(
                record.observationId(),
                record.winningVersion(),
                record.deleted(),
                record.location(),
                record.reading(),
                record.note(),
                read(record.resolutionsJson(), RESOLUTIONS_TYPE, objectMapper),
                read(record.qualityFlagsJson(), FLAGS_TYPE, objectMapper),
                record.releaseRequestId(),
                record.operator(),
                record.releasedAtUtc(),
                List.copyOf(appendices));
    }

    private static <T> T read(String json, TypeReference<T> type, ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored disclosure fields", e);
        }
    }
}