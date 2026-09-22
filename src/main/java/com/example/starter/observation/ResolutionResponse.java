package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 冲突解决响应：返回不可变解决记录要点及解决后指向版本的内容。
 *
 * @param resolutionId    全局唯一解决记录标识
 * @param observationId   观测记录唯一标识
 * @param requestId       生成该记录的请求标识
 * @param baseVersion     基线版本号
 * @param previousVersion 解决前当前版本号
 * @param version         解决后指向的版本号（无变化解决时等于 previousVersion）
 * @param contentChanged  解决后内容是否发生变化：false 表示未生成新观测版本
 * @param conflictFields  服务端重算出的冲突字段名列表
 * @param selections      各冲突字段的人工选择
 * @param operator        操作者标识
 * @param resolvedAtUtc   解决完成时刻（UTC，ISO-8601）
 * @param location        解决后版本的观测地点
 * @param reading         解决后版本的观测读数
 * @param note            解决后版本的观测备注
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResolutionResponse(
        String resolutionId,
        String observationId,
        String requestId,
        int baseVersion,
        int previousVersion,
        int version,
        boolean contentChanged,
        List<String> conflictFields,
        Map<String, String> selections,
        String operator,
        Instant resolvedAtUtc,
        String location,
        String reading,
        String note) {

    private static final TypeReference<Map<String, String>> SELECTIONS_TYPE = new TypeReference<>() {
    };

    /**
     * 由解决记录与指向版本的快照构造响应；选择映射从记录中的 JSON 原文反序列化。
     */
    public static ResolutionResponse of(ResolutionRecord record, ObservationSnapshot pointed,
                                        ObjectMapper objectMapper) {
        return new ResolutionResponse(
                record.resolutionId(),
                record.observationId(),
                record.requestId(),
                record.baseVersion(),
                record.previousVersion(),
                record.newVersion(),
                record.contentChanged(),
                List.copyOf(record.conflictFields()),
                parseSelections(record.fieldSelections(), objectMapper),
                record.operator(),
                record.resolvedAtUtc(),
                pointed.location(),
                pointed.reading(),
                pointed.note());
    }

    private static Map<String, String> parseSelections(String json, ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(json, SELECTIONS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored selections", e);
        }
    }
}
