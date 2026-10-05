package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

/**
 * 到期发布快照视图：首次公开发布的不可变披露内容。
 *
 * @param releaseId           发布快照标识
 * @param observationId       观测记录唯一标识（簇）
 * @param embargoUntilUtc     禁运截止时刻（UTC，ISO-8601）
 * @param disclosureScope     披露范围标签
 * @param releasedVersion     发布时胜出版本号
 * @param deleted             发布时簇是否为删除墓碑
 * @param location            胜出版本观测地点（墓碑时不返回）
 * @param reading             胜出版本观测读数（墓碑时不返回）
 * @param note                胜出版本观测备注（墓碑时不返回）
 * @param conflictResolutions 发布时全部冲突裁决摘要（按解决时刻排序）
 * @param qualityFlags        质量标记
 * @param releasedAtUtc       首次发布时刻（UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReleaseResponse(
        String releaseId,
        String observationId,
        Instant embargoUntilUtc,
        List<String> disclosureScope,
        int releasedVersion,
        boolean deleted,
        String location,
        String reading,
        String note,
        List<ResolutionSummary> conflictResolutions,
        List<String> qualityFlags,
        Instant releasedAtUtc) {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<ResolutionSummary>> SUMMARY_LIST_TYPE = new TypeReference<>() {
    };

    /**
     * 发布时的冲突裁决摘要。
     *
     * @param resolutionId    解决记录标识
     * @param previousVersion 解决前当前版本号
     * @param newVersion      解决后指向的版本号
     * @param operator        解决操作者标识
     * @param resolvedAtUtc   解决完成时刻（UTC，ISO-8601）
     */
    public record ResolutionSummary(
            String resolutionId,
            int previousVersion,
            int newVersion,
            String operator,
            Instant resolvedAtUtc) {
    }

    /**
     * 由不可变发布记录构造响应；披露范围、裁决摘要与质量标记从 JSON 原文反序列化。
     */
    public static ReleaseResponse of(ReleaseSnapshotRecord record, ObjectMapper objectMapper) {
        return new ReleaseResponse(
                record.releaseId(),
                record.observationId(),
                record.embargoUntilUtc(),
                read(record.disclosureScope(), STRING_LIST_TYPE, objectMapper),
                record.releasedVersion(),
                record.deleted(),
                record.deleted() ? null : record.location(),
                record.deleted() ? null : record.reading(),
                record.deleted() ? null : record.note(),
                read(record.conflictResolutions(), SUMMARY_LIST_TYPE, objectMapper),
                read(record.qualityFlags(), STRING_LIST_TYPE, objectMapper),
                record.releasedAtUtc());
    }

    private static <T> T read(String json, TypeReference<T> type, ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored release fields", e);
        }
    }
}
