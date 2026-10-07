package com.example.starter.certificate.dto;

import java.util.List;

/**
 * 声明明细响应：同一声明键的全部版本，按版本号升序稳定排序。
 */
public record DeclarationDetailResponse(
        String declarationKey,
        List<DeclarationResponse> versions
) {
}
