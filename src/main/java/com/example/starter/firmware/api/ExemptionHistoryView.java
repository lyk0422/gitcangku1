package com.example.starter.firmware.api;

import java.util.List;

/**
 * 豁免历史条目，只增不改。supersededAtUtc 为 null 表示该版本仍为当前版本。
 */
public record ExemptionHistoryView(int version, List<String> modules, String expiresAtUtc,
                                   String createdAtUtc, String supersededAtUtc) {
}
