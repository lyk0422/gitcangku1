package com.example.starter.incident.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.example.starter.incident.ApiException;

/**
 * 方案三方合并引擎（纯函数，无外部依赖）。
 * 按稳定 taskId 与边身份（from + 目标事件 + to）对齐 base/left/right 三侧：
 * 相同或单侧改动自动采用；双侧字段分歧、删改并存、同任务对反向边同时新增
 * （同一边相反操作）生成显式冲突。冲突由调用方以 LEFT/RIGHT/MANUAL 一次性解决，
 * 遗漏、多余、重复解决均拒绝。输出排序稳定（任务按 taskId、边按身份字典序）。
 */
public final class PlanMergeEngine {

    private PlanMergeEngine() {
    }

    /** 冲突类型：双侧字段分歧 / 删改并存 / 双侧新增同名任务内容不同 / 同任务对反向边。 */
    public enum ConflictType {
        TASK_FIELD_CONFLICT,
        TASK_DELETE_MODIFY,
        TASK_ADD_ADD,
        EDGE_OPPOSITE
    }

    /** 冲突解决选择：采用左侧 / 采用右侧 / 手工给出完整结果。 */
    public enum Choice {
        LEFT,
        RIGHT,
        MANUAL
    }

    /** 自动采用变更的种类。 */
    public enum ChangeKind {
        TASK_ADDED,
        TASK_REMOVED,
        TASK_MODIFIED,
        EDGE_ADDED,
        EDGE_REMOVED
    }

    /** 变更来源侧：LEFT / RIGHT / BOTH（两侧相同改动）。 */
    public enum Source {
        LEFT,
        RIGHT,
        BOTH
    }

    /**
     * 合并计算用的任务内容快照：taskId 为稳定标识，其余为计划字段。
     */
    public record TaskContent(String taskId, String groupCode, String title, String assignee) {

        boolean sameFields(TaskContent other) {
            return other != null && groupCode.equals(other.groupCode)
                    && title.equals(other.title) && assignee.equals(other.assignee);
        }
    }

    /**
     * 依赖边身份：fromTaskId 为前置任务；toIncidentKey 空串表示本事件内部边。
     */
    public record EdgeKey(String fromTaskId, String toIncidentKey, String toTaskId)
            implements Comparable<EdgeKey> {

        /** 内部边的目标事件哨兵值（空串）。 */
        public static final String INTERNAL = "";

        public String id() {
            return fromTaskId + "->" + toIncidentKey + ":" + toTaskId;
        }

        /** 仅内部边存在反向边（跨事件边的反向属于另一事件的方案）。 */
        public EdgeKey reverse() {
            return new EdgeKey(toTaskId, toIncidentKey, fromTaskId);
        }

        boolean isInternal() {
            return toIncidentKey.isEmpty();
        }

        @Override
        public int compareTo(EdgeKey other) {
            return id().compareTo(other.id());
        }
    }

    /**
     * 自动采用的单侧（或双侧相同）变更。taskId 与 content 用于任务类变更，
     * edge 用于边类变更，不适用字段为 null。
     */
    public record Change(ChangeKind kind, Source source, String taskId, EdgeKey edge,
                         TaskContent content) {
    }

    /**
     * 显式冲突。任务冲突：leftTask/rightTask 为该侧结果（删除侧为 null，
     * leftPresent/rightPresent 标识该侧任务是否存在）；EDGE_OPPOSITE 冲突：
     * edge 为左侧新增边，reverseEdge 为右侧新增的反向边。
     */
    public record Conflict(String conflictId, ConflictType type, String taskId,
                           EdgeKey edge, EdgeKey reverseEdge,
                           TaskContent baseTask, TaskContent leftTask, TaskContent rightTask,
                           boolean leftPresent, boolean rightPresent) {

        static Conflict task(ConflictType type, String taskId, TaskContent baseTask,
                             TaskContent leftTask, TaskContent rightTask,
                             boolean leftPresent, boolean rightPresent) {
            return new Conflict("TASK:" + taskId, type, taskId, null, null,
                    baseTask, leftTask, rightTask, leftPresent, rightPresent);
        }

        static Conflict edgeOpposite(EdgeKey leftEdge, EdgeKey rightEdge) {
            String first = leftEdge.id().compareTo(rightEdge.id()) <= 0 ? leftEdge.id() : rightEdge.id();
            String second = first.equals(leftEdge.id()) ? rightEdge.id() : leftEdge.id();
            return new Conflict("EDGE:" + first + "<->" + second, ConflictType.EDGE_OPPOSITE,
                    null, leftEdge, rightEdge, null, null, null, true, true);
        }
    }

