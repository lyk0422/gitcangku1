package com.example.starter.batch.dto;

import java.util.List;

/**
 * 声明历史响应：某声明键的全部版本，按版本号升序（稳定排序）。
 */
public record DeclarationHistoryResponse(
        String declarationKey,
        List<DeclarationResponse> versions
) {
}
