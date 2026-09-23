package com.example.starter.repo;

import com.example.starter.domain.ZoneWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审核快照中区域窗口文本编解码的往返一致性测试（纯单元测试，不依赖数据库）。
 */
@DisplayName("区域窗口快照编解码")
class ZoneWindowCodecTest {

    @Test
    @DisplayName("带窗口、全时与混合列表往返一致")
    void roundTrip() {
        List<ZoneWindow> windows = List.of(
                new ZoneWindow("z1", 100L, 200L),
                new ZoneWindow("z2", null, null),
                new ZoneWindow("z3", -50L, 0L));
        assertEquals(windows, ReviewRepository.decodeZoneWindows(
                ReviewRepository.encodeZoneWindows(windows)));
    }

    @Test
    @DisplayName("空列表与空串互转")
    void emptyRoundTrip() {
        assertEquals("", ReviewRepository.encodeZoneWindows(List.of()));
        assertTrue(ReviewRepository.decodeZoneWindows("").isEmpty());
    }

    @Test
    @DisplayName("既有历史缺省窗口（空起止）解码为全时 null 对")
    void legacyEntryDecodesToAlwaysValid() {
        assertEquals(List.of(new ZoneWindow("lz", null, null)),
                ReviewRepository.decodeZoneWindows("lz::"));
    }
}
