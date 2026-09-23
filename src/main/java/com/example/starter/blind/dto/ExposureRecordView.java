package com.example.starter.blind.dto;

import java.util.List;

/**
 * 直接披露登记结果视图：不含处理代码。
 *
 * @param experimentId  实验编号
 * @param participantId 被披露处理代码的参与者编号
 * @param sourceActor   本次登记的披露源（持 exposureKey 的操作者本人）
 * @param addedEdges    本次实际新增的去重边数（已存在的接收人不重复新增）
 * @param version       登记后当前开放闭包版本号
 * @param edgeCount     当前版本去重有向边总数
 * @param closure       登记后完整污染闭包（持密操作者编号升序），不含处理代码
 */
public record ExposureRecordView(
        String experimentId,
        String participantId,
        String sourceActor,
        int addedEdges,
        int version,
        int edgeCount,
        List<String> closure
) {
}
