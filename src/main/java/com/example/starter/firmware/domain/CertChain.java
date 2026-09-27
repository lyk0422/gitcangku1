package com.example.starter.firmware.domain;

/**
 * 固件签名证书链。同一固件版本任意时刻至多一条有效链（有效区间不重叠）；
 * 请求时刻唯一有效且未撤销的链即当前链。
 *
 * @param id              证书链ID
 * @param firmwareVersion 固件版本（发布单目标版本）
 * @param chainVersion    证书链版本，同固件版本内唯一
 * @param validFromUtc    生效时刻，UTC，ISO-8601，含该时刻
 * @param validToUtc      失效时刻，UTC，ISO-8601，不含该时刻
 * @param operator        登记操作者
 * @param revokedAtUtc    撤销时刻，UTC，ISO-8601；null 表示未撤销
 */
public record CertChain(long id, String firmwareVersion, String chainVersion,
                        String validFromUtc, String validToUtc, String operator,
                        String revokedAtUtc) {

    /**
     * 该链在指定时刻是否处于有效区间内（不判断撤销）。
     */
    public boolean covers(java.time.Instant moment) {
        return !java.time.Instant.parse(validFromUtc).isAfter(moment)
                && java.time.Instant.parse(validToUtc).isAfter(moment);
    }
}