    /**
     * 三方差异结果：自动采用的变更与显式冲突（均稳定排序），
     * 以及冲突之外的自动合并任务集与边集（冲突项不在其中）。
     */
    public record MergeDiff(List<Change> changes, List<Conflict> conflicts,
                            Map<String, TaskContent> autoTasks, Set<EdgeKey> autoEdges) {
    }

    /**
     * 冲突解决项。MANUAL 任务冲突必须给出完整 manualTask（taskId 与冲突一致）；
     * MANUAL 边冲突必须给出 manualEdgePresent（true 保留冲突涉及边，false 全部移除）。
     */
    public record Resolution(String conflictId, Choice choice, TaskContent manualTask,
                             Boolean manualEdgePresent) {
    }

    /**
     * 合并最终结果：三方差异 + 最终任务集与边集（稳定排序的副本）。
     */
    public record MergeOutcome(MergeDiff diff, Map<String, TaskContent> tasks,
                               Set<EdgeKey> edges) {
    }

    /**
     * 计算三方差异。输入为各侧任务内容（按 taskId 唯一）与边身份集合。
     */
    public static MergeDiff diff(List<TaskContent> baseTasks, List<TaskContent> leftTasks,
                                 List<TaskContent> rightTasks, Set<EdgeKey> baseEdges,
                                 Set<EdgeKey> leftEdges, Set<EdgeKey> rightEdges) {
        Map<String, TaskContent> base = byTaskId(baseTasks, "base");
        Map<String, TaskContent> left = byTaskId(leftTasks, "left");
        Map<String, TaskContent> right = byTaskId(rightTasks, "right");

        List<Change> changes = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        Map<String, TaskContent> autoTasks = new TreeMap<>();

        Set<String> taskIds = new TreeSet<>();
        taskIds.addAll(base.keySet());
        taskIds.addAll(left.keySet());
        taskIds.addAll(right.keySet());
        for (String taskId : taskIds) {
            TaskContent b = base.get(taskId);
            TaskContent l = left.get(taskId);
            TaskContent r = right.get(taskId);
            diffTask(taskId, b, l, r, changes, conflicts, autoTasks);
        }

        Set<EdgeKey> autoEdges = new TreeSet<>();
        diffEdges(baseEdges, leftEdges, rightEdges, changes, conflicts, autoEdges);

        changes.sort(Comparator.comparing(PlanMergeEngine::changeSortKey));
        conflicts.sort(Comparator.comparing(Conflict::conflictId));
        return new MergeDiff(List.copyOf(changes), List.copyOf(conflicts),
                new LinkedHashMap<>(autoTasks), new TreeSet<>(autoEdges));
    }

    private static void diffTask(String taskId, TaskContent b, TaskContent l, TaskContent r,
                                 List<Change> changes, List<Conflict> conflicts,
                                 Map<String, TaskContent> autoTasks) {
        boolean inBase = b != null;
        boolean inLeft = l != null;
        boolean inRight = r != null;
        if (inBase && inLeft && inRight) {
            boolean leftChanged = !l.sameFields(b);
            boolean rightChanged = !r.sameFields(b);
            if (!leftChanged && !rightChanged) {
                autoTasks.put(taskId, b);
            } else if (leftChanged && !rightChanged) {
                autoTasks.put(taskId, l);
                changes.add(new Change(ChangeKind.TASK_MODIFIED, Source.LEFT, taskId, null, l));
            } else if (!leftChanged) {
                autoTasks.put(taskId, r);
                changes.add(new Change(ChangeKind.TASK_MODIFIED, Source.RIGHT, taskId, null, r));
            } else if (l.sameFields(r)) {
                autoTasks.put(taskId, l);
                changes.add(new Change(ChangeKind.TASK_MODIFIED, Source.BOTH, taskId, null, l));
            } else {
                conflicts.add(Conflict.task(ConflictType.TASK_FIELD_CONFLICT, taskId, b, l, r,
                        true, true));
            }
        } else if (inBase && !inLeft && !inRight) {
            changes.add(new Change(ChangeKind.TASK_REMOVED, Source.BOTH, taskId, null, null));
        } else if (inBase && !inRight) {
            // 右侧删除：左侧未改则自动删除，左侧已改则删改并存冲突
            if (l.sameFields(b)) {
                changes.add(new Change(ChangeKind.TASK_REMOVED, Source.RIGHT, taskId, null, null));
            } else {
                conflicts.add(Conflict.task(ConflictType.TASK_DELETE_MODIFY, taskId, b, l, null,
                        true, false));
            }
        } else if (inBase) {
            if (r.sameFields(b)) {
                changes.add(new Change(ChangeKind.TASK_REMOVED, Source.LEFT, taskId, null, null));
            } else {
                conflicts.add(Conflict.task(ConflictType.TASK_DELETE_MODIFY, taskId, b, null, r,
                        false, true));
            }
        } else if (inLeft && inRight) {
            if (l.sameFields(r)) {
                autoTasks.put(taskId, l);
                changes.add(new Change(ChangeKind.TASK_ADDED, Source.BOTH, taskId, null, l));
            } else {
                conflicts.add(Conflict.task(ConflictType.TASK_ADD_ADD, taskId, null, l, r,
                        true, true));
            }
        } else if (inLeft) {
            autoTasks.put(taskId, l);
            changes.add(new Change(ChangeKind.TASK_ADDED, Source.LEFT, taskId, null, l));
        } else {
            autoTasks.put(taskId, r);
            changes.add(new Change(ChangeKind.TASK_ADDED, Source.RIGHT, taskId, null, r));
        }
    }

