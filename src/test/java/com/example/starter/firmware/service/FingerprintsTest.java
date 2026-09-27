package com.example.starter.firmware.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等指纹纯单元测试：结构化 JSON + SHA-256，键排序稳定，且杜绝裸分隔符拼接碰撞。
 */
class FingerprintsTest {

    private final Fingerprints fingerprints = new Fingerprints(new ObjectMapper());

    @Test
    void 相同结构指纹一致_键顺序无关() {
        TreeMap<String, Object> a = fingerprints.builder("op");
        a.put("operator", "alice");
        a.put("deviceId", "d1");
        TreeMap<String, Object> b = fingerprints.builder("op");
        b.put("deviceId", "d1");
        b.put("operator", "alice");
        assertThat(fingerprints.canonical(a)).isEqualTo(fingerprints.canonical(b));
    }

    @Test
    void 不同操作者或操作类型指纹不同() {
        TreeMap<String, Object> alice = fingerprints.builder("release.create");
        alice.put("operator", "alice");
        TreeMap<String, Object> bob = fingerprints.builder("release.create");
        bob.put("operator", "bob");
        TreeMap<String, Object> otherOp = fingerprints.builder("release.cancel");
        otherOp.put("operator", "alice");
        String h1 = fingerprints.canonical(alice);
        assertThat(h1).isNotEqualTo(fingerprints.canonical(bob));
        assertThat(h1).isNotEqualTo(fingerprints.canonical(otherOp));
        assertThat(h1).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void 分隔符注入不碰撞_集合边界由JSON结构承载() {
        // 裸拼接 "a" + "|" + "b|c" 与 "a|b" + "|" + "c" 会碰撞；结构化 JSON 不会
        TreeMap<String, Object> first = fingerprints.builder("op");
        first.put("groups", List.of(
                sorted("a", "b|c")));
        TreeMap<String, Object> second = fingerprints.builder("op");
        second.put("groups", List.of(
                sorted("a|b", "c")));
        assertThat(fingerprints.canonical(first)).isNotEqualTo(fingerprints.canonical(second));
    }

    @Test
    void 规范化集合排序后指纹稳定() {
        TreeMap<String, Object> unsorted = fingerprints.builder("device.tags.batch");
        unsorted.put("operator", "alice");
        unsorted.put("tags", List.of("c", "a", "b"));
        TreeMap<String, Object> sortedView = fingerprints.builder("device.tags.batch");
        sortedView.put("operator", "alice");
        sortedView.put("tags", List.of("a", "b", "c"));
        assertThat(fingerprints.canonical(unsorted)).isNotEqualTo(fingerprints.canonical(sortedView));
        // 调用方负责先规范化排序：排序后相同
        unsorted.put("tags", List.of("a", "b", "c"));
        assertThat(fingerprints.canonical(unsorted)).isEqualTo(fingerprints.canonical(sortedView));
    }

    private static TreeMap<String, Object> sorted(String... tags) {
        TreeMap<String, Object> group = new TreeMap<>();
        group.put("tags", List.of(tags));
        return group;
    }
}
