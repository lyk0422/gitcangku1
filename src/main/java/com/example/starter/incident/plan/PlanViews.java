package com.example.starter.incident.plan;

import java.util.List;

import com.example.starter.incident.dto.Responses.DiffItemView;
import com.example.starter.incident.dto.Responses.DiffView;
import com.example.starter.incident.dto.Responses.EdgeConflictView;
import com.example.starter.incident.dto.Responses.PlanEdgeView;
import com.example.starter.incident.dto.Responses.PlanTaskView;
import com.example.starter.incident.dto.Responses.TaskConflictView;
import com.example.starter.incident.plan.ThreeWayMerge.Outcome;
import com.example.starter.incident.plan.ThreeWayMerge.Snapshot;

/**
 * 方案领域对象与视图 DTO 的映射器：统一差异视图与证据视图的组织方式，
 * 保证差异查询与合并证据查询输出一致、稳定排序。
 */
final class PlanViews {

    private PlanViews() {
    }

    /**
     * 由任务快照与边快照构造三方合并输入。
     */
    static Snapshot snapshot(List<PlanTask> tasks, List<PlanEdge> edges) {
        var taskMap = new java.util.TreeMap<String, TaskContent>();
        for (PlanTask task : tasks) {
            taskMap.put(task.taskId(), task.content());
        }
        var edgeSet = new java.util.LinkedHashSet<EdgeKey>();
        for (PlanEdge edge : edges) {
            edgeSet.add(edge.key());
        }
        return new Snapshot(taskMap, edgeSet);
    }

    static PlanTaskView toTaskView(TaskContent task) {
        if (task == null) {
            return null;
        }
        return new PlanTaskView(task.taskId(), task.incidentKey(), task.groupCode(), task.title(),
                task.assignee(), task.status().name(), task.completedBy(), task.completedAt());
    }

    static PlanEdgeView toEdgeView(EdgeKey edge) {
        if (edge == null) {
            return null;
        }
        return new PlanEdgeView(edge.fromTaskId(), edge.toTaskId());
    }

    /**
     * 由三方比较结果构造差异视图（差异项与冲突均已按件稳定排序）。
     */
    static DiffView toDiffView(long baseVersion, long leftVersion, long rightVersion,
                               Outcome outcome) {
        List<DiffItemView> items = outcome.items().stream()
                .map(i -> new DiffItemView(i.itemKey(), i.kind(), i.change()))
                .toList();
        List<TaskConflictView> taskConflicts = outcome.taskConflicts().stream()
                .map(c -> new TaskConflictView(c.conflictKey(), c.type().name(), c.taskId(),
                        toTaskView(c.base()), toTaskView(c.left()), toTaskView(c.right())))
                .toList();
        List<EdgeConflictView> edgeConflicts = outcome.edgeConflicts().stream()
                .map(c -> new EdgeConflictView(c.conflictKey(), c.type().name(),
                        toEdgeView(c.leftEdge()), toEdgeView(c.rightEdge())))
                .toList();
        return new DiffView(baseVersion, leftVersion, rightVersion, items, taskConflicts,
                edgeConflicts);
    }
}
