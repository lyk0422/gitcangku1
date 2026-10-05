package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 放行诊断响应：只读，不改变状态。releasable 为当前时点是否满足合格证放行条件；
 * reasons 按固定检查顺序列出全部不满足原因（满足时为空列表）；binding 未绑定为 null。
 */
public record CertificateDiagnosisResponse(
        String batchKey,
        boolean releasable,
        List<String> reasons,
        CertificateDetailResponse.BindingInfo binding,
        Instant checkedAt
) {
}
