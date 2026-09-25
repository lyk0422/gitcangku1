package com.example.starter.incident.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.example.starter.incident.ApiException;

/**
 * 方案三方合并引擎（纯函数，不访问数据库）。
 * 按稳定 taskId 与边键对齐 base/left/right 三方：
 * 相同或单侧改动自动采用；双侧字段分歧、删改并存、双侧同名新增不一致
 * 或同一任务对反向加边生成显式冲突。冲突由调用方一次提交全部
 * LEFT/RIGHT/MANUAL 解决后应用，遗漏、多余、重复解决均拒绝。
 */
public final class PlanMergeEngine {

    private PlanMergeEngine() {
    }

    /** 任务规划字段（title + assignee），三方比较的最小单元。 */
    public record TaskFields(String title, String assignee) {
    }

    /** 依赖边键：fromTaskId 依赖方 → toIncidentKey/toTaskId 前置任务。 */
    public record EdgeKey(String fromTaskId, String toIncidentKey, String toTaskId) {
        /** 规范字符串形式：fromTaskId>toIncidentKey/toTaskId。 */
        public String canonical() {
            return fromTaskId + ">" + toIncidentKey + "/" + toTaskId;
        }
    }

    /** 冲突类型：字段分歧 / 双侧同名新增不一致 / 删改并存 / 同一任务对反向加边。 */
    public enum ConflictType {
        FIELD_DIVERGENCE,
        BOTH_ADDED,
        DELETE_VS_MODIFY,
        OPPOSITE_DIRECTION
    }

    /** 显式冲突：conflictId 稳定且全局唯一，供解决项引用。 */
    public sealed interface MergeConflict permits TaskConflict, EdgeConflict {
        String conflictId();
    }

    /** 任务冲突：base/left/right 为该侧字段快照，任务不存在的一侧为 null。 */
    public record TaskConflict(String taskId, ConflictType type, TaskFields base,
                               TaskFields left, TaskFields right) implements MergeConflict {
        @Override
        public String conflictId() {
            return "TASK|" + taskId;
        }
    }

    /** 边冲突：同一任务对被两侧以相反方向加边。 */
    public record EdgeConflict(EdgeKey leftEdge, EdgeKey rightEdge) implements MergeConflict {
        @Override
        public String conflictId() {
            return "EDGE|" + List.of(leftEdge.canonical(), rightEdge.canonical()).stream()
                    .sorted().reduce((a, b) -> a + "|" + b).orElseThrow();
        }
    }

    /** 自动采用的任务变化：action 为 ADDED / REMOVED / MODIFIED。 */
    public record TaskChange(String taskId, String action, TaskFields base,
                             TaskFields left, TaskFields right, TaskFields adopted) {
    }

    /** 自动采用的边变化：action 为 ADDED / REMOVED，adoptedBy 为 LEFT / RIGHT / BOTH。 */
    public record EdgeChange(EdgeKey edge, String action, String adoptedBy) {
    }

    /**
     * 三方比较结果：taskChanges/edgeChanges 为自动采用的变化（相对 base），
     * tasks/edges 为自动采用后的集合（不含冲突项），conflicts 按 conflictId 排序。
     */
    public record MergeOutcome(List<TaskChange> taskChanges, List<EdgeChange> edgeChanges,
                               Map<String, TaskFields> tasks, Set<EdgeKey> edges,
                               List<MergeConflict> conflicts) {
    }

    /** 冲突解决选择。 */
    public enum Choice {
        LEFT,
        RIGHT,
        MANUAL
    }

    /**
     * 一条冲突解决：MANUAL 时任务冲突须给 manualTask（完整字段）；
     * 边冲突给 manualEdge 表示最终边，manualEdge 为 null 表示该冲突最终无边。
     */
    public record Resolution(String conflictId, Choice choice,
                             TaskFields manualTask, EdgeKey manualEdge) {
    }

    /** 解决应用后的最终任务集与边集（均按稳定键排序）。 */
    public record ResolvedPlan(Map<String, TaskFields> tasks, Set<EdgeKey> edges) {
    }

    private static final Comparator<EdgeKey> EDGE_ORDER = Comparator.comparing(EdgeKey::canonical);

