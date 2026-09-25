package com.example.starter.api.dto;

import java.util.List;

/**
 * 批量审查结果（与请求 items 同序）。
 *
 * @param reviews 各航线审查结果
 */
public record BatchReviewResult(List<ReviewResultDto> reviews) {
}
