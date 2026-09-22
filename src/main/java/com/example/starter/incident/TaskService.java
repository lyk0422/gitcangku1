package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.BlockedIncidentView;
import com.example.starter.incident.dto.Responses.TaskGroupView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分组处置任务服务。
 *
 * <p>并发约定：任务写操作先锁定所属事件行；创建任务因涉及跨事件依赖图，
 * 在同事务内额外锁定 task_graph_lock 固定单行，使“环检测 + 任务/边写入”全局串行一致，
 * 最终依赖图不会出现有向环。任务完成与目标事件遏制、当前事件解决之间依靠
 * 各自的事件行锁与条件更新，按事务提交顺序形成合法结果。</p>
 *
 * <p>阻塞解除不写回：阻塞边一经创建不再变更，任务可否完成在操作/查询时按目标事件
 * 当前状态实时计算；事件状态机不允许回退，已完成任务不会因目标状态变化重新打开。</p>
 */
@Service
public class TaskService {

    /** 每个处置任务允许的阻塞事件上限。 */
    private static final int MAX_BLOCKS = 5;

    /** 每个事件允许的处置任务上限。 */
    private static final int MAX_TASKS_PER_INCIDENT = 20;

    /** 阻塞解除（任务可完成）要求目标事件所处的状态集合。 */
    private static final Set<IncidentStatus> LIFTED_STATUSES = Set.of(
            IncidentStatus.CONTAINED, IncidentStatus.RESOLVED, IncidentStatus.CLOSED);

    private final IncidentRepository incidents;
    private final TaskRepository tasks;
    private final IdempotentExecutor idempotent;
    private final Clock clock;

    public TaskService(IncidentRepository incidents, TaskRepository tasks,
                       IdempotentExecutor idempotent, Clock clock) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.idempotent = idempotent;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 创建处置任务：仅操作时的当前指挥人可创建，事件未关闭；
     * 每事件至多 20 个任务，taskKey 事件内唯一，groupCode/title 非空，
     * blockedIncidentKeys 为 0～5 个存在且非自身的事件。
     * 新边（当前事件 → 各阻塞目标）不得与已有边构成直接或间接环，
     * 否则 409 且不留下部分任务或边（整事务回滚）。
     */
    @Transactional
    public TaskView createTask(String incidentKey, String actor, TaskCreateRequest req) {
        String commandKey = IncidentService.requireText(req.commandKey(), "commandKey");
        String taskKey = IncidentService.requireText(req.taskKey(), "taskKey");
        String groupCode = IncidentService.requireText(req.groupCode(), "groupCode");
        String title = IncidentService.requireText(req.title(), "title");
        List<String> blockedKeys = normalizeBlockedKeys(req.blockedIncidentKeys());

        Incident incident = lockIncident(incidentKey);
        String blocksHash = String.join(",", blockedKeys);
        return idempotent.run(commandKey, "task_create",
                IdempotentExecutor.hash(incidentKey, actor, taskKey, groupCode, title, blocksHash),
                TaskView.class, () -> {
                    IncidentService.requireCommander(incident, actor);
                    if (incident.status() == IncidentStatus.CLOSED) {
                        throw ApiException.illegalTransition("事件已关闭，不能再创建处置任务");
                    }
                    if (tasks.findTask(incident.id(), taskKey).isPresent()) {
                        throw ApiException.conflict("taskKey 已存在: " + taskKey);
                    }
                    if (tasks.countTasksByIncident(incident.id()) >= MAX_TASKS_PER_INCIDENT) {
                        throw ApiException.conflict(
                                "每个事件最多创建 " + MAX_TASKS_PER_INCIDENT + " 个处置任务");
                    }

                    // 锁定全局图锁后再读取已提交的最新依赖图，串行化环检测与边写入。
                    tasks.lockGraph();
                    List<Incident> targets = resolveTargets(incident, blockedKeys);
                    if (createsCycle(incident.id(), targets)) {
                        throw ApiException.conflict("该依赖会形成跨事件阻塞环，已拒绝创建任务");
                    }

                    Instant now = now();
                    Task task = new Task(0L, incident.id(), taskKey, groupCode, title, TaskStatus.OPEN,
                            actor, now, now, null, null);
                    long taskId;
                    try {
                        taskId = tasks.insertTask(task);
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("taskKey 已存在: " + taskKey);
                    }
                    for (Incident target : targets) {
                        tasks.insertBlock(new TaskBlock(0L, taskId, target.id(), target.incidentKey()));
                    }
                    return toView(tasks.findTaskById(taskId).orElseThrow());
                });
    }

