package com.example.starter.evidence.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 完整保管链视图：入库、交接发起/接受/取消、封条核验按时间升序排列的只追加事件流。
 */
public record CustodyChainResponse(
        String evidenceKey,
        List<ChainEvent> events
) {
    /**
     * 保管链事件；仅与本事件类型相关的字段非空。
     *
     * @param type INTAKE 入库 / TRANSFER_INITIATE 交接发起 / TRANSFER_ACCEPT 交接接受 /
     *             TRANSFER_CANCEL 交接取消 / SEAL_CHECK 封条核验
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChainEvent(
            String type,
            String actorId,
            String fromCustodianId,
            String toCustodianId,
            String result,
            String detail,
            LocalDateTime occurredAt
    ) {
    }
}