    private static void diffEdges(Set<EdgeKey> baseEdges, Set<EdgeKey> leftEdges,
                                  Set<EdgeKey> rightEdges, List<Change> changes,
                                  List<Conflict> conflicts, Set<EdgeKey> autoEdges) {
        Set<EdgeKey> all = new TreeSet<>();
        all.addAll(baseEdges);
        all.addAll(leftEdges);
        all.addAll(rightEdges);
        // 同任务对反向边同时新增：先归集，按无序对去重生成一次冲突
        Set<EdgeKey> leftAdded = new TreeSet<>(leftEdges);
        leftAdded.removeAll(baseEdges);
        Set<EdgeKey> rightAdded = new TreeSet<>(rightEdges);
        rightAdded.removeAll(baseEdges);
        Set<EdgeKey> oppositePairs = new TreeSet<>();
        for (EdgeKey edge : leftAdded) {
            if (!edge.isInternal()) {
                continue;
            }
            EdgeKey reverse = edge.reverse();
            if (rightAdded.contains(reverse)) {
                oppositePairs.add(edge.id().compareTo(reverse.id()) <= 0 ? edge : reverse);
            }
        }
        Set<EdgeKey> conflictedEdges = new TreeSet<>();
        for (EdgeKey pairEdge : oppositePairs) {
            EdgeKey reverse = pairEdge.reverse();
            EdgeKey leftEdge = leftAdded.contains(pairEdge) ? pairEdge : reverse;
            EdgeKey rightEdge = leftEdge.equals(pairEdge) ? reverse : pairEdge;
            conflicts.add(Conflict.edgeOpposite(leftEdge, rightEdge));
            conflictedEdges.add(leftEdge);
            conflictedEdges.add(rightEdge);
        }

        for (EdgeKey edge : all) {
            if (conflictedEdges.contains(edge)) {
                continue;
            }
            boolean inBase = baseEdges.contains(edge);
            boolean inLeft = leftEdges.contains(edge);
            boolean inRight = rightEdges.contains(edge);
            if (inBase && inLeft && inRight) {
                autoEdges.add(edge);
            } else if (inBase && !inLeft && !inRight) {
                changes.add(new Change(ChangeKind.EDGE_REMOVED, Source.BOTH, null, edge, null));
            } else if (inBase && !inRight) {
                changes.add(new Change(ChangeKind.EDGE_REMOVED, Source.RIGHT, null, edge, null));
            } else if (inBase) {
                changes.add(new Change(ChangeKind.EDGE_REMOVED, Source.LEFT, null, edge, null));
            } else if (inLeft && inRight) {
                autoEdges.add(edge);
                changes.add(new Change(ChangeKind.EDGE_ADDED, Source.BOTH, null, edge, null));
            } else if (inLeft) {
                autoEdges.add(edge);
                changes.add(new Change(ChangeKind.EDGE_ADDED, Source.LEFT, null, edge, null));
            } else if (inRight) {
                autoEdges.add(edge);
                changes.add(new Change(ChangeKind.EDGE_ADDED, Source.RIGHT, null, edge, null));
            }
        }
    }

