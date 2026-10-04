package com.example.starter.observation;

import java.util.List;

/**
 * 批量禁运设置响应：整批校验通过并在一个事务中提交后返回。
 *
 * @param requestId      请求标识
 * @param operator       操作者标识
 * @param requestedCount 请求要求设置的簇数量
 * @param appliedCount   实际设置的簇数量（全部成功时等于 requestedCount）
 * @param items          各簇设置结果，按 observationId 字典序稳定排序
 */
public record EmbargoBatchResponse(
        String requestId,
        String operator,
        int requestedCount,
        int appliedCount,
        List<EmbargoItemResponse> items) {
}