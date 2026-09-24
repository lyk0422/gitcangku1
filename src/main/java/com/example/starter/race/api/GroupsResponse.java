package com.example.starter.race.api;

import java.util.List;

/**
 * 分组划分结果响应；划分成功后不可改写。
 *
 * @param raceId          赛事ID
 * @param version         划分后的赛事版本号
 * @param groups          按分组顺序排列的分组及其成员（成员按参赛号字典序）
 */
public record GroupsResponse(
        String raceId,
        int version,
        List<GroupResponse> groups
) {

    /**
     * 单个分组。
     *
     * @param groupCode 分组代码
     * @param position  分组顺序，从1开始
     * @param members   成员参赛号列表
     */
    public record GroupResponse(
            String groupCode,
            int position,
            List<String> members
    ) {
    }
}
