package com.example.starter.firmware.domain;

/**
 * 单次硬件画像兼容门禁判定结果，基于设备当次冻结画像与版本声明要求计算。
 *
 * @param compatible     是否兼容（型号命中、内存达标且包含全部必需标签）
 * @param reason         不兼容原因；兼容时为 null
 * @param profile        判定所用画像版本；设备无画像时为 null
 */
public record GateDecision(boolean compatible, ExclusionReason reason, ProfileSnapshot profile) {

    public static GateDecision pass(ProfileSnapshot profile) {
        return new GateDecision(true, null, profile);
    }

    public static GateDecision reject(ExclusionReason reason, ProfileSnapshot profile) {
        return new GateDecision(false, reason, profile);
    }
}
