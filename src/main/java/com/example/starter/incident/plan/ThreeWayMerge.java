package com.example.starter.incident.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import com.example.starter.incident.ApiException;

/**
 * 方案三方合并纯逻辑：按稳定 taskId 与有向边键比较 base/left/right 三份快照。
 * 相同或单侧改动自动采用；双侧字段分歧、删改并存生成任务冲突，
 * 同一对任务反向新增边生成边冲突。冲突须由调用方以 LEFT/RIGHT/MANUAL 一次性全部解决。
 * 本类不依赖数据库，输入输出均为不可变快照，便于单元测试。
 */
public final class ThreeWayMerge {

    private ThreeWayMerge() {
    }

    /**
     * 版本快照：taskId → 任务内容，以及依赖边集合。
     */
    public record Snapshot(Map<String, TaskContent> tasks, Set<EdgeKey> edges) {
    }

    /**
     * 冲突类型：TASK_FIELD 双侧字段分歧（含双侧同名新增内容不同）；
     * TASK_DELETE_MODIFY 删改并存；EDGE_OPPOSITE 同一对任务反向新增边。
     */
    public enum ConflictType {
        TASK_FIELD,
        TASK_DELETE_MODIFY,
        EDGE_OPPOSITE
    }

    /**
     * 任务冲突：base/left/right 为三方内容，删除侧为 null。
     * conflictKey 形如 "task:T-1"。
     */
    public record TaskConflict(String conflictKey, ConflictType type, String taskId,
                               TaskContent base, TaskContent left, TaskContent right) {
    }

    /**
     * 边冲突：同一对任务上的反向新增边；leftEdge/rightEdge 为两侧各自新增的边。
     * conflictKey 形如 "edge:A-&gt;B"（端点按字典序排列，与方向无关）。
     */
    public record EdgeConflict(String conflictKey, ConflictType type,
                               EdgeKey leftEdge, EdgeKey rightEdge) {
    }

    /**
     * 三方差异项：itemKey 形如 "task:T-1" / "edge:A-&gt;B"；kind 为 TASK/EDGE；
     * change 为 LEFT/RIGHT（单侧改动采用侧）、BOTH（双侧相同改动）、CONFLICT（显式冲突）。
     */
    public record DiffItem(String itemKey, String kind, String change) {
    }

    /**
     * 三方比较结果：autoTasks/autoEdges 为自动采用部分（冲突项不在内），
     * 冲突列表与差异项均按 conflictKey/itemKey 稳定排序。
     */
    public record Outcome(Map<String, TaskContent> autoTasks, Set<EdgeKey> autoEdges,
                          List<TaskConflict> taskConflicts, List<EdgeConflict> edgeConflicts,
                          List<DiffItem> items) {

        /**
         * 全部冲突键（任务冲突与边冲突合并，稳定排序）。
         */
        public List<String> conflictKeys() {
            List<String> keys = new ArrayList<>();
            taskConflicts.forEach(c -> keys.add(c.conflictKey()));
            edgeConflicts.forEach(c -> keys.add(c.conflictKey()));
            return keys.stream().sorted().toList();
        }
    }

    /**
     * 解决选择：采用左侧、采用右侧或手工给定完整结果。
     */
    public enum Choice {
        LEFT,
        RIGHT,
        MANUAL
    }

    /**
     * 一条冲突解决：task 冲突 MANUAL 须给完整任务字段（manualTask 非空）；
     * edge 冲突 MANUAL 须给边结果（manualEdgeSpecified 为 true，manualEdge 为 null 表示无边）。
     */
    public record Resolution(String conflictKey, Choice choice, TaskContent manualTask,
                             boolean manualEdgeSpecified, EdgeKey manualEdge) {

        public static Resolution of(String conflictKey, Choice choice) {
            return new Resolution(conflictKey, choice, null, false, null);
        }

        public static Resolution manualTask(String conflictKey, TaskContent task) {
            return new Resolution(conflictKey, Choice.MANUAL, task, false, null);
        }

        public static Resolution manualEdge(String conflictKey, EdgeKey edge) {
            return new Resolution(conflictKey, Choice.MANUAL, null, true, edge);
        }
    }

