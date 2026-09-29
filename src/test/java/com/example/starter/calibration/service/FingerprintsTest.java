package com.example.starter.calibration.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 请求指纹单元测试：规范化 JSON + SHA-256 的稳定性、集合顺序无关性、
 * 字段/数值/操作者差异可区分，以及避免裸分隔符拼接歧义（注入分隔符不产生碰撞）。
 */
class FingerprintsTest {

    private Map<String, Object> budgetFingerprint(String operator, String key,
                                                  Long expectedVersion,
                                                  List<String> nameValuePairs) {
        Map<String, Object> body = Fingerprints.ordered();
        body.put("operationType", BudgetService.OPERATION);
        body.put("operator", operator);
        body.put("measurementKey", key);
        body.put("expectedVersion", expectedVersion);
        List<Map<String, Object>> components = nameValuePairs.stream()
                .sorted()
                .map(pair -> {
                    String[] nv = pair.split("=", 2);
                    Map<String, Object> item = Fingerprints.ordered();
                    item.put("name", nv[0]);
                    item.put("value", nv[1]);
                    return item;
                })
                .toList();
        body.put("components", components);
        return body;
    }

    @Test
    void sameStructureProducesSameFingerprint() {
        Map<String, Object> a = budgetFingerprint("alice", "M-1", 1L, List.of("a=1", "b=2"));
        Map<String, Object> b = budgetFingerprint("alice", "M-1", 1L, List.of("a=1", "b=2"));
        assertEquals(Fingerprints.sha256(a), Fingerprints.sha256(b));
    }

    @Test
    void collectionOrderIsNormalizedAway() {
        // 分量按名称排序后哈希，提交顺序不同但集合相同 → 同指纹（幂等同参重放）
        Map<String, Object> orderedA = budgetFingerprint("alice", "M-1", 1L,
                List.of("a=1", "b=2", "c=3"));
        Map<String, Object> orderedB = budgetFingerprint("alice", "M-1", 1L,
                List.of("c=3", "a=1", "b=2"));
        assertEquals(Fingerprints.sha256(orderedA), Fingerprints.sha256(orderedB));
    }

    @Test
    void anyImpactfulDifferenceChangesFingerprint() {
        String base = Fingerprints.sha256(
                budgetFingerprint("alice", "M-1", 1L, List.of("a=1", "b=2")));
        assertNotEquals(base, Fingerprints.sha256(
                budgetFingerprint("bob", "M-1", 1L, List.of("a=1", "b=2"))));
        assertNotEquals(base, Fingerprints.sha256(
                budgetFingerprint("alice", "M-2", 1L, List.of("a=1", "b=2"))));
        assertNotEquals(base, Fingerprints.sha256(
                budgetFingerprint("alice", "M-1", 2L, List.of("a=1", "b=2"))));
        assertNotEquals(base, Fingerprints.sha256(
                budgetFingerprint("alice", "M-1", 1L, List.of("a=1", "b=3"))));
        assertNotEquals(base, Fingerprints.sha256(
                budgetFingerprint("alice", "M-1", 1L, List.of("a=1"))));
    }

    @Test
    void canonicalJsonDoesNotSufferDelimiterInjection() {
        // 裸分隔符拼接会把 ["a","1|b=2"] 与 ["a|b","2"]... 等混淆；结构化 JSON 不混淆。
        Map<String, Object> injected = budgetFingerprint("alice", "M-1", 1L,
                List.of("a=1", "b\", \"x\"=\"2"));
        Map<String, Object> normal = budgetFingerprint("alice", "M-1", 1L,
                List.of("a=1", "b=2"));
        assertNotEquals(Fingerprints.sha256(injected), Fingerprints.sha256(normal));

        String injectedJson = Fingerprints.canonicalJson(injected);
        // JSON 中引号被转义，注入名称不会拆出额外的 name/value 字段对
        int nameFieldCount = 0;
        int idx = 0;
        while ((idx = injectedJson.indexOf("\"name\"", idx)) >= 0) {
            nameFieldCount++;
            idx += "\"name\"".length();
        }
        assertEquals(2, nameFieldCount);
    }

    @Test
    void fingerprintIsStableHexSha256() {
        String fingerprint = Fingerprints.sha256(Fingerprints.ordered());
        // 空 JSON 对象 {} 的 SHA-256
        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
                fingerprint);
        assertEquals(64, fingerprint.length());
    }
}
