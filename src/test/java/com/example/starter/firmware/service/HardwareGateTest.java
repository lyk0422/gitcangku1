package com.example.starter.firmware.service;

import com.example.starter.firmware.domain.ExclusionReason;
import com.example.starter.firmware.domain.GateDecision;
import com.example.starter.firmware.domain.HardwareRequirement;
import com.example.starter.firmware.domain.ProfileSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 硬件画像兼容门禁纯函数单元测试：型号、内存（单位MB）、标签三条件及画像/字段缺失原因、内存边界。
 */
class HardwareGateTest {

    private final HardwareRequirement requirement = new HardwareRequirement(
            1, "V", 4096, List.of("M", "M2"), List.of("BLE"));

    private ProfileSnapshot profile(Integer id, String model, Integer memoryMb, List<String> tags) {
        return new ProfileSnapshot(id == null ? -1 : id, "D", id == null ? 1 : id, model, memoryMb, tags);
    }

    @Test
    void 全部满足_兼容通过() {
        GateDecision decision = HardwareGate.evaluate(requirement,
                profile(1, "M", 4096, List.of("BLE")));
        assertThat(decision.compatible()).isTrue();
        assertThat(decision.reason()).isNull();
        assertThat(decision.profile().version()).isEqualTo(1);
    }

    @Test
    void 型号命中集合第二个型号也算兼容() {
        GateDecision decision = HardwareGate.evaluate(requirement,
                profile(1, "M2", 8192, List.of("BLE", "WIFI")));
        assertThat(decision.compatible()).isTrue();
    }

    @Test
    void 内存恰好等于最低值_边界达标() {
        GateDecision decision = HardwareGate.evaluate(requirement,
                profile(1, "M", 4096, List.of("BLE")));
        assertThat(decision.compatible()).isTrue();
    }

    @Test
    void 内存低于最低值_不兼容_MEMORY_INSUFFICIENT() {
        GateDecision decision = HardwareGate.evaluate(requirement,
                profile(1, "M", 4095, List.of("BLE")));
        assertThat(decision.compatible()).isFalse();
        assertThat(decision.reason()).isEqualTo(ExclusionReason.MEMORY_INSUFFICIENT);
        assertThat(decision.profile().version()).isEqualTo(1);
    }

    @Test
    void 无BLE标签_不兼容_REQUIRED_TAG_MISSING() {
        GateDecision decision = HardwareGate.evaluate(requirement,
                profile(1, "M", 4096, List.of("WIFI")));
        assertThat(decision.compatible()).isFalse();
        assertThat(decision.reason()).isEqualTo(ExclusionReason.REQUIRED_TAG_MISSING);
    }

    @Test
    void 型号不在声明集合_不兼容_MODEL_NOT_REQUIRED() {
        GateDecision decision = HardwareGate.evaluate(requirement,
                profile(1, "X", 8192, List.of("BLE")));
        assertThat(decision.compatible()).isFalse();
        assertThat(decision.reason()).isEqualTo(ExclusionReason.MODEL_NOT_REQUIRED);
    }

    @Test
    void 画像缺型号_不兼容_MODEL_MISSING() {
        GateDecision decision = HardwareGate.evaluate(requirement,
                profile(1, null, 4096, List.of("BLE")));
        assertThat(decision.compatible()).isFalse();
        assertThat(decision.reason()).isEqualTo(ExclusionReason.MODEL_MISSING);
    }

    @Test
    void 画像缺内存_不兼容_MEMORY_MISSING() {
        GateDecision decision = HardwareGate.evaluate(requirement,
                profile(1, "M", null, List.of("BLE")));
        assertThat(decision.compatible()).isFalse();
        assertThat(decision.reason()).isEqualTo(ExclusionReason.MEMORY_MISSING);
    }

    @Test
    void 设备无画像_不兼容_PROFILE_MISSING且画像版本为空() {
        GateDecision decision = HardwareGate.evaluate(requirement, null);
        assertThat(decision.compatible()).isFalse();
        assertThat(decision.reason()).isEqualTo(ExclusionReason.PROFILE_MISSING);
        assertThat(decision.profile()).isNull();
    }

    @Test
    void 型号空白也按缺型号处理() {
        GateDecision decision = HardwareGate.evaluate(requirement,
                profile(1, "  ", 4096, List.of("BLE")));
        assertThat(decision.reason()).isEqualTo(ExclusionReason.MODEL_MISSING);
    }

    @Test
    void 要求不限制内存且不要求标签时_型号命中即兼容_缺内存也通过() {
        HardwareRequirement loose = new HardwareRequirement(2, "V2", null, List.of("M"), List.of());
        GateDecision decision = HardwareGate.evaluate(loose, profile(1, "M", null, List.of()));
        assertThat(decision.compatible()).isTrue();
    }

    @Test
    void 多个必需标签须全部包含_缺一不可() {
        HardwareRequirement multi = new HardwareRequirement(3, "V3", null,
                List.of("M"), List.of("BLE", "NFC"));
        assertThat(HardwareGate.evaluate(multi, profile(1, "M", 4096, List.of("BLE", "NFC")))
                .compatible()).isTrue();
        assertThat(HardwareGate.evaluate(multi, profile(1, "M", 4096, List.of("BLE"))).reason())
                .isEqualTo(ExclusionReason.REQUIRED_TAG_MISSING);
    }
}
