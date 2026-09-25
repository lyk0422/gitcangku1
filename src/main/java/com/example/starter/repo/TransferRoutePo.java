package com.example.starter.repo;

import com.example.starter.domain.Bucket;

import java.util.List;

/**
 * 转配前后单条航线的冻结证据（不可变）。
 *
 * @param transferKey 所属转配单标识
 * @param routeId     参与航线标识
 * @param oldVersion  转配前航线版本
 * @param newVersion  转配后航线版本
 * @param reviewId    转配时冻结的审查依据记录标识
 * @param beforePath  转配前完整穿越序列（有序时空桶）
 * @param afterPath   转配后完整穿越序列（有序时空桶）
 */
public record TransferRoutePo(String transferKey, String routeId, int oldVersion, int newVersion,
                              String reviewId, List<Bucket> beforePath, List<Bucket> afterPath) {
}