    /**
     * 应用冲突解决，产出最终任务集与边集。
     * 解决项必须恰好覆盖全部冲突：遗漏、多余、重复均 400；失败不污染输入。
     */
    public static MergeOutcome apply(MergeDiff diff, List<Resolution> resolutions) {
        List<Resolution> safe = resolutions == null ? List.of() : resolutions;
        Map<String, Resolution> byId = new LinkedHashMap<>();
        for (Resolution resolution : safe) {
            if (resolution == null || resolution.conflictId() == null
                    || resolution.conflictId().isBlank()) {
                throw ApiException.badRequest("冲突解决项 conflictId 不能为空");
            }
            if (resolution.choice() == null) {
                throw ApiException.badRequest("冲突解决项 choice 不能为空: " + resolution.conflictId());
            }
            if (byId.putIfAbsent(resolution.conflictId(), resolution) != null) {
                throw ApiException.badRequest("重复解决同一冲突: " + resolution.conflictId());
            }
        }
        Map<String, Conflict> conflicts = new LinkedHashMap<>();
        for (Conflict conflict : diff.conflicts()) {
            conflicts.put(conflict.conflictId(), conflict);
        }
        for (String conflictId : conflicts.keySet()) {
            if (!byId.containsKey(conflictId)) {
                throw ApiException.badRequest("遗漏冲突解决: " + conflictId);
            }
        }
        for (String conflictId : byId.keySet()) {
            if (!conflicts.containsKey(conflictId)) {
                throw ApiException.badRequest("多余的冲突解决（冲突不存在）: " + conflictId);
            }
        }

        Map<String, TaskContent> tasks = new TreeMap<>(diff.autoTasks());
        Set<EdgeKey> edges = new TreeSet<>(diff.autoEdges());
        for (Conflict conflict : diff.conflicts()) {
            Resolution resolution = byId.get(conflict.conflictId());
            if (conflict.type() == ConflictType.EDGE_OPPOSITE) {
                applyEdgeResolution(conflict, resolution, edges);
            } else {
                applyTaskResolution(conflict, resolution, tasks);
            }
        }
        return new MergeOutcome(diff, new LinkedHashMap<>(tasks), new TreeSet<>(edges));
    }

    private static void applyTaskResolution(Conflict conflict, Resolution resolution,
                                            Map<String, TaskContent> tasks) {
        String taskId = conflict.taskId();
        switch (resolution.choice()) {
            case LEFT -> {
                if (conflict.leftPresent()) {
                    tasks.put(taskId, conflict.leftTask());
                } else {
                    tasks.remove(taskId);
                }
            }
            case RIGHT -> {
                if (conflict.rightPresent()) {
                    tasks.put(taskId, conflict.rightTask());
                } else {
                    tasks.remove(taskId);
                }
            }
            case MANUAL -> {
                TaskContent manual = resolution.manualTask();
                if (manual == null) {
                    throw ApiException.badRequest("MANUAL 解决必须给出完整任务字段: "
                            + conflict.conflictId());
                }
                if (!taskId.equals(manual.taskId()) || manual.groupCode() == null
                        || manual.groupCode().isBlank() || manual.title() == null
                        || manual.title().isBlank() || manual.assignee() == null
                        || manual.assignee().isBlank()) {
                    throw ApiException.badRequest("MANUAL 任务字段不完整或 taskId 与冲突不一致: "
                            + conflict.conflictId());
                }
                tasks.put(taskId, manual);
            }
            default -> throw new IllegalStateException("未知 choice: " + resolution.choice());
        }
    }

    private static void applyEdgeResolution(Conflict conflict, Resolution resolution,
                                            Set<EdgeKey> edges) {
        switch (resolution.choice()) {
            case LEFT -> {
                edges.add(conflict.edge());
                edges.remove(conflict.reverseEdge());
            }
            case RIGHT -> {
                edges.add(conflict.reverseEdge());
                edges.remove(conflict.edge());
            }
            case MANUAL -> {
                if (resolution.manualEdgePresent() == null) {
                    throw ApiException.badRequest("MANUAL 解决必须给出边结果 manualEdgePresent: "
                            + conflict.conflictId());
                }
                if (resolution.manualEdgePresent()) {
                    edges.add(conflict.edge());
                    edges.add(conflict.reverseEdge());
                } else {
                    edges.remove(conflict.edge());
                    edges.remove(conflict.reverseEdge());
                }
            }
            default -> throw new IllegalStateException("未知 choice: " + resolution.choice());
        }
    }

    private static Map<String, TaskContent> byTaskId(List<TaskContent> tasks, String side) {
        Map<String, TaskContent> map = new LinkedHashMap<>();
        for (TaskContent task : tasks) {
            if (map.putIfAbsent(task.taskId(), task) != null) {
                throw ApiException.badRequest(side + " 侧存在重复 taskId: " + task.taskId());
            }
        }
        return map;
    }

    private static String changeSortKey(Change change) {
        // 任务变更排在边变更之前，各自按键字典序，保证稳定输出
        if (change.taskId() != null) {
            return "0:" + change.taskId();
        }
        return "1:" + change.edge().id();
    }
}
