package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 代理批量查询响应：成功时生成快照，固化每个主体的授权代次、委托指纹与版本及当时记录。
 *
 * @param queryId    批量查询标识（即请求 requestId）
 * @param delegateId 代理人标识
 * @param purpose    查询用途
 * @param items      逐主体查询结果
 */
public record BatchQueryResponse(
        String queryId,
        String delegateId,
        Purpose purpose,
        List<SubjectItem> items) {

    /**
     * 单主体查询结果（快照固化）。
     *
     * @param subjectKey      主体标识
     * @param epoch           查询时主体当前授权代次
     * @param delegateKey     查询时命中的委托指纹
     * @param delegateVersion 查询时命中的委托版本
     * @param records         当时返回的记录列表
     */
    public record SubjectItem(
            String subjectKey,
            int epoch,
            String delegateKey,
            int delegateVersion,
            List<RecordItem> records) {
    }

    /**
     * 记录项。
     *
     * @param recordKey 记录键
     * @param payload   记录内容
     */
    public record RecordItem(String recordKey, String payload) {
    }
}
