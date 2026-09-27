package com.example.starter.exposure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量曝光确认回执请求。整批共用一个幂等键与操作者；
 * 任一预占归属不匹配或结构非法时整批不结算（422），不留半成品。
 *
 * @param requestId  写操作全局唯一幂等键；同键同参重放首次完整快照
 * @param operatorId 操作者编号，不可空；纳入幂等指纹
 * @param receipts   回执条目集合，1～500 条，按提交集合裁决；集合内预占编号重复为 422
 */
public record ReceiptBatchRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operatorId,
        @NotEmpty @Size(max = 500) @Valid List<ReceiptItem> receipts
) {

    /**
     * 单条回执条目。
     *
     * @param reservationId 目标预占单编号（预占不存在返回 422）
     * @param campaignId    声明归属公告编号；与预占归属不匹配整批 422
     * @param visitorId     声明归属访客编号；与预占归属不匹配整批 422
     * @param receiptAtUtc  回执业务时刻，epoch 毫秒，UTC；早于预占时刻为窗口外（422 不产生流水）
     */
    public record ReceiptItem(
            @NotBlank @Size(max = 64) String reservationId,
            @NotBlank @Size(max = 64) String campaignId,
            @NotBlank @Size(max = 64) String visitorId,
            @NotNull Long receiptAtUtc
    ) {
    }
}