    /**
     * 合并后完整方案：taskId → 任务内容（有序），依赖边集合（有序）。
     */
    public record MergedPlan(Map<String, TaskContent> tasks, Set<EdgeKey> edges) {
    }

    /**
     * 三方比较：相同或单侧改动自动采用，双侧分歧生成显式冲突。
     */
    public static Outcome merge(Snapshot base, Snapshot left, Snapshot right) {
        Map<String, TaskContent> autoTasks = new TreeMap<>();
        List<TaskConflict> taskConflicts = new ArrayList<>();
        List<DiffItem> items = new ArrayList<>();

        Set<String> taskIds = new HashSet<>();
        taskIds.addAll(base.tasks().keySet());
        taskIds.addAll(left.tasks().keySet());
        taskIds.addAll(right.tasks().keySet());
        for (String taskId : taskIds.stream().sorted().toList()) {
            TaskContent b = base.tasks().get(taskId);
            TaskContent l = left.tasks().get(taskId);
            TaskContent r = right.tasks().get(taskId);
            String itemKey = "task:" + taskId;
            if (Objects.equals(l, r)) {
                if (l != null) {
                    autoTasks.put(taskId, l);
                }
                if (!Objects.equals(b, l)) {
                    items.add(new DiffItem(itemKey, "TASK", "BOTH"));
                }
            } else if (Objects.equals(b, l)) {
                if (r != null) {
                    autoTasks.put(taskId, r);
                }
                items.add(new DiffItem(itemKey, "TASK", "RIGHT"));
            } else if (Objects.equals(b, r)) {
                if (l != null) {
                    autoTasks.put(taskId, l);
                }
                items.add(new DiffItem(itemKey, "TASK", "LEFT"));
            } else {
                ConflictType type = (l == null || r == null)
                        ? ConflictType.TASK_DELETE_MODIFY : ConflictType.TASK_FIELD;
                taskConflicts.add(new TaskConflict(itemKey, type, taskId, b, l, r));
                items.add(new DiffItem(itemKey, "TASK", "CONFLICT"));
            }
        }

        // 同一对任务反向新增边 → EDGE_OPPOSITE 冲突，相关边不参与自动采用
        Set<EdgeKey> leftAdded = new LinkedHashSet<>(left.edges());
        leftAdded.removeAll(base.edges());
        Set<EdgeKey> rightAdded = new LinkedHashSet<>(right.edges());
        rightAdded.removeAll(base.edges());
        Map<String, EdgeConflict> edgeConflictsByKey = new TreeMap<>();
        Set<EdgeKey> conflictedEdges = new HashSet<>();
        for (EdgeKey leftEdge : leftAdded.stream().sorted(ThreeWayMerge::compareEdge).toList()) {
            EdgeKey reversed = new EdgeKey(leftEdge.toTaskId(), leftEdge.fromTaskId());
            if (rightAdded.contains(reversed)) {
                String conflictKey = "edge:" + leftEdge.pairKey();
                edgeConflictsByKey.put(conflictKey,
                        new EdgeConflict(conflictKey, ConflictType.EDGE_OPPOSITE, leftEdge, reversed));
                conflictedEdges.add(leftEdge);
                conflictedEdges.add(reversed);
            }
        }

        Set<EdgeKey> autoEdges = new LinkedHashSet<>();
        Set<EdgeKey> allEdges = new HashSet<>();
        allEdges.addAll(base.edges());
        allEdges.addAll(left.edges());
        allEdges.addAll(right.edges());
        allEdges.removeAll(conflictedEdges);
        for (EdgeKey edge : allEdges.stream().sorted(ThreeWayMerge::compareEdge).toList()) {
            boolean b = base.edges().contains(edge);
            boolean l = left.edges().contains(edge);
            boolean r = right.edges().contains(edge);
            String itemKey = "edge:" + edge.fromTaskId() + "->" + edge.toTaskId();
            if (l == r) {
                if (l) {
                    autoEdges.add(edge);
                }
                if (b != l) {
                    items.add(new DiffItem(itemKey, "EDGE", "BOTH"));
                }
            } else if (b == l) {
                if (r) {
                    autoEdges.add(edge);
                }
                items.add(new DiffItem(itemKey, "EDGE", "RIGHT"));
            } else {
                if (l) {
                    autoEdges.add(edge);
                }
                items.add(new DiffItem(itemKey, "EDGE", "LEFT"));
            }
        }
        for (String key : edgeConflictsByKey.keySet()) {
            items.add(new DiffItem(key, "EDGE", "CONFLICT"));
        }

        items.sort((a, b2) -> a.itemKey().compareTo(b2.itemKey()));
        return new Outcome(autoTasks, sortEdges(autoEdges),
                List.copyOf(taskConflicts), List.copyOf(edgeConflictsByKey.values()),
                List.copyOf(items));
    }

