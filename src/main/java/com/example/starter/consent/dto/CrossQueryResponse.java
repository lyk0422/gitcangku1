package com.example.starter.consent.dto;

import java.time.Instant;
import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 跨域查询响应：返回冻结快照标识与固化条目。快照生成后不受后续迁移、撤回、清除影响。
 *
 * @param snapshotId     查询快照标识
 * @param createdAt      快照生成时间（UTC）
 * @param requestedCount 请求查询的对象数（要求值）
 * @param itemCount      快照固化的对象数（实际值）
 * @param items          固化条目，顺序与请求对象顺序一致
 */
public record CrossQueryResponse(
        String snapshotId,
        Instant createdAt,
        int requestedCount,
        int itemCount,
        List<QueryItem> items) {

    /**
     * 快照条目：固化对象的代次、驻留区域、授权版本与当时内容。
     *
     * @param subjectKey     主体标识（合成字符串）
     * @param purpose        用途：RESEARCH 研究 / PERSONALIZATION 个性化
     * @param epoch          快照时对象所属授权代次
     * @param consentVersion 快照时授权版本号
     * @param regionCode     快照时对象驻留区域代码；NULL 表示无区域
     * @param recordKey      记录键
     * @param payload        快照时记录内容（合成字符串）
     */
    public record QueryItem(
            String subjectKey,
            Purpose purpose,
            int epoch,
            int consentVersion,
            String regionCode,
            String recordKey,
            String payload) {
    }
}
