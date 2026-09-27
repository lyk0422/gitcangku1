package com.example.starter.firmware;

import com.example.starter.firmware.service.MatrixService;
import com.example.starter.firmware.support.ModuleVersion;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 完整依赖集合闭包计算单元测试：传递依赖逐项纳入、多路径取最高最低版本、结果按模块名稳定排序。
 */
class DependencyResolutionTest {

    private MatrixService.ModuleDependencyEdge edge(String from, String to, String min) {
        return new MatrixService.ModuleDependencyEdge(from, to, ModuleVersion.parse(min));
    }

    private Map<String, List<MatrixService.ModuleDependencyEdge>> graph(
            MatrixService.ModuleDependencyEdge... edges) {
        Map<String, List<MatrixService.ModuleDependencyEdge>> graph = new LinkedHashMap<>();
        for (MatrixService.ModuleDependencyEdge edge : edges) {
            graph.computeIfAbsent(edge.from(), k -> new java.util.ArrayList<>()).add(edge);
        }
        return graph;
    }

    private Map<String, ModuleVersion> direct(String... pairs) {
        Map<String, ModuleVersion> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], ModuleVersion.parse(pairs[i + 1]));
        }
        return map;
    }

    @Test
    void 传递依赖逐项纳入_多路径取最高版本_排序稳定() {
        // 固件直接需要 A@1.0；A 依赖 B@1.0、C@2.0；B 依赖 C@1.5（多路径取2.0）；C 依赖 D@0
        Map<String, List<MatrixService.ModuleDependencyEdge>> graph = graph(
                edge("A", "B", "1.0"), edge("A", "C", "2.0"),
                edge("B", "C", "1.5"), edge("C", "D", "0"));

        Map<String, ModuleVersion> required =
                MatrixService.resolveRequiredModules(graph, direct("A", "1.0"));

        assertThat(required.keySet()).containsExactly("A", "B", "C", "D");
        assertThat(required.get("A").raw()).isEqualTo("1.0");
        assertThat(required.get("B").raw()).isEqualTo("1.0");
        assertThat(required.get("C").raw()).isEqualTo("2.0");
        assertThat(required.get("D").raw()).isEqualTo("0");
    }

    @Test
    void 无依赖边时仅直接需求_菱形依赖合并最高版本() {
        Map<String, List<MatrixService.ModuleDependencyEdge>> graph = graph(
                edge("left", "x", "3.1"), edge("right", "x", "3.0"));

        Map<String, ModuleVersion> required = MatrixService.resolveRequiredModules(
                graph, direct("left", "1", "right", "1"));

        assertThat(required.keySet()).containsExactly("left", "right", "x");
        assertThat(required.get("x")).isEqualTo(ModuleVersion.parse("3.1"));
    }
}
