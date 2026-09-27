package com.example.starter.firmware;

import com.example.starter.firmware.domain.CompatibilityEngine;
import com.example.starter.firmware.domain.ModuleVersion;
import com.example.starter.firmware.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模块依赖闭包与环检测纯单元测试。
 */
class CompatibilityEngineTest {

    private static ModuleVersion v(String value) {
        return ModuleVersion.parse(value, "测试");
    }

    private static Map<String, ModuleVersion> roots(String... pairs) {
        Map<String, ModuleVersion> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], v(pairs[i + 1]));
        }
        return map;
    }

    /**
     * 邻接表参数：from,to,version 三元组。
     */
    private static Map<String, Map<String, ModuleVersion>> graph(String... triples) {
        Map<String, Map<String, ModuleVersion>> adjacency = new HashMap<>();
        for (int i = 0; i < triples.length; i += 3) {
            CompatibilityEngine.putEdge(adjacency, triples[i], triples[i + 1], v(triples[i + 2]));
        }
        return adjacency;
    }

    @Test
    void 闭包_沿依赖边传递展开_多路径取最高最低版本() {
        // 固件直接需要 A@1.0；A 依赖 B@1.0、B 依赖 C@2.0；另有一条路径 A 直接依赖 C@1.5
        Map<String, ModuleVersion> required = CompatibilityEngine.closure(
                roots("A", "1.0"),
                graph("A", "B", "1.0", "B", "C", "2.0", "A", "C", "1.5"));
        assertThat(required).containsOnlyKeys("A", "B", "C");
        assertThat(required.get("A")).isEqualByComparingTo(v("1.0"));
        assertThat(required.get("B")).isEqualByComparingTo(v("1.0"));
        // C 经 B 要求 2.0，高于直接路径的 1.5
        assertThat(required.get("C")).isEqualByComparingTo(v("2.0"));
    }

    @Test
    void 闭包_无依赖时仅保留直接需求() {
        Map<String, ModuleVersion> required = CompatibilityEngine.closure(
                roots("A", "1.0", "B", "2.0"), graph());
        assertThat(required).containsOnlyKeys("A", "B");
    }

    @Test
    void 无环图_环检测通过() {
        CompatibilityEngine.assertAcyclic(graph("A", "B", "1.0", "B", "C", "1.0", "A", "C", "1.0"));
        CompatibilityEngine.assertAcyclic(graph());
    }

    @Test
    void 有环图_抛422() {
        assertThatThrownBy(() -> CompatibilityEngine.assertAcyclic(
                graph("A", "B", "1.0", "B", "A", "1.0")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.code()).isEqualTo("MODULE_DEPENDENCY_CYCLE");
                });
    }

    @Test
    void 自依赖_视为环_抛422() {
        assertThatThrownBy(() -> CompatibilityEngine.assertAcyclic(graph("A", "A", "1.0")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("MODULE_DEPENDENCY_CYCLE"));
    }

    @Test
    void 长度三的环_抛422() {
        assertThatThrownBy(() -> CompatibilityEngine.assertAcyclic(
                graph("A", "B", "1.0", "B", "C", "1.0", "C", "A", "1.0")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("MODULE_DEPENDENCY_CYCLE"));
    }
}
