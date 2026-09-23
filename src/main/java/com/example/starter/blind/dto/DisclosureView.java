package com.example.starter.blind.dto;

import java.util.List;

/**
 * 泄露披露登记结果视图；不返回处理代码。
 *
 * @param exposureKey      本次披露凭证
 * @param experimentId     实验编号
 * @param participantId    被披露参与者编号
 * @param sourceActorId    披露来源操作者（调用者本人）
 * @param targetActorIds   本次提交的全部接收人编号（有序去重）
 * @param newEdgeCount     本次实际新增的边数（重复边不新增）
 * @param duplicateEdgeCount 本次提交中与既有边重复、未新增的边数
 * @param closureVersion   提交后该参与者闭包新版本号
 * @param closureActors    提交后闭包内全部操作者编号（有序）
 * @param recordedAt       登记时间，Unix 毫秒，UTC
 */
public record DisclosureView(
        String exposureKey,
        String experimentId,
        String participantId,
        String sourceActorId,
        List<String> targetActorIds,
        int newEdgeCount,
        int duplicateEdgeCount,
        int closureVersion,
        List<String> closureActors,
        long recordedAt
) {
}
