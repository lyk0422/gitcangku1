package com.example.starter.firmware.api;

import com.example.starter.firmware.repo.CertRejectionRepository;

/**
 * 证书域拒绝记录视图。
 */
public record CertRejectionView(long id, String operation, String firmwareVersion, String chainVersion,
                                String deviceId, String reasonCode, String detail, String rejectedAtUtc) {

    public static CertRejectionView of(CertRejectionRepository.RejectionRow row) {
        return new CertRejectionView(row.id(), row.operation(), row.firmwareVersion(), row.chainVersion(),
                row.deviceId(), row.reasonCode(), row.detail(), row.rejectedAtUtc());
    }
}
