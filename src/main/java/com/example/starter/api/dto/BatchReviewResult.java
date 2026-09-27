package com.example.starter.api.dto;

import java.util.List;

/**
 * 批量审查结果。结果按 routeId 字典序稳定排序；整批同事务创建或整体不创建。
 *
 * @param results 各航线审查结果（按 routeId 升序）
 */
public record BatchReviewResult(List<ReviewResultDto> results) {
}
