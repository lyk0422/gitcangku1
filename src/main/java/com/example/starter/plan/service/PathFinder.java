package com.example.starter.plan.service;

import com.example.starter.plan.model.GraphSegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 线路图寻径：枚举起终站之间所有不重复站点的简单路径（不中途等待），
 * 按（总行驶分钟升序，区段编号序列字典序升序）排序，供调用方依次做可行性过滤。
 */
final class PathFinder {

    private PathFinder() {
    }

    /**
     * 一条候选路径：按行驶顺序的区段与总行驶分钟。
     */
    record Path(List<GraphSegment> segments, int totalMinutes) {

        List<String> segmentIds() {
            return segments.stream().map(GraphSegment::segmentId).toList();
        }
    }

    /**
     * 枚举 from 到 to 的全部简单路径并按优选顺序排序（最快在前，同耗时按区段编号字典序）。
     */
    static List<Path> findPaths(List<GraphSegment> segments, String from, String to) {
        Map<String, List<GraphSegment>> adjacency = new LinkedHashMap<>();
        for (GraphSegment s : segments) {
            adjacency.computeIfAbsent(s.fromStation(), k -> new ArrayList<>()).add(s);
        }
        List<Path> result = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        visited.add(from);
        dfs(from, to, adjacency, visited, new ArrayList<>(), 0, result);
        result.sort(Comparator.comparingInt(Path::totalMinutes)
                .thenComparing(Path::segmentIds, PathFinder::compareIds));
        return result;
    }

    private static void dfs(String current, String to, Map<String, List<GraphSegment>> adjacency,
                            Set<String> visited, List<GraphSegment> stack, int minutes,
                            List<Path> result) {
        if (current.equals(to)) {
            if (!stack.isEmpty()) {
                result.add(new Path(List.copyOf(stack), minutes));
            }
            return;
        }
        for (GraphSegment segment : adjacency.getOrDefault(current, List.of())) {
            if (visited.contains(segment.toStation())) {
                continue;
            }
            visited.add(segment.toStation());
            stack.add(segment);
            dfs(segment.toStation(), to, adjacency, visited, stack,
                    minutes + segment.minutes(), result);
            stack.remove(stack.size() - 1);
            visited.remove(segment.toStation());
        }
    }

    /**
     * 区段编号序列字典序：逐项比较，公共前缀时短序列在前。
     */
    private static int compareIds(List<String> left, List<String> right) {
        int common = Math.min(left.size(), right.size());
        for (int i = 0; i < common; i++) {
            int cmp = left.get(i).compareTo(right.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(left.size(), right.size());
    }
}
