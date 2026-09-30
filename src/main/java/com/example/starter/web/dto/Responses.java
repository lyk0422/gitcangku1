package com.example.starter.web.dto;

import com.example.starter.domain.CertificateStatus;
import com.example.starter.domain.TestStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 接口响应体集合。金额/比例类字段均为 0-1 之间四位小数，时间字段为 Asia/Shanghai。
 */
public final class Responses {

    private Responses() {
    }

    /** 批次信息。 */
    public record BatchResponse(
            String batchNo,
            String cropName,
            LocalDateTime createdAt
    ) {
    }

    /** 检测记录信息，ratio 为发芽粒数/样本数。 */
    public record TestResponse(
            String testNo,
            int sampleSize,
            int germinatedCount,
            BigDecimal ratio,
            TestStatus status,
            LocalDateTime testedAt,
            LocalDateTime voidedAt,
            LocalDateTime createdAt
    ) {
    }

    /** 证书中固化的单次检测快照。 */
    public record CertTestSnapshot(
            String testNo,
            int sampleSize,
            int germinatedCount,
            BigDecimal ratio
    ) {
    }

    /** 证书信息；currentCertificate 为 null 表示批次从未成功签发证书。 */
    public record CertificateResponse(
            String certificateNo,
            CertificateStatus status,
            CertTestSnapshot firstTest,
            CertTestSnapshot secondTest,
            LocalDateTime issuedAt,
            String requestId
    ) {
    }

    /** 放行事实；released 为 null 表示批次尚未放行。 */
    public record ReleaseResponse(
            String certificateNo,
            String firstTestNo,
            BigDecimal firstRatio,
            String secondTestNo,
            BigDecimal secondRatio,
            String requestId,
            LocalDateTime releasedAt
    ) {
    }

    /**
     * 按批次查询的完整检测链：批次信息、全部检测记录（含作废）、当前证书与放行状态。
     * currentCertificate 中可直接看到证书依赖的两次检测编号。
     */
    public record BatchChainResponse(
            BatchResponse batch,
            List<TestResponse> tests,
            CertificateResponse currentCertificate,
            ReleaseResponse release
    ) {
    }
}
