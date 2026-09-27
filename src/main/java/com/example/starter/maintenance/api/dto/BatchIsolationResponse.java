package com.example.starter.maintenance.api.dto;

import java.util.List;

/**
 * 批量能源隔离视图：整批在一个事务内生效，条目顺序与请求一致（稳定排序）。
 *
 * @param requestId  批量请求 requestId
 * @param items      各设备隔离令视图（首次成功响应快照的组成部分）
 */
public record BatchIsolationResponse(
        String requestId,
        List<IsolationResponse> items) {
}
