package com.example.starter.race.api;

import java.util.List;

/**
 * 批量批准重分的响应：不可变重分记录与全部受影响组别（来源与目标）重算后的完整排名。
 *
 * @param raceId    赛事ID
 * @param version   批次提交后的赛事版本号
 * @param records   本批次写入的不可变重分记录，按申请ID字典序排列
 * @param standings 全部受影响组别重算后的排名，按组别代码字典序排列
 */
public record ReclassApprovalResponse(
        String raceId,
        int version,
        List<ReclassRecordResponse> records,
        List<CategoryStandingResponse> standings
) {
}