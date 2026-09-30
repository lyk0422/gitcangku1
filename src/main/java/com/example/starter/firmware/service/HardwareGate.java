package com.example.starter.firmware.service;

import com.example.starter.firmware.domain.ExclusionReason;
import com.example.starter.firmware.domain.GateDecision;
import com.example.starter.firmware.domain.HardwareRequirement;
import com.example.starter.firmware.domain.ProfileSnapshot;

import java.util.HashSet;
import java.util.Set;

/**
 * 硬件画像兼容门禁评估（无状态纯函数）：基于设备当次冻结画像与版本声明要求判定。
 * 兼容条件为画像型号命中声明集合、内存达标（单位MB）且包含全部必需能力标签；
 * 画像缺失或画像字段缺失分别给出原因，不兼容设备不创建任务、不计失败率。
 */
public final class HardwareGate {

    private HardwareGate() {
    }

    public static GateDecision evaluate(HardwareRequirement requirement, ProfileSnapshot profile) {
        if (profile == null) {
            return GateDecision.reject(ExclusionReason.PROFILE_MISSING, null);
        }
        if (profile.model() == null || profile.model().isBlank()) {
            return GateDecision.reject(ExclusionReason.MODEL_MISSING, profile);
        }
        if (!requirement.models().contains(profile.model())) {
            return GateDecision.reject(ExclusionReason.MODEL_NOT_REQUIRED, profile);
        }
        // 仅当版本声明了最低内存时，画像缺内存或内存不足才不兼容；未声明内存要求则不校验
        if (requirement.minMemoryMb() != null) {
            if (profile.memoryMb() == null) {
                return GateDecision.reject(ExclusionReason.MEMORY_MISSING, profile);
            }
            if (profile.memoryMb() < requirement.minMemoryMb()) {
                return GateDecision.reject(ExclusionReason.MEMORY_INSUFFICIENT, profile);
            }
        }
        Set<String> deviceTags = new HashSet<>(profile.tags());
        for (String required : requirement.tags()) {
            if (!deviceTags.contains(required)) {
                return GateDecision.reject(ExclusionReason.REQUIRED_TAG_MISSING, profile);
            }
        }
        return GateDecision.pass(profile);
    }
}
