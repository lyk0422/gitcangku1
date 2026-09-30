package com.example.starter.plan.service;

import com.example.starter.plan.model.PlatformCapability;
import com.example.starter.plan.model.PowerSupplyType;
import java.util.ArrayList;
import java.util.List;

/**
 * 编组与站台能力适配的纯逻辑评估（无 IO，便于单元测试）。
 *
 * <p>不适配原因代码：
 * <ul>
 *   <li>LENGTH_EXCEEDED：编组长度超过站台长度上限（单位米，恰好相等允许）</li>
 *   <li>POWER_SUPPLY_INCOMPATIBLE：站台不支持编组需求供电制式；编组 NONE（无需求）始终兼容</li>
 *   <li>ACCESSIBLE_UNAVAILABLE：编组有无障碍需求但站台不具备无障碍能力</li>
 * </ul>
 */
public final class PlatformFit {

    /** 编组长度超过站台上限。 */
    public static final String REASON_LENGTH_EXCEEDED = "LENGTH_EXCEEDED";
    /** 供电制式不兼容。 */
    public static final String REASON_POWER_SUPPLY_INCOMPATIBLE = "POWER_SUPPLY_INCOMPATIBLE";
    /** 站台不满足无障碍需求。 */
    public static final String REASON_ACCESSIBLE_UNAVAILABLE = "ACCESSIBLE_UNAVAILABLE";

    private PlatformFit() {
    }

    /**
     * 评估编组三属性对某站台能力版本的适配情况，返回不适配原因代码列表；空列表表示适配。
     *
     * @param capability         站台能力（评估时的当前有效版本）
     * @param consistLengthM     编组长度，单位米
     * @param powerSupplyType    编组需求供电制式
     * @param accessibleRequired 编组是否有无障碍登乘需求
     */
    public static List<String> mismatchReasons(PlatformCapability capability, int consistLengthM,
                                               PowerSupplyType powerSupplyType,
                                               boolean accessibleRequired) {
        List<String> reasons = new ArrayList<>(3);
        if (consistLengthM > capability.maxLengthM()) {
            reasons.add(REASON_LENGTH_EXCEEDED);
        }
        if (powerSupplyType != PowerSupplyType.NONE
                && !capability.supportedPowerTypes().contains(powerSupplyType)) {
            reasons.add(REASON_POWER_SUPPLY_INCOMPATIBLE);
        }
        if (accessibleRequired && !capability.accessible()) {
            reasons.add(REASON_ACCESSIBLE_UNAVAILABLE);
        }
        return reasons;
    }

    /** 三项能力全部满足。 */
    public static boolean isCompatible(PlatformCapability capability, int consistLengthM,
                                       PowerSupplyType powerSupplyType, boolean accessibleRequired) {
        return mismatchReasons(capability, consistLengthM, powerSupplyType, accessibleRequired)
                .isEmpty();
    }
}
