package com.example.starter.firmware.api;

import java.util.List;

/**
 * 被拒回执列表响应。
 */
public record RejectedReceiptListResponse(List<RejectedReceiptView> receipts) {
}
