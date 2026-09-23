package com.example.starter.consent.migration.dto;

import java.util.List;

/**
 * 批量查询响应：仅返回固定目录代次内的有效数据，条目顺序与请求一致。
 *
 * @param queryGeneration   使用的查询代次
 * @param catalogGeneration 固定的目录代次
 * @param results           查询结果
 */
public record BatchQueryResponse(long queryGeneration,
                                 long catalogGeneration,
                                 List<BatchQueryResult> results) {

    /**
     * 单条查询结果。
     *
     * @param subjectKey       主体标识
     * @param purpose          用途代码
     * @param epoch            授权代次
     * @param recordKey        记录键
     * @param recordAttribute  记录属性
     * @param payload          记录内容
     * @param found            是否在固定目录代次内命中有效记录
     */
    public record BatchQueryResult(String subjectKey, String purpose, int epoch, String recordKey,
                                   long recordAttribute, String payload, boolean found) {
    }
}
