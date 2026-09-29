package com.example.starter.calibration;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.starter.calibration.service.Fingerprints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 请求指纹纯单元测试：操作者/操作类型/规范化集合参与指纹，集合顺序无关，
 * 异参指纹不同，字段值中的分隔字符不会因手工拼接造成碰撞。
 */
class FingerprintsTest {

    @Test
    void sameFieldsProduceSameFingerprint() {
        Map<String, Object> a = Map.of("k", "M-1", "v", "0.1");
        Map<String, Object> b = Map.of("v", "0.1", "k", "M-1");
        assertEquals(Fingerprints.of("alice", "UPDATE_BUDGET", a),
                Fingerprints.of("alice", "UPDATE_BUDGET", b));
    }

    @Test
    void collectionOrderIsNormalized() {
        String f1 = Fingerprints.of("carol", "RELEASE_BUDGET",
                Map.of("keys", List.of("M-1", "M-2")));
        String f2 = Fingerprints.of("carol", "RELEASE_BUDGET",
                Map.of("keys", List.of("M-2", "M-1")));
        assertEquals(f1, f2);
    }

    @Test
    void differentActorOperationOrFieldsProduceDifferentFingerprint() {
        Map<String, Object> fields = Map.of("k", "M-1");
        String base = Fingerprints.of("alice", "UPDATE_BUDGET", fields);
        assertNotEquals(base, Fingerprints.of("bob", "UPDATE_BUDGET", fields));
        assertNotEquals(base, Fingerprints.of("alice", "REVISE_MEASUREMENT", fields));
        assertNotEquals(base, Fingerprints.of("alice", "UPDATE_BUDGET", Map.of("k", "M-2")));
    }

    @Test
    void separatorCharactersInValuesDoNotCollide() {
        // 未转义分隔符拼接会令 ("a","b,c") 与 ("a,b","c") 碰撞；规范化 JSON 不会
        String f1 = Fingerprints.of("op", "OP", Map.of("x", List.of("a", "b,c")));
        String f2 = Fingerprints.of("op", "OP", Map.of("x", List.of("a,b", "c")));
        assertNotEquals(f1, f2);
    }

    @Test
    void fingerprintIs64HexCharacters() {
        String fp = Fingerprints.of("a", "B", Map.of());
        assertEquals(64, fp.length());
        assertEquals(fp.toLowerCase(), fp);
        assertTrueHex(fp);
    }

    private static void assertTrueHex(String value) {
        for (char c : value.toCharArray()) {
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            org.junit.jupiter.api.Assertions.assertTrue(hex, "指纹必须为小写十六进制: " + c);
        }
    }
}
