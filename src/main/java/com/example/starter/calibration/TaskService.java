package com.example.starter.calibration;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 接收任务业务。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final BindingRepository bindingRepository;
    private final AntennaRepository antennaRepository;
    private final BindingViews bindingViews;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository,
            BindingRepository bindingRepository,
            AntennaRepository antennaRepository,
            BindingViews bindingViews,
            Clock clock) {
        this.taskRepository = taskRepository;
        this.bindingRepository = bindingRepository;
        this.antennaRepository = antennaRepository;
        this.bindingViews = bindingViews;
        this.clock = clock;
    }

    /** 创建接收任务。 */
    @Transactional
    public TaskView createTask(CreateTaskRequest request) {
        long antennaId = antennaRepository.findIdByCode(request.antennaCode())
                .orElseThrow(() -> ApiException.unprocessable("ANTENNA_UNKNOWN",
                        "天线编号不存在: " + request.antennaCode()));
        if (!request.endTime().isAfter(request.startTime())) {
            throw ApiException.unprocessable("TASK_WINDOW_INVALID", "任务结束时刻必须晚于开始时刻");
        }
        ReceiveTask task = new ReceiveTask(null, request.taskCode(), antennaId,
                request.startTime(), request.endTime(), TaskStatus.SCHEDULED);
        long id = taskRepository.insert(task, Instant.now(clock));
        return getTask(id);
    }

    /** 查询任务及其绑定版本。 */
    @Transactional(readOnly = true)
    public TaskView getTask(long id) {
        ReceiveTask task = taskRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + id));
        String antennaCode = antennaRepository.findCodeById(task.antennaId()).orElseThrow(
                () -> ApiException.conflict("DATA_INCONSISTENT", "任务引用的天线不存在: " + task.antennaId()));
        return ViewAssembler.toTaskView(task, antennaCode,
                bindingViews.ofAll(bindingRepository.findByTaskId(id)));
    }

    /** 标记任务完成。 */
    @Transactional
    public TaskView completeTask(long id) {
        ReceiveTask task = taskRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + id));
        if (task.status() == TaskStatus.COMPLETED) {
            throw ApiException.conflict("TASK_ALREADY_COMPLETED", "任务已完成: " + id);
        }
        taskRepository.markCompleted(id);
        return getTask(id);
    }
}