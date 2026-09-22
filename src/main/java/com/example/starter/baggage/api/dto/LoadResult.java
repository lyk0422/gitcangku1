package com.example.starter.baggage.api.dto;

import java.util.List;

/**
 * 批量装载结果。
 *
 * @param legId       航段标识
 * @param status      装载后航段状态（OPEN）
 * @param version     装载后航段版本号
 * @param loadedCount 本批装载件数
 * @param bagTags     本批装载的行李牌号（按提交顺序）
 */
public record LoadResult(String legId, String status, int version, int loadedCount, List<String> bagTags) {
}
