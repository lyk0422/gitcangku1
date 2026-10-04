package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.RequestFingerprint;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 请求指纹单元测试：结构化身份比较避免分隔符碰撞，任一身份字段变化都改变指纹。
 */
class RequestFingerprintTest {

    private final RequestFingerprint fingerprint = new RequestFingerprint(new ObjectMapper());

    @Test
    void sameIdentityProducesSameFingerprint() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("op", "TRAIN_PROFILE");
        first.put("operator", "dispatcher-1");
        first.put("trainNo", "G100");
        first.put("tractionMode", "ELECTRIC");
        first.put("expectedVersion", 0);

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("op", "TRAIN_PROFILE");
        second.put("operator", "dispatcher-1");
        second.put("trainNo", "G100");
        second.put("tractionMode", "ELECTRIC");
        second.put("expectedVersion", 0);

        assertThat(fingerprint.of(first)).isEqualTo(fingerprint.of(second));
    }

    @Test
    void structuredFieldsAvoidSeparatorCollisions() {
        // 朴素分隔符拼接下会碰撞的两组身份：x="p|q",y="r" 与 x="p",y="q|r"
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("op", "OP");
        first.put("x", "p|q");
        first.put("y", "r");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("op", "OP");
        second.put("x", "p");
        second.put("y", "q|r");

        assertThat(fingerprint.of(first)).isNotEqualTo(fingerprint.of(second));
    }

    @Test
    void anyIdentityFieldChangeChangesFingerprint() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("op", "SECTION_PROFILE");
        base.put("operator", "dispatcher-1");
        base.put("sectionId", "SEC-1");
        base.put("electrified", true);
        base.put("dieselAllowed", false);
        base.put("expectedVersion", 1);
        String baseHash = fingerprint.of(base);

        Map<String, Object> otherOperator = new LinkedHashMap<>(base);
        otherOperator.put("operator", "dispatcher-2");
        assertThat(fingerprint.of(otherOperator)).isNotEqualTo(baseHash);

        Map<String, Object> otherResource = new LinkedHashMap<>(base);
        otherResource.put("sectionId", "SEC-2");
        assertThat(fingerprint.of(otherResource)).isNotEqualTo(baseHash);

        Map<String, Object> otherParam = new LinkedHashMap<>(base);
        otherParam.put("electrified", false);
        assertThat(fingerprint.of(otherParam)).isNotEqualTo(baseHash);

        Map<String, Object> otherVersion = new LinkedHashMap<>(base);
        otherVersion.put("expectedVersion", 2);
        assertThat(fingerprint.of(otherVersion)).isNotEqualTo(baseHash);

        Map<String, Object> otherOp = new LinkedHashMap<>(base);
        otherOp.put("op", "TRAIN_PROFILE");
        assertThat(fingerprint.of(otherOp)).isNotEqualTo(baseHash);
    }
}