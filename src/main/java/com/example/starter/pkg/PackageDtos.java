package com.example.starter.pkg;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

/**
 * 封存包相关传输对象。对应 seal_package/package_item/seal_event 表：
 * 同层位最多 20 件样本，交接锁定清单与封签，破损只追加异常记录，重新封存生成新版本。
 */
public final class PackageDtos {

    private PackageDtos() {
    }

    /** 封存请求：requestId 为幂等键；sampleNos 为包内样本编号列表（1-20 件，须同属指定层位）。 */
    public record SealRequest(@NotBlank String requestId, @NotBlank String packageNo, @NotBlank String siteCode,
            @NotBlank String stratumNo, @NotBlank String sealNo, List<String> sampleNos, @NotBlank String operator) {
    }

    /** 交接请求：requestId 为幂等键；operator/receiver 为合成交接人与接收方。 */
    public record HandoverRequest(@NotBlank String requestId, @NotBlank String operator, @NotBlank String receiver) {
    }

    /** 封签破损上报：仅追加异常记录，note 为异常描述。 */
    public record AnomalyRequest(@NotBlank String requestId, @NotBlank String note, @NotBlank String operator) {
    }

    /** 重新封存请求：基于已破损旧包生成新版本包，newSealNo 通过 packageNo/sealNo 指定。 */
    public record ResealRequest(@NotBlank String requestId, @NotBlank String oldPackageNo, @NotBlank String packageNo,
            @NotBlank String sealNo, @NotBlank String operator) {
    }

    /** 封存包响应：version 从 1 开始；prevAnomalyId 为重新封存引用的旧异常记录，首包为空。 */
    public record PackageResponse(String packageNo, String lineageNo, int version, String sealNo, String status,
            int sampleCount, Long prevAnomalyId) {
    }

    /** 异常上报响应：anomalyId 为追加的异常记录 ID，sealNo 为保留的原封签编号。 */
    public record AnomalyResponse(long anomalyId, String packageNo, String sealNo, String status) {
    }
}