    /**
     * 应用冲突解决：解决集合必须与冲突集合精确对应（遗漏、多余、重复均 400）；
     * MANUAL 任务须给完整字段且 taskId 与冲突一致，MANUAL 边须显式给出边结果。
     */
    public static MergedPlan apply(Outcome outcome, List<Resolution> resolutions) {
        Map<String, Resolution> byKey = new LinkedHashMap<>();
        for (Resolution resolution : resolutions) {
            if (resolution.conflictKey() == null || resolution.conflictKey().isBlank()) {
                throw ApiException.badRequest("冲突解决的 conflictKey 不能为空");
            }
            if (byKey.put(resolution.conflictKey(), resolution) != null) {
                throw ApiException.badRequest("重复的冲突解决: " + resolution.conflictKey());
            }
        }
        Map<String, TaskConflict> taskConflicts = new LinkedHashMap<>();
        outcome.taskConflicts().forEach(c -> taskConflicts.put(c.conflictKey(), c));
        Map<String, EdgeConflict> edgeConflicts = new LinkedHashMap<>();
        outcome.edgeConflicts().forEach(c -> edgeConflicts.put(c.conflictKey(), c));

        for (String key : byKey.keySet()) {
            if (!taskConflicts.containsKey(key) && !edgeConflicts.containsKey(key)) {
                throw ApiException.badRequest("多余的冲突解决: " + key);
            }
        }
        for (String key : outcome.conflictKeys()) {
            if (!byKey.containsKey(key)) {
                throw ApiException.badRequest("遗漏的冲突解决: " + key);
            }
        }

        Map<String, TaskContent> tasks = new TreeMap<>(outcome.autoTasks());
        Set<EdgeKey> edges = new LinkedHashSet<>(outcome.autoEdges());
        for (Resolution resolution : byKey.values()) {
            TaskConflict taskConflict = taskConflicts.get(resolution.conflictKey());
            if (taskConflict != null) {
                applyTaskResolution(taskConflict, resolution, tasks);
                continue;
            }
            EdgeConflict edgeConflict = edgeConflicts.get(resolution.conflictKey());
            applyEdgeResolution(edgeConflict, resolution, edges);
        }
        return new MergedPlan(tasks, sortEdges(edges));
    }