    /**
     * 三方比较：返回自动采用结果与显式冲突列表。
     *
     * @param incidentKey 本事件键（反向边冲突仅识别内部边）
     */
    public static MergeOutcome compute(String incidentKey,
                                       Map<String, TaskFields> base,
                                       Map<String, TaskFields> left,
                                       Map<String, TaskFields> right,
                                       Set<EdgeKey> baseEdges,
                                       Set<EdgeKey> leftEdges,
                                       Set<EdgeKey> rightEdges) {
        Map<String, TaskFields> tasks = new TreeMap<>();
        List<TaskChange> taskChanges = new ArrayList<>();
        List<MergeConflict> conflicts = new ArrayList<>();

        Set<String> taskIds = new TreeSet<>();
        taskIds.addAll(base.keySet());
        taskIds.addAll(left.keySet());
        taskIds.addAll(right.keySet());
        for (String taskId : taskIds) {
            TaskFields b = base.get(taskId);
            TaskFields l = left.get(taskId);
            TaskFields r = right.get(taskId);
            if (Objects.equals(l, r)) {
                // 两侧一致：未变、相同修改、同名同内容新增或同时删除
                adoptTask(taskChanges, tasks, taskId, b, l, r, l);
            } else if (Objects.equals(b, l)) {
                adoptTask(taskChanges, tasks, taskId, b, l, r, r);
            } else if (Objects.equals(b, r)) {
                adoptTask(taskChanges, tasks, taskId, b, l, r, l);
            } else {
                ConflictType type = b == null ? ConflictType.BOTH_ADDED
                        : (l == null || r == null) ? ConflictType.DELETE_VS_MODIFY
                        : ConflictType.FIELD_DIVERGENCE;
                conflicts.add(new TaskConflict(taskId, type, b, l, r));
            }
        }

        Set<EdgeKey> edges = new TreeSet<>(EDGE_ORDER);
        List<EdgeChange> edgeChanges = new ArrayList<>();
        Set<EdgeKey> leftAdded = new TreeSet<>(EDGE_ORDER);
        Set<EdgeKey> rightAdded = new TreeSet<>(EDGE_ORDER);
        Set<EdgeKey> allEdges = new TreeSet<>(EDGE_ORDER);
        allEdges.addAll(baseEdges);
        allEdges.addAll(leftEdges);
        allEdges.addAll(rightEdges);
        for (EdgeKey edge : allEdges) {
            boolean inBase = baseEdges.contains(edge);
            boolean inLeft = leftEdges.contains(edge);
            boolean inRight = rightEdges.contains(edge);
            boolean adopted;
            String adoptedBy;
            if (inLeft == inRight) {
                adopted = inLeft;
                adoptedBy = "BOTH";
            } else if (inBase == inLeft) {
                adopted = inRight;
                adoptedBy = "RIGHT";
            } else {
                adopted = inLeft;
                adoptedBy = "LEFT";
            }
            if (adopted) {
                edges.add(edge);
                if (!inBase) {
                    edgeChanges.add(new EdgeChange(edge, "ADDED", adoptedBy));
                    if (inLeft && !inRight) {
                        leftAdded.add(edge);
                    } else if (inRight && !inLeft) {
                        rightAdded.add(edge);
                    }
                }
            } else if (inBase) {
                edgeChanges.add(new EdgeChange(edge, "REMOVED", adoptedBy));
            }
        }

        // 同一任务对被两侧以相反方向加边（仅内部边）：显式冲突，不自动采用
        for (EdgeKey leftEdge : leftAdded) {
            if (!leftEdge.toIncidentKey().equals(incidentKey)) {
                continue;
            }
            EdgeKey reverse = new EdgeKey(leftEdge.toTaskId(), incidentKey, leftEdge.fromTaskId());
            if (rightAdded.contains(reverse)) {
                edges.remove(leftEdge);
                edges.remove(reverse);
                rightAdded.remove(reverse);
                edgeChanges.removeIf(c -> c.edge().equals(leftEdge) || c.edge().equals(reverse));
                conflicts.add(new EdgeConflict(leftEdge, reverse));
            }
        }

        conflicts.sort(Comparator.comparing(MergeConflict::conflictId));
        taskChanges.sort(Comparator.comparing(TaskChange::taskId));
        edgeChanges.sort(Comparator.comparing(c -> c.edge().canonical()));
        return new MergeOutcome(taskChanges, edgeChanges, tasks, edges, conflicts);
    }

