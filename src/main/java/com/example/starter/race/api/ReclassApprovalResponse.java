package com.example.starter.race.api;

import java.util.List;

/**
 * 批量批准重分申请响应：含已批准申请与全部受影响组别（来源与目标）重算后的完整排名。
 *
 * @param batchId   批准批次ID（=批准请求的 reclassKey）
 * @param raceId    赛事ID
 * @param version   批准后的赛事版本号
 * @param approved  本批已批准的申请
 * @param standings 全部受影响组别（来源与目标，按组别代码排序）迁移后的完整排名
 */
public record ReclassApprovalResponse(
        String batchId,
        String raceId,
        int version,
        List<ReclassRequestResponse> approved,
        List<CategoryStandingResponse> standings
) {
}
