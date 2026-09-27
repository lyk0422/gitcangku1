package com.example.starter.firmware.service;

import com.example.starter.firmware.domain.FirmwareCertChain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 证书链时态裁决纯逻辑（无 IO，便于单元测试）。
 * 区间半开 [from, to)，to 为 null 表示正无穷。同一固件任意时刻至多一条 ACTIVE 链覆盖该时刻。
 */
public final class CertIntervalPolicy {

    private CertIntervalPolicy() {
    }

    /**
     * 两个半开区间是否重叠：[f1,t1) 与 [f2,t2)，null 端点视为正无穷。
     */
    public static boolean overlaps(Instant from1, Instant to1, Instant from2, Instant to2) {
        boolean firstBeforeSecondEnds = to2 == null || from1.isBefore(to2);
        boolean secondBeforeFirstEnds = to1 == null || from2.isBefore(to1);
        return firstBeforeSecondEnds && secondBeforeFirstEnds;
    }

    /**
     * 请求时刻唯一有效的链：状态 ACTIVE 且时刻落在半开区间内。
     * 若多于一条（不应发生，登记/轮换均已拒绝重叠），按链版本最大者裁决并保证不抛空。
     */
    public static Optional<FirmwareCertChain> currentAt(List<FirmwareCertChain> chains, Instant at) {
        return chains.stream()
                .filter(chain -> chain.status().name().equals("ACTIVE") && chain.covers(at))
                .max((a, b) -> Integer.compare(a.chainVersion(), b.chainVersion()));
    }
}
