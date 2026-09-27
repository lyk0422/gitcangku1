package com.example.starter.maintenance.api.dto;

import java.util.List;

/**
 * 批量隔离响应：整批一个 requestId，全部成功或整批回滚。
 *
 * @param requestId  批量请求 requestId
 * @param operatorId 创建操作人
 * @param total      本批隔离令数量（要求值与实际值一致，失败则整批无结果）
 * @param orders     各设备隔离令快照，按请求中的设备提交顺序排列
 */
public record BatchIsolationResponse(
        String requestId,
        String operatorId,
        int total,
        List<IsolationOrderResponse> orders) {
}
