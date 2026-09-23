package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 关联观测簇记录：对应 observation_bundle 表的一行。
 *
 * @param bundleKey        关联簇唯一标识（业务唯一键）
 * @param surveyId         簇内全部观测所属的调查（survey）唯一标识
 * @param consistentFields 声明必须一致的字段名列表（location/reading/note 的子集，固定字段顺序存储）
 * @param status           簇状态：OPEN 未结 / CLOSED 已完成联合裁决
 * @param operator         建簇审核员标识
 * @param closedAtUtc      簇关闭时刻（UTC）；OPEN 时为 null
 */
public record BundleRecord(
        String bundleKey,
        String surveyId,
        List<String> consistentFields,
        String status,
        String operator,
        Instant closedAtUtc) {

    public static final String OPEN = "OPEN";
    public static final String CLOSED = "CLOSED";

    public boolean open() {
        return OPEN.equals(status);
    }
}
