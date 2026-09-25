package com.example.starter.translation.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回退链纯逻辑单元测试：链展开顺序、断链终止、自环与多语种成环检测。
 */
class FallbackChainTest {

    @Test
    @DisplayName("链展开：从直接语种开始逐级跟随回退，未配置处终止")
    void chainFollowsFallbacks() {
        Map<String, String> fallbacks = Map.of("ja", "en", "en", "de");
        assertThat(FallbackChain.chain("ja", fallbacks)).containsExactly("ja", "en", "de");
        assertThat(FallbackChain.chain("en", fallbacks)).containsExactly("en", "de");
        assertThat(FallbackChain.chain("de", fallbacks)).containsExactly("de");
    }

    @Test
    @DisplayName("链展开：无任何配置时链仅含自身；异常成环数据被截断不死循环")
    void chainTerminatesSafely() {
        assertThat(FallbackChain.chain("ja", Map.of())).containsExactly("ja");
        Map<String, String> cyclic = Map.of("ja", "en", "en", "ja");
        assertThat(FallbackChain.chain("ja", cyclic)).containsExactly("ja", "en");
    }

    @Test
    @DisplayName("环检测：空图与单链无环；自环与多语种环均被检出")
    void cycleDetection() {
        assertThat(FallbackChain.hasCycle(Map.of())).isFalse();
        assertThat(FallbackChain.hasCycle(Map.of("ja", "en", "en", "de", "fr", "de"))).isFalse();
        assertThat(FallbackChain.hasCycle(Map.of("ja", "ja"))).isTrue();
        assertThat(FallbackChain.hasCycle(Map.of("ja", "en", "en", "ja"))).isTrue();
        assertThat(FallbackChain.hasCycle(Map.of("a", "b", "b", "c", "c", "a", "x", "y"))).isTrue();
    }
}
