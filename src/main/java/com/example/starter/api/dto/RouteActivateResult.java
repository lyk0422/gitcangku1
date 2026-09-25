package com.example.starter.api.dto;

/**
 * 航线版本激活结果。
 *
 * @param routeId     航线标识
 * @param version     激活的航线版本
 * @param bucketCount 占用的时空桶数量（穿越序列长度）
 * @param reviewId    激活所依据的审查通过记录标识
 */
public record RouteActivateResult(String routeId, int version, int bucketCount, String reviewId) {
}
