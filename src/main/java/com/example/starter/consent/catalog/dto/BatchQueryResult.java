package com.example.starter.consent.catalog.dto;

/**
 * 批量查询单条结果：found=false 时数据字段为空；error 给出未命中原因（空表示正常）。
 *
 * @param subjectKey     主体标识
 * @param purpose        用途代码
 * @param recordKey      记录键
 * @param found          是否查到记录
 * @param epoch          记录所属授权代次；未查到为空
 * @param payload        记录内容；未查到为空
 * @param attributeValue 记录属性取值；未查到为空
 * @param error          未命中原因：NOT_FOUND 无记录 / GRANT_MISSING 无有效授权 /
 *                       CONSENT_REVOKED 授权已撤回 / GRANT_MIGRATED 授权已迁移
 */
public record BatchQueryResult(String subjectKey, String purpose, String recordKey, boolean found,
                               Integer epoch, String payload, String attributeValue, String error) {

    /**
     * 构造未命中结果。
     */
    public static BatchQueryResult missing(BatchQueryItem item, String error) {
        return new BatchQueryResult(item.subjectKey(), item.purpose(), item.recordKey(),
                false, null, null, null, error);
    }
}
