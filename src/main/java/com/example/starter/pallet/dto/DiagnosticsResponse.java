package com.example.starter.pallet.dto;

import java.util.List;

/**
 * 托盘隔离诊断响应（只读）：按当前在托批次与隔板配置评估隔离关系。
 * 数量字段同时给出要求值（requiredDividers）、实际值（actualDividers）与差额（dividerDeficit）。
 */
public record DiagnosticsResponse(
        String palletKey,
        String status,
        int dividerCount,
        int dividerVersion,
        int activeLoadCount,
        int distinctCategories,
        int requiredDividers,
        int actualDividers,
        int dividerDeficit,
        int availableCompartments,
        boolean segregationValid,
        List<String> conflicts,
        List<LoadEntry> loads
) {
    /**
     * 诊断视角的在托装载项。
     */
    public record LoadEntry(
            String batchKey,
            String category,
            int compartment,
            boolean reviewFlag
    ) {
    }
}
