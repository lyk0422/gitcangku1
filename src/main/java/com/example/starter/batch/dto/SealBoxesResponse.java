package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批量封箱响应：本次提交的封箱结果与提交后的汇总（实际值/要求值/差额）。
 */
public record SealBoxesResponse(
        String batchKey,
        List<SealResponse> seals,
        LabelSummary summary
) {
}
