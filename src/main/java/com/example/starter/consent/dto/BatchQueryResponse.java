package com.example.starter.consent.dto;

import java.util.List;

/**
 * 批量查询成功响应：结果顺序与请求项顺序严格一致。
 *
 * @param results 命中记录快照，逐项对应请求 items
 */
public record BatchQueryResponse(List<RecordResponse> results) {
}
