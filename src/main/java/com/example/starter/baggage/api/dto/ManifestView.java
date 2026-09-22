package com.example.starter.baggage.api.dto;

import java.util.List;

/**
 * 封舱清单视图（封舱后只读；OPEN 航段查询时返回当前装载明细）。
 *
 * @param legId   航段标识
 * @param status  航段状态
 * @param version 航段当前版本号
 * @param bagTags 清单内行李牌号（按牌号排序）
 */
public record ManifestView(String legId, String status, int version, List<String> bagTags) {
}
