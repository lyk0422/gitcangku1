package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 聚合查询响应：按主体、用途、代次列出全部记录，标明每条记录所属子范围及是否可用。
 * 已撤回子范围的记录仍返回并标记 available=false，不做静默过滤。
 *
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch      授权代次
 * @param records    记录清单
 */
public record AggregateResponse(String subjectKey, Purpose purpose, int epoch, List<AggregateEntry> records) {

    /**
     * 聚合记录条目。
     *
     * @param recordKey 记录键
     * @param scopeKey  所属子范围标识，default 表示默认子范围
     * @param payload   记录内容（合成字符串）
     * @param available 当前是否可用：所属子范围已撤回时为 false
     */
    public record AggregateEntry(String recordKey, String scopeKey, String payload, boolean available) {
    }
}
