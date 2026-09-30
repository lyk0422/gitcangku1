package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.model.Platform;
import com.example.starter.plan.service.PlatformCompatibility;
import com.example.starter.plan.web.dto.IncompatibilityView;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 站台适配规则纯单元测试：长度边界（相等适配）、供电兼容、无障碍需求、
 * 站台缺失与多原因一次性返回。不依赖 Spring 与数据库。
 */
class PlatformCompatibilityTest {

    private Platform platform(int maxLength, List<String> powerSystems, boolean accessible) {
        return new Platform("PLT-T", "测试站台", maxLength, powerSystems, accessible, 1, 0L, 0L);
    }

    @Test
    void lengthWithinLimitIncludingEqualIsCompatible() {
        Platform p = platform(200, List.of("AC25KV"), true);
        assertThat(PlatformCompatibility.evaluate(p, 200, "AC25KV", false)).isEmpty();
        assertThat(PlatformCompatibility.evaluate(p, 199, "AC25KV", false)).isEmpty();
    }

    @Test
    void lengthExceededReturnsReasonWithMeters() {
        Platform p = platform(180, List.of("AC25KV"), true);
        List<IncompatibilityView> reasons =
                PlatformCompatibility.evaluate(p, 200, "AC25KV", false);
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0).reasonCode()).isEqualTo("LENGTH_EXCEEDED");
        assertThat(reasons.get(0).message()).contains("200").contains("180");
    }

    @Test
    void powerMismatchReturnsIncompatibleReason() {
        Platform p = platform(220, List.of("AC25KV"), true);
        List<IncompatibilityView> reasons =
                PlatformCompatibility.evaluate(p, 200, "DC1500V", false);
        assertThat(reasons).extracting(IncompatibilityView::reasonCode)
                .containsExactly("POWER_INCOMPATIBLE");
    }

    @Test
    void platformSupportingOneOfMultiplePowerSystemsIsCompatible() {
        Platform p = platform(220, List.of("AC25KV", "DC1500V"), true);
        assertThat(PlatformCompatibility.evaluate(p, 200, "DC1500V", false)).isEmpty();
    }

    @Test
    void accessibleRequiredOnInaccessiblePlatformFails() {
        Platform p = platform(220, List.of("AC25KV"), false);
        List<IncompatibilityView> need = PlatformCompatibility.evaluate(p, 200, "AC25KV", true);
        assertThat(need).extracting(IncompatibilityView::reasonCode)
                .containsExactly("ACCESSIBILITY_UNAVAILABLE");
        // 不提出无障碍需求时不要求该能力
        assertThat(PlatformCompatibility.evaluate(p, 200, "AC25KV", false)).isEmpty();
    }

    @Test
    void allReasonsReturnedTogetherAndMissingPlatformIsNotFound() {
        Platform p = platform(180, List.of("AC25KV"), false);
        List<IncompatibilityView> reasons =
                PlatformCompatibility.evaluate(p, 200, "DC1500V", true);
        assertThat(reasons).extracting(IncompatibilityView::reasonCode)
                .containsExactly("LENGTH_EXCEEDED", "POWER_INCOMPATIBLE",
                        "ACCESSIBILITY_UNAVAILABLE");

        List<IncompatibilityView> missing =
                PlatformCompatibility.evaluate(null, 200, "AC25KV", true);
        assertThat(missing).extracting(IncompatibilityView::reasonCode)
                .containsExactly("PLATFORM_NOT_FOUND");
    }
}
