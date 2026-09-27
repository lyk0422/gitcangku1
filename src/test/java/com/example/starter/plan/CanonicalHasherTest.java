package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.CanonicalHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * 规范化指纹单元测试：键序无关、集合序无关、操作者参与、字段完整区分、
 * 不依赖未转义分隔符（分隔符注入构造不出碰撞）。
 */
class CanonicalHasherTest {

    private final CanonicalHasher hasher = new CanonicalHasher(new ObjectMapper());

    @Test
    void mapKeyOrderDoesNotChangeHash() {
        TreeMap<String, Object> a = hasher.object("x", 1, "y", "二");
        TreeMap<String, Object> b = hasher.object("y", "二", "x", 1);
        assertThat(hasher.sha256(a)).isEqualTo(hasher.sha256(b));
    }

    @Test
    void differentValueProducesDifferentHash() {
        String h1 = hasher.sha256(hasher.object("x", 1));
        String h2 = hasher.sha256(hasher.object("x", 2));
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void operatorIsPartOfFingerprint() {
        String opA = hasher.sha256(hasher.object("opType", "PUBLISH", "operator", "alice",
                "scheduleKey", "S1"));
        String opB = hasher.sha256(hasher.object("opType", "PUBLISH", "operator", "bob",
                "scheduleKey", "S1"));
        assertThat(opA).isNotEqualTo(opB);
    }

    @Test
    void opTypeIsPartOfFingerprint() {
        String publish = hasher.sha256(hasher.object("opType", "PUBLISH", "operator", "alice",
                "scheduleKey", "S1"));
        String cancel = hasher.sha256(hasher.object("opType", "CANCEL", "operator", "alice",
                "scheduleKey", "S1"));
        assertThat(publish).isNotEqualTo(cancel);
    }

    @Test
    void normalizedSetsAreOrderIndependent() {
        SortedSet<String> s1 = new TreeSet<>(List.of("A", "B", "C"));
        SortedSet<String> s2 = new TreeSet<>(List.of("C", "A", "B"));
        String h1 = hasher.sha256(hasher.object("classes", hasher.sortedStrings(s1)));
        String h2 = hasher.sha256(hasher.object("classes", hasher.sortedStrings(s2)));
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unescapedDelimiterTrickCannotCollide() {
        // 若用 '|' 拼接，["a|b","c"] 与 ["a","b|c"] 会得到相同串；JSON 编码不会碰撞
        List<String> variant1 = List.of("a|b", "c");
        List<String> variant2 = List.of("a", "b|c");
        String h1 = hasher.sha256(hasher.object("items", variant1));
        String h2 = hasher.sha256(hasher.object("items", variant2));
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void nestedMapAndListsFullyDistinguished() {
        TreeMap<String, Object> n1 = hasher.object();
        n1.put("opType", "RESCHEDULE");
        n1.put("items", List.of(
                hasher.object("newScheduleKey", "N1", "oldScheduleKey", "O1"),
                hasher.object("newScheduleKey", "N2", "oldScheduleKey", "O2")));
        TreeMap<String, Object> n2 = hasher.object();
        n2.put("opType", "RESCHEDULE");
        n2.put("items", List.of(
                hasher.object("newScheduleKey", "N1", "oldScheduleKey", "O2"),
                hasher.object("newScheduleKey", "N2", "oldScheduleKey", "O1")));
        assertThat(hasher.sha256(n1)).isNotEqualTo(hasher.sha256(n2));
    }

    @Test
    void nullVsAbsentLikeValuesAreStable() {
        // 长期有效（effectiveToUtc=null）与有限期必须区分；两次计算保持稳定
        TreeMap<String, Object> openEnded = hasher.object("from", 100L, "to", null);
        TreeMap<String, Object> bounded = hasher.object("from", 100L, "to", 200L);
        assertThat(hasher.sha256(openEnded)).isEqualTo(hasher.sha256(openEnded));
        assertThat(hasher.sha256(openEnded)).isNotEqualTo(hasher.sha256(bounded));
    }

    @Test
    void hashIs64HexChars() {
        String hash = hasher.sha256(hasher.object("k", "v"));
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }
}
