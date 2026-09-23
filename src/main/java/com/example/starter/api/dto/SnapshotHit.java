package com.example.starter.api.dto;

/**
 * 快照中单个几何命中区域。
 *
 * @param regionKey     命中区域（禁飞区）标识
 * @param regionVersion 命中区域的生效空域版本
 * @param matched       是否存在版本匹配且未撤销、额度大于 0 的有效豁免项
 */
public record SnapshotHit(String regionKey, long regionVersion, boolean matched) {
}
