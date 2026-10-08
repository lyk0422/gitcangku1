package com.example.starter.crosssection;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 断面冲突裁决响应：返回不可变裁决记录要点及裁决后指向版本的完整版本组。
 *
 * @param resolutionId    全局唯一裁决记录标识
 * @param observationId   断面观测点唯一标识
 * @param requestId       生成该记录的请求标识
 * @param baseVersion     基线版本号
 * @param previousVersion 裁决前当前版本号
 * @param version         裁决后指向版本号（无变化裁决时等于 previousVersion）
 * @param contentChanged  裁决后内容是否变化
 * @param conflictFields  服务端重算出的冲突字段名列表
 * @param selections      各冲突字段的人工选择
 * @param operator        操作者标识
 * @param resolvedAtUtc   裁决完成时刻（UTC，ISO-8601）
 * @param sectionNo       裁决后版本断面号
 * @param xMm             裁决后版本横向坐标（整数毫米）
 * @param zMm             裁决后版本地面高程（整数毫米）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CrossSectionResolutionResponse(
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
        String sectionNo,
        Integer xMm,
        Integer zMm) {

    private static final TypeReference<Map<String, String>> SELECTIONS_TYPE = new TypeReference<>() {
    };

    /**
     * 由裁决记录与指向版本快照组装响应；选择映射从记录中的 JSON 原文反序列化。
     */
    public static CrossSectionResolutionResponse of(CrossSectionResolutionRecord record,
                                                    CrossSectionSnapshot pointed,
                                                    ObjectMapper objectMapper) {
        return new CrossSectionResolutionResponse(
                record.resolutionId(), record.observationId(), record.requestId(),
                record.baseVersion(), record.previousVersion(), record.newVersion(),
                record.contentChanged(), List.copyOf(record.conflictFields()),
                parseSelections(record.fieldSelections(), objectMapper),
                record.operator(), record.resolvedAtUtc(),
                pointed.sectionNo(), pointed.xMm(), pointed.zMm());
    }

    private static Map<String, String> parseSelections(String json, ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(json, SELECTIONS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored selections", e);
        }
    }
}
