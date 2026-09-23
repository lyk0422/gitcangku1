package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 取消移交请求：仅允许源厂在尚未接收且清单全部仍在途时执行，原子恢复发运前状态。
 */
public record CancelHandoffRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "plant 不能为空") String plant
) {
}