    /**
     * 完成任务：仅当前指挥人可操作；OPEN 任务只有在全部阻塞事件均处于
     * CONTAINED/RESOLVED/CLOSED 时才能完成，否则 409 并返回仍未解除的事件键列表。
     * 已是 DONE 的同类重复完成按幂等规则返回首次结果；对 CANCELLED 任务完成返回 409。
     */
    @Transactional
    public TaskView completeTask(String incidentKey, String taskKey, String actor, TaskActionRequest req) {
        String commandKey = IncidentService.requireText(req.commandKey(), "commandKey");
        String normalizedTaskKey = IncidentService.requireText(taskKey, "taskKey");
        Incident incident = lockIncident(incidentKey);
        return idempotent.run(commandKey, "task_complete",
                IdempotentExecutor.hash(incidentKey, normalizedTaskKey, actor),
                TaskView.class, () -> {
                    IncidentService.requireCommander(incident, actor);
                    Task task = tasks.findTask(incident.id(), normalizedTaskKey)
                            .orElseThrow(() -> ApiException.notFound(
                                    "任务不存在: " + normalizedTaskKey));
                    if (task.status() == TaskStatus.DONE) {
                        // 终态同类重复操作：返回首次结果。
                        return toView(task);
                    }
                    if (task.status() == TaskStatus.CANCELLED) {
                        throw ApiException.conflict("任务已取消，不能完成");
                    }
                    List<String> unlifted = unliftedBlockedKeys(task);
                    if (!unlifted.isEmpty()) {
                        throw ApiException.conflict("仍有阻塞事件未解除，任务不能完成", unlifted);
                    }
                    Instant now = now();
                    int updated = tasks.completeTask(task.id(), now);
                    if (updated == 0) {
                        throw ApiException.conflict("任务已被并发处理，不能完成");
                    }
                    return toView(tasks.findTaskById(task.id()).orElseThrow());
                });
    }

    /**
     * 取消任务：仅当前指挥人可操作，且仅 OPEN 可取消；
     * 已是 CANCELLED 的同类重复取消返回首次结果；对 DONE 任务取消返回 409。
     */
    @Transactional
    public TaskView cancelTask(String incidentKey, String taskKey, String actor, TaskActionRequest req) {
        String commandKey = IncidentService.requireText(req.commandKey(), "commandKey");
        String normalizedTaskKey = IncidentService.requireText(taskKey, "taskKey");
        Incident incident = lockIncident(incidentKey);
        return idempotent.run(commandKey, "task_cancel",
                IdempotentExecutor.hash(incidentKey, normalizedTaskKey, actor),
                TaskView.class, () -> {
                    IncidentService.requireCommander(incident, actor);
                    Task task = tasks.findTask(incident.id(), normalizedTaskKey)
                            .orElseThrow(() -> ApiException.notFound(
                                    "任务不存在: " + normalizedTaskKey));
                    if (task.status() == TaskStatus.CANCELLED) {
                        return toView(task);
                    }
                    if (task.status() == TaskStatus.DONE) {
                        throw ApiException.conflict("任务已完成，不能取消");
                    }
                    Instant now = now();
                    int updated = tasks.cancelTask(task.id(), now);
                    if (updated == 0) {
                        throw ApiException.conflict("任务已被并发处理，不能取消");
                    }
                    return toView(tasks.findTaskById(task.id()).orElseThrow());
                });
    }

