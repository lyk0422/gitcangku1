package com.example.starter.incident;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.example.starter.incident.dto.Responses.TaskBlockerView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.springframework.stereotype.Component;

/**
 * 任务视图组装：阻塞状态按目标事件查询时的当前状态计算，不写回依赖任务。
 * 供 IncidentService 与 EvacuationService 共用。
 */
@Component
public class TaskViewMapper {

    /** 视为阻塞已解除的目标事件状态。 */
    public static final Set<IncidentStatus> UNBLOCKING_STATUSES = EnumSet.of(
            IncidentStatus.CONTAINED, IncidentStatus.RESOLVED, IncidentStatus.CLOSED);

    private final IncidentRepository incidents;

    public TaskViewMapper(IncidentRepository incidents) {
        this.incidents = incidents;
    }

    /**
     * 组装任务视图：阻塞状态按目标事件查询时的当前状态计算，不写回依赖任务。
     */
    public TaskView toTaskView(IncidentTask task) {
        List<TaskBlockerView> blockers = incidents.listBlockingIncidents(task.id()).stream()
                .map(b -> new TaskBlockerView(b.incidentKey(), b.status().name(),
                        UNBLOCKING_STATUSES.contains(b.status())))
                .toList();
        return new TaskView(task.taskKey(), task.groupCode(), task.title(), task.status().name(),
                blockers, task.highRisk(), task.workGrids(), task.finalPosition(),
                task.createdBy(), task.createdAt(), task.startedBy(), task.startedAt(),
                task.doneBy(), task.doneAt(), task.cancelledBy(), task.cancelledAt(),
                task.evacuatedBy(), task.evacuatedAt());
    }
}
