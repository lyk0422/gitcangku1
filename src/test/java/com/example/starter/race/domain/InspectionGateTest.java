package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InspectionGate} 纯逻辑单元测试：非强制放行、无记录/FAIL/过期三类阻断及有效期含端点。
 */
class InspectionGateTest {

    private record Inspection(InspectionResult result, Long validUntil)
            implements InspectionGate.InspectionView {
    }

    private static final Inspection PASS = new Inspection(InspectionResult.PASS, 1_000L);
    private static final Inspection FAIL = new Inspection(InspectionResult.FAIL, null);

    @Test
    void 非强制赛事一律放行() {
        assertThat(InspectionGate.evaluate(false, null, 500L))
                .isEqualTo(InspectionGate.Verdict.ALLOWED);
        assertThat(InspectionGate.evaluate(false, FAIL, 500L))
                .isEqualTo(InspectionGate.Verdict.ALLOWED);
    }

    @Test
    void 强制赛事无记录返回无检录阻断() {
        assertThat(InspectionGate.evaluate(true, null, 500L))
                .isEqualTo(InspectionGate.Verdict.NO_INSPECTION);
    }

    @Test
    void 强制赛事最近FAIL立即阻断() {
        assertThat(InspectionGate.evaluate(true, FAIL, 500L))
                .isEqualTo(InspectionGate.Verdict.LATEST_FAIL);
    }

    @Test
    void 强制赛事PASS有效期内含端点放行过期阻断() {
        assertThat(InspectionGate.evaluate(true, PASS, 999L))
                .isEqualTo(InspectionGate.Verdict.ALLOWED);
        // 有效期截止时刻含端点：等于 validUntil 仍放行
        assertThat(InspectionGate.evaluate(true, PASS, 1_000L))
                .isEqualTo(InspectionGate.Verdict.ALLOWED);
        assertThat(InspectionGate.evaluate(true, PASS, 1_001L))
                .isEqualTo(InspectionGate.Verdict.PASS_EXPIRED);
    }

    @Test
    void PASS缺少有效期按过期处理() {
        Inspection broken = new Inspection(InspectionResult.PASS, null);
        assertThat(InspectionGate.evaluate(true, broken, 1L))
                .isEqualTo(InspectionGate.Verdict.PASS_EXPIRED);
    }

    @Test
    void 阻断原因均有可读中文描述() {
        assertThat(InspectionGate.reasonOf(InspectionGate.Verdict.NO_INSPECTION)).contains("不存在");
        assertThat(InspectionGate.reasonOf(InspectionGate.Verdict.LATEST_FAIL)).contains("FAIL");
        assertThat(InspectionGate.reasonOf(InspectionGate.Verdict.PASS_EXPIRED)).contains("过期");
    }
}
