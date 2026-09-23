package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 簇归并结果响应：返回新主记录、成员冻结证据与字段级溯源，全部只读。
 *
 * @param clusterKey          簇标识
 * @param canonical           新生成的 canonical 主记录（generation 从 1 开始）
 * @param siteKey             簇分组站点键
 * @param type                簇分组观测类型
 * @param windowStart         重算的簇观测时间范围下限（UTC）
 * @param windowEnd           重算的簇观测时间范围上限（UTC）
 * @param members             成员冻结证据列表（按提交顺序）
 * @param fieldSources        字段级来源证据列表（按字段名升序）
 */
public record ClusterMergeResponse(
        String clusterKey,
        ObservationResponse canonical,
        String siteKey,
        String type,
        Instant windowStart,
        Instant windowEnd,
        List<MemberView> members,
        List<FieldSourceView> fieldSources) {

    /**
     * 成员只读视图：冻结的 generation、设备、时间与字段值及归并后状态。
     */
    public record MemberView(
            String recordKey,
            int generation,
            String deviceId,
            Instant observedAt,
            String location,
            String reading,
            String note,
            String mergeStatus) {

        public static MemberView of(ClusterMemberEvidence evidence) {
            return new MemberView(evidence.recordKey(), evidence.generation(), evidence.deviceId(),
                    evidence.observedAt(), evidence.location(), evidence.reading(), evidence.note(),
                    MergeStatus.MERGED.name());
        }
    }

    /**
     * 字段级溯源只读视图：字段名、来源成员键、来源 generation 与采纳值。
     */
    public record FieldSourceView(
            String field,
            String sourceRecordKey,
            int sourceGeneration,
            String value) {

        public static FieldSourceView of(ClusterFieldSource source) {
            return new FieldSourceView(source.fieldName(), source.sourceRecordKey(),
                    source.sourceGeneration(), source.sourceValue());
        }
    }
}
