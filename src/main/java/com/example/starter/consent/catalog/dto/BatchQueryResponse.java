package com.example.starter.consent.catalog.dto;

import java.util.List;

/**
 * 批量查询响应：所有结果基于同一目录代次读取。
 *
 * @param catalogGeneration 本次读取固定的目录代次
 * @param results           与请求条目一一对应的结果（保持请求顺序）
 */
public record BatchQueryResponse(int catalogGeneration, List<BatchQueryResult> results) {
}
