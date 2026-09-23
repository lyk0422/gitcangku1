package com.example.starter.blind.dto;

import java.util.List;

/**
 * 披露登记视图：登记成功后返回，仅包含边信息，不含处理代码。
 *
 * @param exposureKey    披露键
 * @param experimentId   实验编号
 * @param sourceActor    披露源操作者编号（登记人本人）
 * @param receiverActors 去重后的接收操作者编号
 * @param participantIds 去重后实际登记的参与者编号
 * @param newEdges       本次新增的“操作者—参与者”披露边数量；重复边不新增
 * @param createdAt      登记时间，Unix 毫秒，UTC
 */
public record DisclosureView(
        String exposureKey,
        String experimentId,
        String sourceActor,
        List<String> receiverActors,
        List<String> participantIds,
        int newEdges,
        long createdAt
) {
}
