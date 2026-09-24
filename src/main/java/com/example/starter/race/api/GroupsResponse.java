package com.example.starter.race.api;

import java.util.List;

/**
 * 分组划分响应。
 *
 * @param raceId  赛事ID
 * @param version 划分后的赛事版本号
 * @param groups  分组及其成员（按分组代码、参赛号字典序）
 */
public record GroupsResponse(
        String raceId,
        int version,
        List<GroupResponse> groups
) {

    /**
     * 单个分组视图。
     *
     * @param groupCode 分组代码
     * @param bibs      组内选手参赛号，按字典序排列
     */
    public record GroupResponse(String groupCode, List<String> bibs) {
    }
}
