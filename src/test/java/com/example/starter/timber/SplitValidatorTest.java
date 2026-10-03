package com.example.starter.timber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * 拆分纯函数校验与幂等指纹的单元测试，不依赖数据库。
 */
class SplitValidatorTest {

    @Test
    void weightConservationPassesWhenSumEqualsParent() {
        Optional<String> error = SplitValidator.checkWeightConservation(100, List.of(
                new SplitChildInput("B1", 60), new SplitChildInput("B2", 40)));
        assertTrue(error.isEmpty());
    }

    @Test
    void weightConservationFailsWhenSumMismatch() {
        Optional<String> error = SplitValidator.checkWeightConservation(100, List.of(
                new SplitChildInput("B1", 60), new SplitChildInput("B2", 50)));
        assertTrue(error.isPresent());
    }

    @Test
    void weightConservationFailsOnNonPositiveChild() {
        Optional<String> error = SplitValidator.checkWeightConservation(100, List.of(
                new SplitChildInput("B1", 0), new SplitChildInput("B2", 100)));
        assertTrue(error.isPresent());
    }

    @Test
    void weightConservationFailsOnEmptyChildren() {
        assertTrue(SplitValidator.checkWeightConservation(100, List.of()).isPresent());
    }

    @Test
    void distinctBatchNosDetectsDuplicate() {
        assertTrue(SplitValidator.checkDistinctBatchNos(List.of(
                new SplitChildInput("B1", 50), new SplitChildInput("B1", 50))).isPresent());
        assertTrue(SplitValidator.checkDistinctBatchNos(List.of(
                new SplitChildInput("B1", 50), new SplitChildInput("B2", 50))).isEmpty());
    }

    @Test
    void fingerprintIsDeterministicAndParameterSensitive() {
        String a1 = Fingerprints.of("SPLIT", List.of("P", "B1:60", "B2:40"));
        String a2 = Fingerprints.of("SPLIT", List.of("P", "B1:60", "B2:40"));
        String b = Fingerprints.of("SPLIT", List.of("P", "B1:60", "B2:50"));
        String otherOp = Fingerprints.of("SIGN", List.of("P", "B1:60", "B2:40"));
        assertEquals(a1, a2);
        assertEquals(64, a1.length());
        assertNotEquals(a1, b);
        assertNotEquals(a1, otherOp);
    }
}