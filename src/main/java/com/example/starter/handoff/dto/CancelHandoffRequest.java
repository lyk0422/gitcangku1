package com.example.starter.handoff.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 跨厂移交取消请求。仅允许尚未接收且清单全部仍在途时由源厂执行；
 * 在同一事务内原子恢复全部批次发运前状态并写取消快照。requestId 为幂等键。
 */
public record CancelHandoffRequest(
        @NotBlank(message = "requestId 不能为空") String requestId
) {
}
