package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 批量批准重分响应：迁移后的赛事版本、不可变重分记录，
 * 以及全部涉及组别（来源与目标）重算后的完整排名。
 *
 * @param raceId    赛事ID
 * @param version   批准后的赛事版本号
 * @param batchId   批准批次ID（reclassKey）
 * @param records   本批写入的不可变重分记录
 * @param standings 涉及组别代码 → 该组别重算后的完整排名（按展示顺序）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReclassApprovalResponse(
        String raceId,
        int version,
        String batchId,
        List<ReclassRecordResponse> records,
        Map<String, List<ResultEntryResponse>> standings
) {
}
