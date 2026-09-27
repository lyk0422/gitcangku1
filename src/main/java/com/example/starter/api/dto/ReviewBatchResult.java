package com.example.starter.api.dto;

import java.util.List;

/**
 * 批量审核结果。
 *
 * @param results 各条目审核结果，顺序与请求条目一致
 */
public record ReviewBatchResult(List<ReviewResultDto> results) {
}
