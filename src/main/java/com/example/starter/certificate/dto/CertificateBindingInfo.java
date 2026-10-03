package com.example.starter.certificate.dto;

import java.time.Instant;

/**
 * 批次历史中的合格证绑定信息。绑定字段为绑定时刻固化的业务证据；
 * declarationStatus 为声明版本的当前实时状态（撤回不改写绑定本身）。
 * matchedPrefix 在拆分继承且子批号不匹配任何前缀时为 null。
 */
public record CertificateBindingInfo(
        String declarationKey,
        int declarationVersion,
        String matchedPrefix,
        String boundBy,
        Instant boundAt,
        String declarationStatus
) {
}