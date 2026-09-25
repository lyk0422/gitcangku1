package com.example.starter.firmware.api;

import java.util.List;

/**
 * 设备被拒回执列表响应（只读），按拒绝时间升序。
 */
public record RejectedReceiptListResponse(String deviceId, List<RejectedReceiptView> receipts) {
}
