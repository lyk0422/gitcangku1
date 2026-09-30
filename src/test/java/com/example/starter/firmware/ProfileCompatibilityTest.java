package com.example.starter.firmware;

import com.example.starter.firmware.domain.DeviceProfile;
import com.example.starter.firmware.domain.FirmwareVersion;
import com.example.starter.firmware.domain.ProfileCompatibility;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 硬件画像兼容门禁纯函数测试：型号、内存、必需标签与缺字段原因，不依赖数据库。
 */
class ProfileCompatibilityTest {

    private static final FirmwareVersion VERSION = new FirmwareVersion(1, "V",
            Set.of("M"), 4096, Set.of("BLE"));

    @Test
    void 全部满足时兼容() {
        DeviceProfile profile = new DeviceProfile("d1", "M", 4096, Set.of("BLE"), 1);
        assertThat(ProfileCompatibility.incompatibilityReason(VERSION, profile)).isNull();
    }

    @Test
    void 内存与标签超出要求时仍兼容() {
        DeviceProfile profile = new DeviceProfile("d1", "M", 8192, Set.of("BLE", "WIFI"), 1);
        assertThat(ProfileCompatibility.incompatibilityReason(VERSION, profile)).isNull();
    }

    @Test
    void 无画像按不兼容处理() {
        assertThat(ProfileCompatibility.incompatibilityReason(VERSION, null)).isEqualTo("设备无画像");
    }

    @Test
    void 缺少型号字段按不兼容处理() {
        DeviceProfile profile = new DeviceProfile("d1", null, 4096, Set.of("BLE"), 1);
        assertThat(ProfileCompatibility.incompatibilityReason(VERSION, profile))
                .isEqualTo("画像缺少型号字段");
    }

    @Test
    void 型号不匹配给出原因() {
        DeviceProfile profile = new DeviceProfile("d1", "X", 4096, Set.of("BLE"), 1);
        assertThat(ProfileCompatibility.incompatibilityReason(VERSION, profile))
                .isEqualTo("型号不匹配: X");
    }

    @Test
    void 缺少内存字段按不兼容处理() {
        DeviceProfile profile = new DeviceProfile("d1", "M", null, Set.of("BLE"), 1);
        assertThat(ProfileCompatibility.incompatibilityReason(VERSION, profile))
                .isEqualTo("画像缺少内存字段");
    }

    @Test
    void 内存不足给出差额原因() {
        DeviceProfile profile = new DeviceProfile("d1", "M", 2048, Set.of("BLE"), 1);
        assertThat(ProfileCompatibility.incompatibilityReason(VERSION, profile))
                .isEqualTo("内存不足: 2048MB < 4096MB");
    }

    @Test
    void 缺少能力标签字段按不兼容处理() {
        DeviceProfile profile = new DeviceProfile("d1", "M", 4096, null, 1);
        assertThat(ProfileCompatibility.incompatibilityReason(VERSION, profile))
                .isEqualTo("画像缺少能力标签字段");
    }

    @Test
    void 缺少必需标签给出缺失清单() {
        FirmwareVersion multi = new FirmwareVersion(2, "V2", Set.of("M"), 4096, Set.of("BLE", "NFC"));
        DeviceProfile profile = new DeviceProfile("d1", "M", 4096, Set.of(), 1);
        assertThat(ProfileCompatibility.incompatibilityReason(multi, profile))
                .isEqualTo("缺少必需标签: BLE,NFC");
    }

    @Test
    void 合成验收场景_型号内存达标但无BLE不兼容() {
        DeviceProfile profile = new DeviceProfile("D", "M", 4096, Set.of(), 1);
        assertThat(ProfileCompatibility.incompatibilityReason(VERSION, profile))
                .isEqualTo("缺少必需标签: BLE");
    }

    @Test
    void 集合归一化_去空白去空项去重() {
        assertThat(ProfileCompatibility.normalizeSet(List.of(" BLE ", "", "BLE", "NFC")))
                .containsExactly("BLE", "NFC");
        assertThat(ProfileCompatibility.normalizeSet(null)).isEmpty();
    }

    @Test
    void 序列化与反序列化互逆() {
        assertThat(ProfileCompatibility.split(ProfileCompatibility.join(Set.of("BLE", "NFC"))))
                .containsExactlyInAnyOrder("BLE", "NFC");
        assertThat(ProfileCompatibility.split("")).isEmpty();
        assertThat(ProfileCompatibility.split(null)).isEmpty();
    }
}
