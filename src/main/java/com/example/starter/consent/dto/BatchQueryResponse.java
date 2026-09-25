package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 批量数据查询快照响应：创建后不可改写，后续证明撤销、授权撤回均不影响本快照内容。
 *
 * @param batchId     批次查询标识
 * @param recipientId 发起查询的数据接收方标识
 * @param purpose     查询用途
 * @param items       逐主体快照，按主体标识字典序稳定排列
 */
public record BatchQueryResponse(
        long batchId,
        String recipientId,
        Purpose purpose,
        List<Item> items) {

    /**
     * 单主体快照：记录查询时的授权代次、所用证明版本与记录内容。
     *
     * @param subjectKey         主体标识
     * @param epoch              查询时主体的当前授权代次
     * @param attestationVersion 门禁校验所用的证明版本
     * @param records            该主体当前代次的记录快照
     */
    public record Item(String subjectKey, int epoch, int attestationVersion, List<RecordView> records) {
    }

    /**
     * 快照中的单条记录。
     *
     * @param recordKey 记录键
     * @param payload   记录内容
     */
    public record RecordView(String recordKey, String payload) {
    }
}
