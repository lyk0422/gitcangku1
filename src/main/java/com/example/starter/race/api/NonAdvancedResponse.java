package com.example.starter.race.api;

import java.util.List;

/**
 * 未晋级清单：分组内具备有效成绩但未进入名单的选手，按分组顺序与组内成绩排列。
 *
 * @param raceId         赛事ID
 * @param advancementKey 当前生效名单键；尚无生效名单时为 null
 * @param version        当前赛事版本号
 * @param groups         按分组顺序排列的未晋级清单
 */
public record NonAdvancedResponse(
        String raceId,
        String advancementKey,
        int version,
        List<GroupNonAdvanced> groups
) {

    /**
     * 单个分组的未晋级选手。
     *
     * @param groupCode 分组代码
     * @param position  分组顺序
     * @param members   未晋级选手参赛号，按组内成绩（总耗时升序、并列按参赛号）排列
     */
    public record GroupNonAdvanced(
            String groupCode,
            int position,
            List<String> members
    ) {
    }
}
