package com.example.starter.observation;

import java.time.Instant;

/**
 * 联合裁决不可变记录：对应 bundle_arbitration 表的一行，成功裁决原子落库，永不修改。
 *
 * @param arbitrationId 全局唯一联合裁决记录标识
 * @param bundleKey     被裁决的关联观测簇标识
 * @param requestId     生成该裁决的请求标识
 * @param surveyId      簇所属调查问卷标识
 * @param operator      执行联合裁决的审核员标识
 * @param fieldSources  逐字段来源 JSON 原文（按观测、字段稳定排序）
 * @param restoreBasis  墓碑恢复依据 JSON 原文
 * @param snapshotBefore 裁决前簇级快照 JSON 原文
 * @param snapshotAfter  裁决后簇级快照 JSON 原文
 * @param arbitratedAtUtc 裁决完成时刻（UTC）
 */
public record BundleArbitrationRecord(
        String arbitrationId,
        String bundleKey,
        String requestId,
        String surveyId,
        String operator,
        String fieldSources,
        String restoreBasis,
        String snapshotBefore,
        String snapshotAfter,
        Instant arbitratedAtUtc) {
}
