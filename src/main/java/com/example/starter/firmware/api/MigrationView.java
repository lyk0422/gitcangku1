package com.example.starter.firmware.api;

import java.util.List;

/**
 * 迁移单视图：迁移前后队列、指令代次与迟到回执证据；查询只读。
 */
public record MigrationView(String migrationKey, long campaignId, String status, int deviceCount,
                            List<MigrationItemView> items, List<LateReceiptView> lateReceipts) {
}