    private static void applyTaskResolution(TaskConflict conflict, Resolution resolution,
                                            Map<String, TaskContent> tasks) {
        if (resolution.choice() == null) {
            throw ApiException.badRequest("冲突解决缺少 choice: " + conflict.conflictKey());
        }
        switch (resolution.choice()) {
            case LEFT -> putIfPresent(tasks, conflict.taskId(), conflict.left());
            case RIGHT -> putIfPresent(tasks, conflict.taskId(), conflict.right());
            case MANUAL -> {
                TaskContent manual = resolution.manualTask();
                if (manual == null) {
                    throw ApiException.badRequest(
                            "MANUAL 任务解决须给出完整任务字段: " + conflict.conflictKey());
                }
                if (!conflict.taskId().equals(manual.taskId())) {
                    throw ApiException.badRequest("MANUAL 任务 taskId 与冲突不一致: "
                            + conflict.conflictKey());
                }
                tasks.put(conflict.taskId(), manual);
            }
        }
    }

    private static void applyEdgeResolution(EdgeConflict conflict, Resolution resolution,
                                            Set<EdgeKey> edges) {
        if (resolution.choice() == null) {
            throw ApiException.badRequest("冲突解决缺少 choice: " + conflict.conflictKey());
        }
        switch (resolution.choice()) {
            case LEFT -> edges.add(conflict.leftEdge());
            case RIGHT -> edges.add(conflict.rightEdge());
            case MANUAL -> {
                if (!resolution.manualEdgeSpecified()) {
                    throw ApiException.badRequest(
                            "MANUAL 边解决须给出边结果: " + conflict.conflictKey());
                }
                if (resolution.manualEdge() != null) {
                    edges.add(resolution.manualEdge());
                }
            }
        }
    }

    private static void putIfPresent(Map<String, TaskContent> tasks, String taskId,
                                     TaskContent content) {
        if (content != null) {
            tasks.put(taskId, content);
        } else {
            tasks.remove(taskId);
        }
    }

    /**
     * 图校验：边不得引用不存在的任务，不得成环（含自环）。返回错误描述，合法返回 null。
     */
    public static String validateGraph(MergedPlan plan) {
        for (EdgeKey edge : plan.edges()) {
            if (!plan.tasks().containsKey(edge.fromTaskId())
                    || !plan.tasks().containsKey(edge.toTaskId())) {
                return "依赖边引用不存在的任务: " + edge.fromTaskId() + "->" + edge.toTaskId();
            }
        }
        Map<String, List<String>> adjacency = new HashMap<>();
        for (EdgeKey edge : plan.edges()) {
            adjacency.computeIfAbsent(edge.fromTaskId(), k -> new ArrayList<>())
                    .add(edge.toTaskId());
        }
        // 三色标记 DFS 判环
        Map<String, Integer> color = new HashMap<>();
        for (String taskId : plan.tasks().keySet()) {
            if (color.getOrDefault(taskId, 0) == 0 && hasCycle(taskId, adjacency, color)) {
                return "依赖图存在环，涉及任务: " + taskId;
            }
        }
        return null;
    }

    private static boolean hasCycle(String start, Map<String, List<String>> adjacency,
                                    Map<String, Integer> color) {
        Deque<String> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            String node = stack.peek();
            if (color.getOrDefault(node, 0) == 0) {
                color.put(node, 1);
                for (String next : adjacency.getOrDefault(node, List.of())) {
                    int nextColor = color.getOrDefault(next, 0);
                    if (nextColor == 1) {
                        return true;
                    }
                    if (nextColor == 0) {
                        stack.push(next);
                    }
                }
            } else {
                color.put(node, 2);
                stack.pop();
            }
        }
        return false;
    }

    private static Set<EdgeKey> sortEdges(Set<EdgeKey> edges) {
        Set<EdgeKey> sorted = new LinkedHashSet<>();
        edges.stream().sorted(ThreeWayMerge::compareEdge).forEach(sorted::add);
        return sorted;
    }

    private static int compareEdge(EdgeKey a, EdgeKey b) {
        int byFrom = a.fromTaskId().compareTo(b.fromTaskId());
        return byFrom != 0 ? byFrom : a.toTaskId().compareTo(b.toTaskId());
    }
}
