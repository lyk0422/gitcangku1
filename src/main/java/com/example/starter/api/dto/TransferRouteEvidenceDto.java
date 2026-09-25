package com.example.starter.api.dto;

import java.util.List;

/**
 * 转配冻结的单条航线证据。
 *
 * @param routeId    航线标识
 * @param oldVersion 转配前版本
 * @param newVersion 转配后版本
 * @param reviewId   冻结的审查依据记录标识
 * @param beforePath 转配前完整穿越序列（有序）
 * @param afterPath  转配后完整穿越序列（有序）
 */
public record TransferRouteEvidenceDto(String routeId, int oldVersion, int newVersion,
                                       String reviewId, List<BucketDto> beforePath,
                                       List<BucketDto> afterPath) {
}
