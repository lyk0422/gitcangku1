package com.example.starter.observation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 重复观测簇归并结果响应（只读）：返回归并产生的主记录、成员冻结快照与字段级来源证据。
 *
 * @param clusterKey        归并簇标识
 * @param canonical         归并产生的 canonical 主记录
 * @param siteKey           站点标识
 * @param observationType   观测类型
 * @param observedAtFrom    确认时重算的成员观测时间范围下限（UTC）
 * @param observedAtTo      确认时重算的成员观测时间范围上限（UTC）
 * @param operator          执行归并的审核人标识
 * @param members           按记录键排序的成员冻结快照
 * @param fieldSources      按字段名（location/reading/note）排序的字段级来源证据
 */
public record ClusterResponse(
        String clusterKey,
        ObservationResponse canonical,
        String siteKey,
        String observationType,
        Instant observedAtFrom,
        Instant observedAtTo,
        String operator,
        List<ClusterMemberPreview> members,
        Map<String, FieldSourceView> fieldSources) {

    /**
     * 由落库的表头、主记录快照、成员冻结行与字段证据行组装只读响应。
     */
    public static ClusterResponse of(ClusterHeader header,
                                     ObservationSnapshot canonical,
                                     List<ClusterMemberRecord> memberRecords,
                                     List<ClusterFieldSourceRecord> sourceRecords) {
        List<ClusterMemberPreview> members = memberRecords.stream()
                .map(record -> new ClusterMemberPreview(
                        record.recordId(), record.generation(), record.siteKey(), record.observationType(),
                        record.deviceId(), record.observedAt(), record.location(), record.reading(), record.note()))
                .toList();
        Map<String, FieldSourceView> sources = new java.util.TreeMap<>();
        for (ClusterFieldSourceRecord record : sourceRecords) {
            sources.put(record.fieldName(), new FieldSourceView(
                    record.sourceRecordId(), record.sourceGeneration(), record.sourceValue()));
        }
        return new ClusterResponse(
                header.clusterKey(),
                ObservationResponse.of(canonical),
                header.siteKey(),
                header.observationType(),
                header.observedAtFrom(),
                header.observedAtTo(),
                header.operator(),
                List.copyOf(members),
                Map.copyOf(sources));
    }
}
