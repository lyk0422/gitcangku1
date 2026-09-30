package com.example.starter.film.api;

import java.time.Instant;

/**
 * 交付回执视图，用于查询。
 */
public record ReceiptView(String receiptNo, String masterNo, String requestId,
                          String snapshotJson, Instant createdAt) {
}
