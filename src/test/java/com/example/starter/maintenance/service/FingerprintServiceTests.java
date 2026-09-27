package com.example.starter.maintenance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import java.util.List;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.example.starter.maintenance.api.dto.LockPointSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 请求指纹纯单元测试：结构化规范化 JSON + SHA-256，字段/操作者/集合差异可区分，
 * 集合顺序不影响指纹（规范化），字段内容无法通过分隔符伪造碰撞。
 */
class FingerprintServiceTests {

    private final FingerprintService fingerprints =
            new FingerprintService(new ObjectMapper().registerModule(new JavaTimeModule()));

    @Test
    void sameFieldsDifferentInsertOrder_sameFingerprint() {
        var a = new TreeMap<String, Object>();
        a.put("operation", "CREATE_ISOLATION");
        a.put("operatorId", "alice");
        var b = new TreeMap<String, Object>();
        b.put("operatorId", "alice");
        b.put("operation", "CREATE_ISOLATION");
        assertEquals(fingerprints.sha256(a), fingerprints.sha256(b));
    }

    @Test
    void operatorOrResultFieldDifference_distinctFingerprint() {
        var base = new TreeMap<String, Object>();
        base.put("operation", "CONFIRM_LOCK_POINT");
        base.put("operatorId", "alice");
        base.put("lockRole", "ISOLATOR");
        var otherOperator = new TreeMap<>(base);
        otherOperator.put("operatorId", "bob");
        var otherRole = new TreeMap<>(base);
        otherRole.put("lockRole", "VERIFIER");
        assertNotEquals(fingerprints.sha256(base), fingerprints.sha256(otherOperator));
        assertNotEquals(fingerprints.sha256(base), fingerprints.sha256(otherRole));
    }

    @Test
    void normalizedLockPoints_orderInsensitiveContentSensitive() {
        List<LockPointSpec> one = List.of(
                new LockPointSpec("L1", "电"), new LockPointSpec("L2", "气"));
        List<LockPointSpec> reordered = List.of(
                new LockPointSpec("L2", "气"), new LockPointSpec("L1", "电"));
        List<LockPointSpec> changedDesc = List.of(
                new LockPointSpec("L1", "电"), new LockPointSpec("L2", "水"));
        var fp1 = new TreeMap<String, Object>();
        fp1.put("operation", "CREATE_ISOLATION");
        fp1.put("lockPoints", fingerprints.normalizeLockPoints(one));
        var fp2 = new TreeMap<String, Object>();
        fp2.put("operation", "CREATE_ISOLATION");
        fp2.put("lockPoints", fingerprints.normalizeLockPoints(reordered));
        var fp3 = new TreeMap<String, Object>();
        fp3.put("operation", "CREATE_ISOLATION");
        fp3.put("lockPoints", fingerprints.normalizeLockPoints(changedDesc));
        // 规范化集合：提交顺序不同不影响指纹
        assertEquals(fingerprints.sha256(fp1), fingerprints.sha256(fp2));
        // 内容（描述）不同指纹不同
        assertNotEquals(fingerprints.sha256(fp1), fingerprints.sha256(fp3));
        // 规范化输出按标识升序
        assertEquals("L1", ((java.util.Map<?, ?>) fingerprints.normalizeLockPoints(reordered).get(0))
                .get("lockPointId"));
    }

    @Test
    void separatorInjectionCannotForgeFields() {
        // 攻击者在操作者中拼入结构化分隔符，也无法等价于另一个字段布局
        var forged = new TreeMap<String, Object>();
        forged.put("operation", "CREATE_ISOLATION");
        forged.put("operatorId", "alice\",\"lockRole\":\"ISOLATOR\",\"x\":\"");
        forged.put("lockRole", "VERIFIER");
        var legit = new TreeMap<String, Object>();
        legit.put("operation", "CREATE_ISOLATION");
        legit.put("operatorId", "alice");
        legit.put("lockRole", "ISOLATOR");
        assertNotEquals(fingerprints.sha256(forged), fingerprints.sha256(legit));
    }

    @Test
    void instantFieldsSupportedAndHexShape() {
        var fields = new TreeMap<String, Object>();
        fields.put("operation", "ADD_READING");
        fields.put("sampledAt", Instant.parse("2026-01-01T10:00:00Z"));
        String digest = fingerprints.sha256(fields);
        assertEquals(64, digest.length());
        assertEquals(digest.toLowerCase(), digest);
        assertTrue(digest.matches("[0-9a-f]{64}"));
        // 时刻不同指纹不同
        var later = new TreeMap<>(fields);
        later.put("sampledAt", Instant.parse("2026-01-01T10:00:01Z"));
        assertNotEquals(digest, fingerprints.sha256(later));
    }

    @Test
    void jsonArrayIsSortedPayload() {
        assertEquals("[\"L1\",\"L2\"]", fingerprints.toJsonArray(List.of("L1", "L2")));
    }

    private static void assertTrue(boolean condition) {
        org.junit.jupiter.api.Assertions.assertTrue(condition);
    }
}
