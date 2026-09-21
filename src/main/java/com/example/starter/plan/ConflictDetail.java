package com.example.starter.plan;

import java.util.List;

/**
 * 时隙冲突明细：冲突区段与发生冲突的已发布计划。
 *
 * @param sectionId 冲突区段 ID
 * @param trainNo 本计划冲突占用的列车编号
 * @param conflictingScheduleKey 与本计划冲突的已发布计划业务键；计划内列车自冲突时为 null
 */
public record ConflictDetail(String sectionId, String trainNo, String conflictingScheduleKey) {

    /**
     * 合并多条冲突明细为不可变列表。
     */
    public static List<ConflictDetail> listOf(ConflictDetail... details) {
        return List.of(details);
    }
}
