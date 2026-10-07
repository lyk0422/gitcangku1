package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.starter.incident.IncidentSchedule.Snapshot;
import com.example.starter.incident.TemporalNetworkSolver.TaskTiming;
import com.example.starter.incident.TemporalNetworkSolver.TemporalConstraint;
import com.example.starter.incident.dto.Requests.ScheduleConstraintRequest;
import com.example.starter.incident.dto.Requests.ScheduleProposeRequest;
import com.example.starter.incident.dto.Requests.ScheduleTaskRequest;
import com.example.starter.incident.dto.Responses.ScheduleConstraintView;
import com.example.starter.incident.dto.Responses.ScheduleHistoryView;
import com.example.starter.incident.dto.Responses.ScheduleView;
import com.example.starter.incident.dto.Responses.ScheduledTaskView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 整案排程提案服务。
 * 并发约定：与事件其他写操作相同，先 SELECT ... FOR UPDATE 锁定事件行，
 * 提案提交与任务完成/取消/指挥交接在同一事件上形成合法串行结果，
 * 不会保存混合状态的 CURRENT 方案。
 * 幂等约定：requestId 全局唯一，结构化比较操作、资源、全参数及期望版本
 * （十进制按值、文本原样，操作者不参与参数比较）；同参重放首次完整结果、
 * 改参 409、失败不占键；当前指挥人权限校验先于重放。
 * 可行性：全部任务时间窗与有向约束整体建模为差分约束系统判定，
 * 不可行 422 且不留部分排程；提案不实际执行任务，不替换原阻塞事件规则。
 */
@Service
public class ScheduleService {

    /** 每提案任务上限。 */
    private static final int MAX_TASKS_PER_SCHEDULE = 10;

    /** 每提案有向约束上限。 */
    private static final int MAX_CONSTRAINTS_PER_SCHEDULE = 20;

