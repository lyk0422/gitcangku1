package com.example.starter.firmware.domain;

import java.time.Instant;
import java.util.List;

/**
 * 固件签名证书链。同一固件版本任意时刻至多一条未撤销且区间覆盖当前时刻的有效链；
 * 有效区间为半开区间 [validFromUtc, validToUtc)，validToUtc 为 null 表示长期有效。
 *
 * @param id              证书链ID
 * @param firmwareVersion 目标固件版本
 * @param chainVersion    证书链版本，同固件版本内唯一
 * @param validFromUtc    生效时刻（UTC，含）
 * @param validToUtc      失效时刻（UTC，不含），null 表示长期有效
 * @param deviceSet       信任该链的设备ID集合，空集合表示全部设备信任
 * @param operator        登记/轮换操作者
 * @param revoked         是否已撤销
 * @param revokedAtUtc    撤销时刻（UTC），未撤销为 null
 */
public record CertChain(long id, String firmwareVersion, String chainVersion,
                        Instant validFromUtc, Instant validToUtc, List<String> deviceSet,
                        String operator, boolean revoked, Instant revokedAtUtc) {

    /**
     * 判断该链在指定时刻是否有效：未撤销且时刻落在半开区间 [validFromUtc, validToUtc) 内。
     */
    public boolean validAt(Instant instant) {
        if (revoked || instant.isBefore(validFromUtc)) {
            return false;
        }
        return validToUtc == null || instant.isBefore(validToUtc);
    }

    /**
     * 判断设备是否信任该链：空设备集合表示全部设备信任。
     */
    public boolean trustedBy(String deviceId) {
        return deviceSet.isEmpty() || deviceSet.contains(deviceId);
    }
}
