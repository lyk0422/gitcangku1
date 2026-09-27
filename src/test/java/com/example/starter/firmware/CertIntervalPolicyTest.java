package com.example.starter.firmware;

import com.example.starter.firmware.domain.CertChainStatus;
import com.example.starter.firmware.domain.FirmwareCertChain;
import com.example.starter.firmware.service.CertIntervalPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证书链半开区间时态裁决纯单元测试：区间重叠判定与请求时刻唯一当前链。
 */
class CertIntervalPolicyTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-12-01T00:00:00Z");

    @Test
    void 半开区间_端点相接不重叠_交叉与包含重叠() {
        // [T0,T1) 与 [T1,T2)：端点相接，不重叠（轮换允许）
        assertThat(CertIntervalPolicy.overlaps(T0, T1, T1, T2)).isFalse();
        // 端点均不含：[T0,T1) 不覆盖 T1
        assertThat(CertIntervalPolicy.overlaps(T1, T2, T0, T1)).isFalse();
        // 部分交叉
        assertThat(CertIntervalPolicy.overlaps(T0, T2, T1, T3)).isTrue();
        // 包含
        assertThat(CertIntervalPolicy.overlaps(T0, T3, T1, T2)).isTrue();
        // 相离
        assertThat(CertIntervalPolicy.overlaps(T0, T1, T2, T3)).isFalse();
    }

    @Test
    void 开放区间_与未来链相接不重叠_交叉重叠() {
        // [T0,+∞) 与 [T1,+∞)：在 T1 之后重叠
        assertThat(CertIntervalPolicy.overlaps(T0, null, T1, null)).isTrue();
        // 开放旧链恰好在 T1 截断为 [T0,T1) 后，与新链 [T1,+∞) 不重叠
        assertThat(CertIntervalPolicy.overlaps(T0, T1, T1, null)).isFalse();
        // [T0,T2) 与开放链 [T1,+∞)
        assertThat(CertIntervalPolicy.overlaps(T0, T2, T1, null)).isTrue();
    }

    private FirmwareCertChain chain(int version, Instant from, Instant to, CertChainStatus status) {
        return new FirmwareCertChain(version, 100L, version, "CN=sign-" + version, "CN=root",
                "fp" + version, from, to, status,
                status == CertChainStatus.REVOKED ? (to == null ? from : to) : null);
    }

    @Test
    void 当前链_唯一生效_生效前与到期边界为空() {
        List<FirmwareCertChain> chains = List.of(chain(1, T0, T1, CertChainStatus.ACTIVE));
        assertThat(CertIntervalPolicy.currentAt(chains, T0).get().chainVersion()).isEqualTo(1);
        // 生效前一刻
        assertThat(CertIntervalPolicy.currentAt(chains, T0.minusSeconds(1))).isEmpty();
        // 到期时刻本身不属于半开区间
        assertThat(CertIntervalPolicy.currentAt(chains, T1)).isEmpty();
        assertThat(CertIntervalPolicy.currentAt(chains, T1.minusSeconds(1)).get().chainVersion()).isEqualTo(1);
    }

    @Test
    void 当前链_轮换后按请求时刻切换_撤销链不再有效() {
        List<FirmwareCertChain> chains = List.of(
                chain(1, T0, T1, CertChainStatus.ACTIVE),
                chain(2, T1, null, CertChainStatus.ACTIVE));
        assertThat(CertIntervalPolicy.currentAt(chains, T1.minusSeconds(1)).get().chainVersion()).isEqualTo(1);
        // 交接点 T1：旧链已到期、新链生效
        assertThat(CertIntervalPolicy.currentAt(chains, T1).get().chainVersion()).isEqualTo(2);
        assertThat(CertIntervalPolicy.currentAt(chains, T2).get().chainVersion()).isEqualTo(2);

        // 新链撤销后：T1 起无任何有效链
        List<FirmwareCertChain> revoked = List.of(
                chain(1, T0, T1, CertChainStatus.ACTIVE),
                chain(2, T1, null, CertChainStatus.REVOKED));
        assertThat(CertIntervalPolicy.currentAt(revoked, T0).get().chainVersion()).isEqualTo(1);
        assertThat(CertIntervalPolicy.currentAt(revoked, T2)).isEqualTo(Optional.empty());
    }
}
