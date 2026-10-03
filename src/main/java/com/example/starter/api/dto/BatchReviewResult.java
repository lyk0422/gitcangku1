package com.example.starter.api.dto;

import java.util.List;

/**
 * 批量审核结果。
 *
 * @param requestedCount 请求要求审核的航线数量
 * @param createdCount   实际创建的审核结果数量（全部成功时等于 requestedCount）
 * @param results        各航线审核结果（顺序与请求 items 一致）
 */
public record BatchReviewResult(int requestedCount, int createdCount, List<ReviewResultDto> results) {
}