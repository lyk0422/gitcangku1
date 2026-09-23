package com.example.starter.blind.dto;

import java.util.List;

/**
 * 参与者当前污染闭包视图；任何情况下都不包含处理代码。
 *
 * @param experimentId   实验编号
 * @param participantId  参与者编号
 * @param currentVersion 当前版本号；尚无披露时为 null
 * @param versionStatus  当前版本状态 OPEN / CLOSED；尚无版本时为 null
 * @param contaminatedActors 闭包内全部操作者编号（有序去重，含已批准揭盲申请人根节点）
 * @param edgeCount      当前披露有向边总数
 * @param versionCreatedAt 当前版本生成时间，Unix 毫秒，UTC；无版本时为 null
 * @param versionClosedAt  当前版本冻结时间，Unix 毫秒，UTC；OPEN 或无版本时为 null
 */
public record ClosureView(
        String experimentId,
        String participantId,
        Integer currentVersion,
        String versionStatus,
        List<String> contaminatedActors,
        int edgeCount,
        Long versionCreatedAt,
        Long versionClosedAt
) {
}
