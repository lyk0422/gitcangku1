package com.example.starter.consent.dto;

import java.util.List;

/**
 * 批量查询成功响应：结果顺序与输入 items 完全一致。
 *
 * @param results 按输入顺序排列的记录结果
 */
public record BatchQueryResponse(List<RecordResponse> results) {
}
