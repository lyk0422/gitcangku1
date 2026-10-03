package com.example.starter.race.api;

import java.util.List;

/**
 * 赛事组别列表响应。
 *
 * @param raceId     赛事ID
 * @param version    当前赛事版本号
 * @param categories 全部组别，按组别代码字典序排列
 */
public record CategoriesResponse(
        String raceId,
        int version,
        List<CategoryResponse> categories
) {
}