package com.example.starter.plan.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.model.PlatformCapability;
import com.example.starter.plan.model.PowerSupplyType;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 编组-站台能力适配纯逻辑单元测试：长度边界、供电制式兼容、无障碍需求与组合原因。
 */
class PlatformFitTest {

    private PlatformCapability capability(int maxLengthM, Set<PowerSupplyType> powerTypes,
                                          boolean accessible) {
        return new PlatformCapability("PLT-T", 1, maxLengthM, powerTypes, accessible);
    }

    @Test
    void lengthWithinLimitIsCompatibleAndExactLengthAllowed() {
        PlatformCapability platform = capability(200, Set.of(PowerSupplyType.AC25KV), true);
        assertThat(PlatformFit.isCompatible(platform, 199, PowerSupplyType.AC25KV, false))
                .isTrue();
        // 恰好等于上限允许
        assertThat(PlatformFit.isCompatible(platform, 200, PowerSupplyType.AC25KV, false))
                .isTrue();
    }

    @Test
    void lengthExceededReturnsSpecificReason() {
        PlatformCapability platform = capability(180, Set.of(PowerSupplyType.AC25KV), true);
        // 题干核心场景：200 米编组申请 180 米站台
        assertThat(PlatformFit.mismatchReasons(platform, 200, PowerSupplyType.AC25KV, false))
                .containsExactly(PlatformFit.REASON_LENGTH_EXCEEDED);
        assertThat(PlatformFit.isCompatible(platform, 200, PowerSupplyType.AC25KV, false))
                .isFalse();
    }

    @Test
    void powerSupplyMismatchReturnsSpecificReasonAndNoneAlwaysCompatible() {
        PlatformCapability platform = capability(300, Set.of(PowerSupplyType.AC25KV), true);
        assertThat(PlatformFit.mismatchReasons(platform, 200, PowerSupplyType.DC1500V, false))
                .containsExactly(PlatformFit.REASON_POWER_SUPPLY_INCOMPATIBLE);
        // 编组无供电需求（NONE）时任何站台均兼容
        assertThat(PlatformFit.mismatchReasons(platform, 200, PowerSupplyType.NONE, false))
                .isEmpty();
    }

    @Test
    void accessibleRequirementReturnsSpecificReason() {
        PlatformCapability platform = capability(300, Set.of(PowerSupplyType.AC25KV), false);
        assertThat(PlatformFit.mismatchReasons(platform, 200, PowerSupplyType.AC25KV, true))
                .containsExactly(PlatformFit.REASON_ACCESSIBLE_UNAVAILABLE);
        // 编组无无障碍需求时，不具备无障碍能力的站台也可用
        assertThat(PlatformFit.mismatchReasons(platform, 200, PowerSupplyType.AC25KV, false))
                .isEmpty();
    }

    @Test
    void multipleMismatchesAreAllReported() {
        PlatformCapability platform = capability(180, Set.of(PowerSupplyType.AC25KV), false);
        assertThat(PlatformFit.mismatchReasons(platform, 200, PowerSupplyType.DC3000V, true))
                .containsExactly(
                        PlatformFit.REASON_LENGTH_EXCEEDED,
                        PlatformFit.REASON_POWER_SUPPLY_INCOMPATIBLE,
                        PlatformFit.REASON_ACCESSIBLE_UNAVAILABLE);
    }
}