    /** 偏移/持续/约束界的上限（分钟），防止求解时数值溢出。 */
    private static final long MAX_MINUTES = 1_000_000_000_000L;

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final IncidentScheduleRepository schedules;
    private final CommandKeyIdempotency idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ScheduleService(IncidentRepository incidents, IncidentTaskRepository tasks,
                           IncidentScheduleRepository schedules, CommandKeyIdempotency idempotency,
                           ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.schedules = schedules;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 提交整案排程提案：仅当前指挥人；全部任务及约束同时存在可行时间赋值才可提交。
     * 版本从 1 递增；替换须携带 expectedScheduleVersion 等于当前最新版本，成功保留旧方案。
     * 提交时冻结任务状态、事件版本及完整约束。
     */
    @Transactional
    public ScheduleView propose(String incidentKey, String actor, ScheduleProposeRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        String actorId = requireText(actor, "actor");
        if (req.baseInstant() == null) {
            throw ApiException.badRequest("baseInstant 不能为空");
        }
        Instant base = req.baseInstant().truncatedTo(ChronoUnit.MICROS);
        List<ScheduleTaskRequest> taskReqs = validateTasks(req.tasks());
        List<ScheduleConstraintRequest> constraintReqs =
                validateConstraints(req.constraints(), taskReqs);
        Long expected = req.expectedScheduleVersion();
        if (expected != null && expected < 0) {
            throw ApiException.badRequest("expectedScheduleVersion 不能为负数");
        }
        Incident incident = lockIncident(incidentKey);
        // 当前权限先于重放：非当前指挥人直接 409，不进入幂等重放
        requireCommander(incident, actorId);
        return idempotency.run(requestId, "schedule_propose",
                requestHash(incident.incidentKey(), base, expected, taskReqs, constraintReqs),
                ScheduleView.class,
                () -> doPropose(incident, actorId, base, expected, taskReqs, constraintReqs));
    }

    /**
     * 查询事件排程：current 为最新版本提案（无则 null），history 为全部版本升序。
     * 只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public ScheduleHistoryView listSchedules(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<ScheduleView> views = schedules.listByIncident(incident.id()).stream()
                .map(this::toView).toList();
        ScheduleView current = views.isEmpty() ? null : views.get(views.size() - 1);
        return new ScheduleHistoryView(current, views);
    }

    private ScheduleView doPropose(Incident incident, String actorId, Instant base, Long expected,
                                   List<ScheduleTaskRequest> taskReqs,
                                   List<ScheduleConstraintRequest> constraintReqs) {
        // 引用任务必须存在且为 OPEN（非 OPEN 409）
        List<IncidentTask> referenced = new ArrayList<>();
        for (ScheduleTaskRequest taskReq : taskReqs) {
            IncidentTask task = tasks.findByKey(incident.id(), taskReq.taskKey())
                    .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskReq.taskKey()));
            if (task.status() != TaskStatus.OPEN) {
                throw ApiException.conflict("任务 " + taskReq.taskKey() + " 已处于 "
                        + task.status() + "，仅 OPEN 任务可纳入排程");
            }
            referenced.add(task);
        }
        // 版本校验：首版无需期望版本；替换必须携带且等于当前最新版本
        var latest = schedules.findLatest(incident.id());
        int currentVersion = latest.map(IncidentSchedule::scheduleVersion).orElse(0);
        if (currentVersion == 0) {
            if (expected != null && expected != 0) {
                throw ApiException.conflict("当前尚无排程提案，expectedScheduleVersion 必须省略或为 0");
            }
        } else if (expected == null || expected != currentVersion) {
            throw ApiException.conflict("替换排程须携带 expectedScheduleVersion=" + currentVersion
                    + "，实际为 " + (expected == null ? "未提供" : expected));
        }
        // 整体可行性：所有任务与约束同时存在可行时间赋值才可提交
        List<TaskTiming> timings = taskReqs.stream()
                .map(t -> new TaskTiming(t.taskKey(), t.durationMinutes(),
                        t.earliestStartOffset(), t.latestFinishOffset()))
                .toList();
        List<TemporalConstraint> constraints = constraintReqs.stream()
                .map(c -> new TemporalConstraint(c.fromTaskKey(), c.toTaskKey(),
                        c.lowerBound(), c.upperBound()))
                .toList();
        if (!TemporalNetworkSolver.isFeasible(timings, constraints)) {
            throw ApiException.infeasibleSchedule(
                    "任务时间窗与有向约束不存在同时可行的时间赋值，提案整体拒绝");
        }
        // 冻结任务状态、事件版本及完整约束；旧 CURRENT 方案标为 STALE 但历史保留
        long stateVersion = schedules.currentStateVersion(incident.id());
        List<ScheduledTaskView> taskViews = new ArrayList<>();
        for (int i = 0; i < taskReqs.size(); i++) {
            ScheduleTaskRequest t = taskReqs.get(i);
            taskViews.add(new ScheduledTaskView(t.taskKey(), referenced.get(i).status().name(),
                    t.durationMinutes(), t.earliestStartOffset(), t.latestFinishOffset()));
        }
        List<ScheduleConstraintView> constraintViews = constraintReqs.stream()
                .map(c -> new ScheduleConstraintView(c.fromTaskKey(), c.toTaskKey(),
                        c.lowerBound(), c.upperBound()))
                .toList();
        Snapshot snapshot = new Snapshot(stateVersion, taskViews, constraintViews);
        Instant now = clock.instant();
        schedules.markCurrentStale(incident.id());
        int newVersion = currentVersion + 1;
        schedules.insert(new IncidentSchedule(0L, incident.id(), newVersion,
                ScheduleStatus.CURRENT, base, toJson(snapshot), actorId, now));
        return new ScheduleView(newVersion, ScheduleStatus.CURRENT.name(), base, stateVersion,
                taskViews, constraintViews, actorId, now);
    }

    private List<ScheduleTaskRequest> validateTasks(List<ScheduleTaskRequest> taskReqs) {
        if (taskReqs == null || taskReqs.isEmpty()) {
            throw ApiException.badRequest("tasks 不能为空");
        }
        if (taskReqs.size() > MAX_TASKS_PER_SCHEDULE) {
            throw ApiException.badRequest("排程任务最多 " + MAX_TASKS_PER_SCHEDULE + " 个");
        }
        Set<String> seen = new HashSet<>();
        for (ScheduleTaskRequest t : taskReqs) {
            if (t == null) {
                throw ApiException.badRequest("tasks 不能包含空条目");
            }
            String taskKey = requireText(t.taskKey(), "taskKey");
            if (!seen.add(taskKey)) {
                throw ApiException.badRequest("任务集合不得重复: " + taskKey);
            }
            requireMinutes(t.durationMinutes(), "durationMinutes", 1);
            requireMinutes(t.earliestStartOffset(), "earliestStartOffset", 0);
            requireMinutes(t.latestFinishOffset(), "latestFinishOffset", 0);
        }
        return taskReqs;
    }

    private List<ScheduleConstraintRequest> validateConstraints(
            List<ScheduleConstraintRequest> constraintReqs, List<ScheduleTaskRequest> taskReqs) {
        List<ScheduleConstraintRequest> effective = constraintReqs == null ? List.of()
                : constraintReqs;
        if (effective.size() > MAX_CONSTRAINTS_PER_SCHEDULE) {
            throw ApiException.badRequest("有向约束最多 " + MAX_CONSTRAINTS_PER_SCHEDULE + " 条");
        }
        Set<String> taskKeys = new HashSet<>();
        for (ScheduleTaskRequest t : taskReqs) {
            taskKeys.add(t.taskKey().strip());
        }
        for (ScheduleConstraintRequest c : effective) {
            if (c == null) {
                throw ApiException.badRequest("constraints 不能包含空条目");
            }
            String from = requireText(c.fromTaskKey(), "fromTaskKey");
            String to = requireText(c.toTaskKey(), "toTaskKey");
            if (!taskKeys.contains(from) || !taskKeys.contains(to)) {
                throw ApiException.badRequest("约束引用了提案任务集合之外的任务: " + from + " -> " + to);
            }
            long lower = requireMinutes(c.lowerBound(), "lowerBound", 0);
            long upper = requireMinutes(c.upperBound(), "upperBound", 0);
            if (lower > upper) {
                throw ApiException.badRequest("约束下界不能大于上界: " + lower + " > " + upper);
            }
        }
        return effective;
    }

    private static long requireMinutes(Long value, String field, long min) {
        if (value == null) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        if (value < min || value > MAX_MINUTES) {
            throw ApiException.badRequest(field + " 必须在 [" + min + ", " + MAX_MINUTES + "] 内");
        }
        return value;
    }

    /**
     * 结构化参数摘要：操作、资源（事件）、全参数及期望版本；十进制按值、文本原样；
     * 操作者不参与参数比较（权限在校验阶段先行处理）。
     */
    private static String requestHash(String incidentKey, Instant base, Long expected,
                                      List<ScheduleTaskRequest> taskReqs,
                                      List<ScheduleConstraintRequest> constraintReqs) {
        List<String> parts = new ArrayList<>();
        parts.add(incidentKey);
        parts.add(base.toString());
        parts.add(String.valueOf(expected));
        for (ScheduleTaskRequest t : taskReqs) {
            parts.add(t.taskKey().strip());
            parts.add(String.valueOf(t.durationMinutes()));
            parts.add(String.valueOf(t.earliestStartOffset()));
            parts.add(String.valueOf(t.latestFinishOffset()));
        }
        for (ScheduleConstraintRequest c : constraintReqs) {
            parts.add(c.fromTaskKey().strip());
            parts.add(c.toTaskKey().strip());
            parts.add(String.valueOf(c.lowerBound()));
            parts.add(String.valueOf(c.upperBound()));
        }
        return CommandKeyIdempotency.hash(parts.toArray(new String[0]));
    }

    private ScheduleView toView(IncidentSchedule schedule) {
        Snapshot snapshot;
        try {
            snapshot = objectMapper.readValue(schedule.snapshot(), Snapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("排程快照反序列化失败", e);
        }
        return new ScheduleView(schedule.scheduleVersion(), schedule.status().name(),
                schedule.baseInstant(), snapshot.stateVersion(), snapshot.tasks(),
                snapshot.constraints(), schedule.createdBy(), schedule.createdAt());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("排程快照序列化失败", e);
        }
    }

    private Incident lockIncident(String incidentKey) {
        requireText(incidentKey, "incidentKey");
        return incidents.lockByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
    }

    private static void requireCommander(Incident incident, String actor) {
        if (incident.commander() == null || !incident.commander().equals(actor)) {
            throw ApiException.conflict("只有当前指挥人 "
                    + (incident.commander() == null ? "(无)" : incident.commander()) + " 能执行该操作");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }
}
