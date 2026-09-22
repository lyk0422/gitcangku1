package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 拆分成功响应：父批已置为 SPLIT，子批全部初始 QUARANTINED；splitAt 为拆分提交时间（UTC instant）。
 */
public record SplitResponse(
        BatchResponse parent,
        List<BatchResponse> children,
        Instant splitAt
) {
}
