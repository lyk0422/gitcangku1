package com.example.starter.certificate.dto;

import java.util.List;

/**
 * 声明明细响应：业务键 + 全部版本，按版本号升序（稳定排序）。
 */
public record DeclarationDetailResponse(
        String declarationKey,
        List<DeclarationVersionResponse> versions
) {
}