    /**
     * 按事件查询任务并按 groupCode 分组；只读，不隐式写入。
     * 阻塞状态按各目标事件当前状态实时计算。
     */
    @Transactional(readOnly = true)
    public List<TaskGroupView> listTasks(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        Map<String, List<TaskView>> grouped = new LinkedHashMap<>();
        for (Task task : tasks.listTasksByIncident(incident.id())) {
            grouped.computeIfAbsent(task.groupCode(), g -> new ArrayList<>()).add(toView(task));
        }
        return grouped.entrySet().stream()
                .map(e -> new TaskGroupView(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * 查询单任务明细及实时阻塞状态；只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public TaskView getTask(String incidentKey, String taskKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        Task task = tasks.findTask(incident.id(), taskKey)
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
        return toView(task);
    }

    private Incident lockIncident(String incidentKey) {
        IncidentService.requireText(incidentKey, "incidentKey");
        return incidents.lockByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
    }

    private static List<String> normalizeBlockedKeys(List<String> raw) {
        List<String> keys = raw == null ? List.of() : raw;
        if (keys.size() > MAX_BLOCKS) {
            throw ApiException.badRequest("每个任务最多指定 " + MAX_BLOCKS + " 个阻塞事件");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String key : keys) {
            String normalized = IncidentService.requireText(key, "blockedIncidentKeys");
            if (!seen.add(normalized)) {
                throw ApiException.badRequest("阻塞事件不能重复: " + normalized);
            }
        }
        return new ArrayList<>(seen);
    }

    /**
     * 解析阻塞目标事件：必须存在且不能是任务所属事件自身。
     */
    private List<Incident> resolveTargets(Incident owner, List<String> blockedKeys) {
        List<Incident> targets = new ArrayList<>();
        for (String key : blockedKeys) {
            Incident target = incidents.findByKey(key)
                    .orElseThrow(() -> ApiException.notFound("阻塞事件不存在: " + key));
            if (target.id() == owner.id()) {
                throw ApiException.badRequest("阻塞事件不能是任务所属事件自身: " + key);
            }
            targets.add(target);
        }
        return targets;
    }

    /**
     * 环检测：加入 source→target 各边后，若任一目标在“已有图”中可沿边到达 source，
     * 则存在（直接或间接）环。目标之间不新增边，无需考虑目标彼此到达的情形。
     * 调用方已持有全局图锁，listEdges() 读到的是已提交最新图。
     */
    private boolean createsCycle(long sourceId, List<Incident> targets) {
        Map<Long, List<Long>> adjacency = new LinkedHashMap<>();
        for (TaskDependencyEdge edge : tasks.listEdges()) {
            adjacency.computeIfAbsent(edge.sourceIncidentId(), k -> new ArrayList<>())
                    .add(edge.blockedIncidentId());
        }
        for (Incident target : targets) {
            if (reaches(adjacency, target.id(), sourceId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在有向图中判断 from 是否能沿已有边到达 to（BFS）。
     */
    private static boolean reaches(Map<Long, List<Long>> adjacency, long from, long to) {
        if (from == to) {
            return true;
        }
        Set<Long> visited = new LinkedHashSet<>();
        List<Long> queue = new ArrayList<>();
        visited.add(from);
        queue.add(from);
        int head = 0;
        while (head < queue.size()) {
            long current = queue.get(head++);
            for (long next : adjacency.getOrDefault(current, List.of())) {
                if (next == to) {
                    return true;
                }
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return false;
    }

    /**
     * 返回任务阻塞边中当前仍未解除（目标事件不在 CONTAINED/RESOLVED/CLOSED）的事件键列表，保持边顺序。
     */
    private List<String> unliftedBlockedKeys(Task task) {
        List<String> unlifted = new ArrayList<>();
        for (TaskBlock block : tasks.listBlocksByTask(task.id())) {
            Incident target = incidents.findByKey(block.blockedIncidentKey()).orElse(null);
            if (target == null || !LIFTED_STATUSES.contains(target.status())) {
                unlifted.add(block.blockedIncidentKey());
            }
        }
        return unlifted;
    }

    private TaskView toView(Task task) {
        List<BlockedIncidentView> blockedViews = new ArrayList<>();
        boolean completable = true;
        for (TaskBlock block : tasks.listBlocksByTask(task.id())) {
            Incident target = incidents.findByKey(block.blockedIncidentKey()).orElse(null);
            boolean lifted = target != null && LIFTED_STATUSES.contains(target.status());
            if (!lifted) {
                completable = false;
            }
            blockedViews.add(new BlockedIncidentView(block.blockedIncidentKey(),
                    target == null ? null : target.status().name(), lifted));
        }
        return new TaskView(task.taskKey(), task.groupCode(), task.title(), task.status().name(),
                task.createdBy(), task.createdAt(), task.updatedAt(), task.completedAt(),
                task.cancelledAt(), blockedViews, completable);
    }
}
