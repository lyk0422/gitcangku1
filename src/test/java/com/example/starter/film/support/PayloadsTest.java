package com.example.starter.film.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 幂等指纹与快照定格纯函数测试。
 */
class PayloadsTest {

    @Test
    void sameSegmentParamsProduceSameHash() {
        assertEquals(
                Payloads.segmentSubmit(1L, 2, "abcd", 0),
                Payloads.segmentSubmit(1L, 2, "abcd", 0)
        );
    }

    @Test
    void differentBaseVersionProducesDifferentHash() {
        assertNotEquals(
                Payloads.segmentSubmit(1L, 2, "abcd", 0),
                Payloads.segmentSubmit(1L, 2, "abcd", 1)
        );
    }

    @Test
    void differentDigestProducesDifferentHash() {
        assertNotEquals(
                Payloads.segmentSubmit(1L, 2, "abcd", 1),
                Payloads.segmentSubmit(1L, 2, "abce", 1)
        );
    }

    @Test
    void snapshotJoinsSeqVersionDigestInOrder() {
        List<TestLine> lines = List.of(
                new TestLine(1, 1, "aaaa"),
                new TestLine(2, 3, "bbbb")
        );
        assertEquals("1:1:aaaa|2:3:bbbb", Payloads.snapshot(lines));
    }

    @Test
    void snapshotChangesWhenAnySegmentVersionChanges() {
        List<TestLine> before = List.of(new TestLine(1, 1, "aaaa"), new TestLine(2, 1, "bbbb"));
        List<TestLine> after = List.of(new TestLine(1, 1, "aaaa"), new TestLine(2, 2, "bbbb"));
        assertNotEquals(Payloads.snapshot(before), Payloads.snapshot(after));
    }

    private record TestLine(int seq, int versionNo, String digest)
            implements Payloads.SnapshotLine {
    }
}
