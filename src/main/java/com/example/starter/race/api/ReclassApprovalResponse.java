package com.example.starter.race.api;

import java.util.List;

/**
 * 批量批准组别重分的响应：不可变重分记录 + 迁移后来源与目标组别的重算排名。
 *
 * @param raceId     所属赛事ID
 * @param version    批准提交后的赛事版本号
 * @param reclassKey 批次幂等键
 * @param operator   批准操作者
 * @param records    本次写入的不可变重分记录（每名迁移选手一条）
 * @param standings  受影响的来源与目标组别在迁移后的重算排名，按组别代码排序
 */
public record ReclassApprovalResponse(
        String raceId,
        int version,
        String reclassKey,
        String operator,
        List<ReclassRecordResponse> records,
        List<CategoryStandingResponse> standings
) {
}