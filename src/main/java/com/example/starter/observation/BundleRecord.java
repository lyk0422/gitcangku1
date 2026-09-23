package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 关联观测簇记录：对应 observation_bundle 表的一行。
 *
 * @param bundleKey        簇唯一业务标识
 * @param surveyId         簇内全部观测必须归属的调查问卷标识
 * @param status           簇状态（OPEN/CLOSED）
 * @param consistentFields 要求簇内一致的字段名列表（location/reading/note 子集）
 * @param operator         建簇审核员标识
 * @param arbitrationId    结案联合裁决标识；未结时为 null
 * @param closedAtUtc      关闭时刻（UTC）；未结时为 null
 * @param createdAtUtc     建簇时刻（UTC）
 */
public record BundleRecord(
        String bundleKey,
        String surveyId,
        BundleStatus status,
        List<String> consistentFields,
        String operator,
        String arbitrationId,
        Instant closedAtUtc,
        Instant createdAtUtc) {
}
