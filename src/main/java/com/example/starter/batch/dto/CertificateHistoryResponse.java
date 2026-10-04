package com.example.starter.batch.dto;

import java.util.List;

/**
 * 声明版本历史：同一 declarationKey 的全部版本，按 version 升序稳定排序；
 * 历史版本不因后续撤回或新版本发布而改写。
 */
public record CertificateHistoryResponse(
        String declarationKey,
        List<CertificateResponse> versions
) {
}