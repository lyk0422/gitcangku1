package com.example.starter.plan.service;

import com.example.starter.plan.model.Platform;
import com.example.starter.plan.web.dto.IncompatibilityView;
import java.util.ArrayList;
import java.util.List;

/**
 * 编组需求与站台能力的适配规则（无副作用纯逻辑，便于单元测试）：
 * 长度不超上限（相等视为适配）、供电制式在兼容集合内、无障碍需求须被满足。
 * 站台不存在单独返回 PLATFORM_NOT_FOUND；原因一次性列全，便于调度员定位。
 */
public final class PlatformCompatibility {

    public static final String REASON_PLATFORM_NOT_FOUND = "PLATFORM_NOT_FOUND";
    public static final String REASON_LENGTH_EXCEEDED = "LENGTH_EXCEEDED";
    public static final String REASON_POWER_INCOMPATIBLE = "POWER_INCOMPATIBLE";
    public static final String REASON_ACCESSIBILITY_UNAVAILABLE = "ACCESSIBILITY_UNAVAILABLE";

    private PlatformCompatibility() {
    }

    /**
     * 评估一项编组需求对某站台当前能力的全部不适配原因；返回空列表表示适配。
     *
     * @param platform             站台当前能力，null 表示站台未登记
     * @param consistLengthMeters  编组长度，单位米
     * @param powerSystem          编组供电制式
     * @param accessibleRequired   是否需要无障碍服务
     */
    public static List<IncompatibilityView> evaluate(Platform platform, int consistLengthMeters,
                                                     String powerSystem,
                                                     boolean accessibleRequired) {
        List<IncompatibilityView> reasons = new ArrayList<>();
        if (platform == null) {
            reasons.add(new IncompatibilityView(REASON_PLATFORM_NOT_FOUND,
                    "站台未登记或当前不可用，无法接纳编组"));
            return reasons;
        }
        if (consistLengthMeters > platform.maxLengthMeters()) {
            reasons.add(new IncompatibilityView(REASON_LENGTH_EXCEEDED,
                    "编组长度 " + consistLengthMeters + " 米超过站台长度上限 "
                            + platform.maxLengthMeters() + " 米"));
        }
        if (!platform.powerSystems().contains(powerSystem)) {
            reasons.add(new IncompatibilityView(REASON_POWER_INCOMPATIBLE,
                    "编组供电制式 " + powerSystem + " 与站台兼容制式 "
                            + platform.powerSystems() + " 不兼容"));
        }
        if (accessibleRequired && !platform.accessible()) {
            reasons.add(new IncompatibilityView(REASON_ACCESSIBILITY_UNAVAILABLE,
                    "编组有无障碍服务需求，但站台不具备无障碍能力"));
        }
        return reasons;
    }
}
