package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

/**
 * 观测更正附页响应：返回附页要点及解析后的字段差异与原值。
 *
 * @param observationId  观测记录唯一标识
 * @param corrVersion    附页版本号
 * @param corrKey        附页幂等键
 * @param baseVersion    附页指定的原观测版本号
 * @param diffs          字段差异（更正值）
 * @param originalValues 差异字段在原观测版本中的原值
 * @param reason         更正原因
 * @param collector      采集者标识
 * @param revoked        是否已撤销
 * @param createdAtUtc   附页提交时刻（UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CorrigendumResponse(
        String observationId,
        int corrVersion,
        String corrKey,
        int baseVersion,
        Map<String, String> diffs,
        Map<String, String> originalValues,
        String reason,
        String collector,
        boolean revoked,
        Instant createdAtUtc) {

    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 由附页记录构造响应；差异与原值从记录中的 JSON 原文反序列化。
     */
    public static CorrigendumResponse of(CorrigendumRecord record, ObjectMapper objectMapper) {
        return new CorrigendumResponse(
                record.observationId(),
                record.corrVersion(),
                record.corrKey(),
                record.baseVersion(),
                parseMap(record.diffs(), objectMapper),
                parseMap(record.originalValues(), objectMapper),
                record.reason(),
                record.collector(),
                record.revoked(),
                record.createdAtUtc());
    }

    private static Map<String, String> parseMap(String json, ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(json, STRING_MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored corrigendum map", e);
        }
    }
}