    private static void adoptTask(List<TaskChange> changes, Map<String, TaskFields> tasks,
                                  String taskId, TaskFields base, TaskFields left,
                                  TaskFields right, TaskFields adopted) {
        if (adopted != null) {
            tasks.put(taskId, adopted);
        }
        if (!Objects.equals(base, adopted)) {
            String action = base == null ? "ADDED" : adopted == null ? "REMOVED" : "MODIFIED";
            changes.add(new TaskChange(taskId, action, base, left, right, adopted));
        }
    }

    /**
     * 应用全部冲突解决：解决项必须与冲突集合精确对应，
     * 遗漏、多余、重复解决均拒绝（400）。
     */
    public static ResolvedPlan apply(MergeOutcome outcome, List<Resolution> resolutions) {
        Map<String, MergeConflict> byId = new TreeMap<>();
        for (MergeConflict conflict : outcome.conflicts()) {
            byId.put(conflict.conflictId(), conflict);
        }
        Set<String> seen = new HashSet<>();
        for (Resolution resolution : resolutions) {
            if (!seen.add(resolution.conflictId())) {
                throw ApiException.badRequest("重复解决同一冲突: " + resolution.conflictId());
            }
            if (!byId.containsKey(resolution.conflictId())) {
                throw ApiException.badRequest("解决项没有对应冲突（多余解决）: "
                        + resolution.conflictId());
            }
        }
        if (seen.size() != byId.size()) {
            Set<String> missing = new TreeSet<>(byId.keySet());
            missing.removeAll(seen);
            throw ApiException.badRequest("存在未解决的冲突（遗漏解决）: " + String.join(",", missing));
        }

        Map<String, TaskFields> tasks = new TreeMap<>(outcome.tasks());
        Set<EdgeKey> edges = new TreeSet<>(EDGE_ORDER);
        edges.addAll(outcome.edges());
        for (Resolution resolution : resolutions) {
            MergeConflict conflict = byId.get(resolution.conflictId());
            if (conflict instanceof TaskConflict taskConflict) {
                applyTaskResolution(tasks, taskConflict, resolution);
            } else if (conflict instanceof EdgeConflict edgeConflict) {
                applyEdgeResolution(edges, edgeConflict, resolution);
            }
        }
        return new ResolvedPlan(tasks, edges);
    }

    private static void applyTaskResolution(Map<String, TaskFields> tasks,
                                            TaskConflict conflict, Resolution resolution) {
        switch (resolution.choice()) {
            case LEFT -> putIfPresent(tasks, conflict.taskId(), conflict.left());
            case RIGHT -> putIfPresent(tasks, conflict.taskId(), conflict.right());
            case MANUAL -> {
                TaskFields manual = resolution.manualTask();
                if (manual == null || manual.title() == null || manual.title().isBlank()) {
                    throw ApiException.badRequest("MANUAL 任务解决必须给出完整任务字段: "
                            + conflict.conflictId());
                }
                tasks.put(conflict.taskId(),
                        new TaskFields(manual.title().strip(),
                                manual.assignee() == null || manual.assignee().isBlank()
                                        ? null : manual.assignee().strip()));
            }
        }
    }

    private static void applyEdgeResolution(Set<EdgeKey> edges,
                                            EdgeConflict conflict, Resolution resolution) {
        switch (resolution.choice()) {
            case LEFT -> edges.add(conflict.leftEdge());
            case RIGHT -> edges.add(conflict.rightEdge());
            case MANUAL -> {
                EdgeKey manual = resolution.manualEdge();
                if (manual == null) {
                    return; // 边结果为空：该冲突最终无边
                }
                if (!manual.equals(conflict.leftEdge()) && !manual.equals(conflict.rightEdge())) {
                    throw ApiException.badRequest("MANUAL 边结果必须为冲突两侧之一或无边: "
                            + conflict.conflictId());
                }
                edges.add(manual);
            }
        }
    }

    private static void putIfPresent(Map<String, TaskFields> tasks, String taskId,
                                     TaskFields fields) {
        if (fields != null) {
            tasks.put(taskId, fields);
        }
    }
}
