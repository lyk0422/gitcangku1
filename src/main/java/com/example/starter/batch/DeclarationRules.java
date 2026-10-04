package com.example.starter.batch;

import java.time.Instant;

/**
 * 合格证声明匹配与有效性规则：绑定、合批与放行门禁共用。
 * 时间一律按 UTC instant 比较，有效区间左闭右开 [validFrom, validTo)。
 */
public final class DeclarationRules {

    private DeclarationRules() {
    }

    /**
     * 两个批号前缀是否重叠：任一前缀是另一个的前缀即重叠（空前缀与一切重叠）。
     */
    public static boolean prefixOverlap(String a, String b) {
        return a.startsWith(b) || b.startsWith(a);
    }

    /**
     * 时点是否落在声明有效区间内：左闭右开，at == validFrom 有效，at == validTo 失效。
     */
    public static boolean withinValidity(CertificateRepository.DeclarationRow declaration,
                                         Instant at) {
        Instant from = Instant.parse(declaration.validFrom());
        Instant to = Instant.parse(declaration.validTo());
        return !at.isBefore(from) && at.isBefore(to);
    }

    /**
     * 校验声明版本可用于指定物料与批号：未撤回、物料一致、批号前缀匹配、时点在有效区间内。
     * 任一不满足抛 422，错误码可区分原因，消息携带实际值与要求值。
     */
    public static void assertUsable(CertificateRepository.DeclarationRow declaration,
                                    String productCode, String batchNo, String at) {
        if (!DeclarationStatus.ACTIVE.name().equals(declaration.status())) {
            throw ApiException.unprocessable("DECLARATION_WITHDRAWN",
                    "声明版本已撤回: " + declaration.declarationKey() + " v" + declaration.version()
                            + "，撤回时间 " + declaration.withdrawnAt());
        }
        if (!declaration.materialCode().equals(productCode)) {
            throw ApiException.unprocessable("MATERIAL_MISMATCH",
                    "批次物料与声明物料不匹配: 要求 " + declaration.materialCode() + "，实际 "
                            + productCode);
        }
        if (!batchNo.startsWith(declaration.batchNoPrefix())) {
            throw ApiException.unprocessable("BATCH_NO_PREFIX_MISMATCH",
                    "批号不匹配声明允许的前缀: 要求前缀 " + declaration.batchNoPrefix()
                            + "，实际批号 " + batchNo);
        }
        if (!withinValidity(declaration, Instant.parse(at))) {
            throw ApiException.unprocessable("DECLARATION_NOT_EFFECTIVE",
                    "声明在时点 " + at + " 不在有效区间内: 要求 [" + declaration.validFrom() + ", "
                            + declaration.validTo() + ")");
        }
    }
}