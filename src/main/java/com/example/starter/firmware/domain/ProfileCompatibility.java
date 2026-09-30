package com.example.starter.firmware.domain;

import java.util.List;
import java.util.Set;

/**
 * 硬件画像兼容门禁：型号匹配、内存达标且包含全部必需标签才兼容。
 * 设备缺少画像或画像字段时按不兼容处理并给出原因；评估为纯函数，不依赖数据库。
 */
public final class ProfileCompatibility {

    private ProfileCompatibility() {
    }

    /**
     * 评估画像是否满足版本兼容声明。
     *
     * @param version 固件版本兼容声明
     * @param profile 设备画像，null 表示设备无画像
     * @return 兼容返回 null；不兼容返回中文原因
     */
    public static String incompatibilityReason(FirmwareVersion version, DeviceProfile profile) {
        if (profile == null) {
            return "设备无画像";
        }
        if (profile.model() == null || profile.model().isBlank()) {
            return "画像缺少型号字段";
        }
        if (!version.models().contains(profile.model())) {
            return "型号不匹配: " + profile.model();
        }
        if (profile.memoryMb() == null) {
            return "画像缺少内存字段";
        }
        if (profile.memoryMb() < version.minMemoryMb()) {
            return "内存不足: " + profile.memoryMb() + "MB < " + version.minMemoryMb() + "MB";
        }
        if (profile.tags() == null) {
            return "画像缺少能力标签字段";
        }
        List<String> missing = version.requiredTags().stream()
                .filter(tag -> !profile.tags().contains(tag))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            return "缺少必需标签: " + String.join(",", missing);
        }
        return null;
    }

    /**
     * 归一化标签/型号集合：去空白、去空项、去重，保持输入顺序。
     */
    public static Set<String> normalizeSet(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /**
     * 集合序列化为逗号分隔串；空集合序列化为空串。
     */
    public static String join(Set<String> values) {
        return String.join(",", values);
    }

    /**
     * 逗号分隔串反序列化为集合；null 或空串得到空集合。
     */
    public static Set<String> split(String csv) {
        if (csv == null || csv.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(java.util.Arrays.asList(csv.split(",")));
    }
}
