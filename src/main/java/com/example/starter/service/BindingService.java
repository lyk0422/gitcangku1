package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;

import com.example.starter.domain.ApiException;
import com.example.starter.domain.CalibrationRecord;
import com.example.starter.domain.CalibrationStatus;
import com.example.starter.domain.ReceiveTask;
import com.example.starter.domain.TaskBinding;
import com.example.starter.repository.AntennaRepository;
import com.example.starter.repository.BindingRepository;
import com.example.starter.repository.CalibrationRepository;
import com.example.starter.repository.TaskRepository;
import com.example.starter.web.dto.BindRequest;
import com.example.starter.web.dto.CreateTaskRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务与绑定服务：任务登记、幂等绑定、有效期与重叠占用校验。
 */
@Service
public class BindingService {

    private final TaskRepository taskRepository;
    private final BindingRepository bindingRepository;
    private final CalibrationRepository calibrationRepository;
    private final AntennaRepository antennaRepository;
    private final Clock clock;

    public BindingService(TaskRepository taskRepository, BindingRepository bindingRepository,
            CalibrationRepository calibrationRepository, AntennaRepository antennaRepository,
            Clock clock) {
        this.taskRepository = taskRepository;
        this.bindingRepository = bindingRepository;
        this.calibrationRepository = calibrationRepository;
        this.antennaRepository = antennaRepository;
        this.clock = clock;
    }

    /** 登记接收任务：结束时刻必须晚于开始时刻。 */
    @Transactional
    public ReceiveTask createTask(CreateTaskRequest request) {
        if (!request.endAt().isAfter(request.startAt())) {
            throw ApiException.unprocessable("TASK_INTERVAL_INVALID",
                    "任务结束时刻必须晚于开始时刻");
        }
        long antennaId = antennaRepository.findIdByCode(request.antennaCode())
                .orElseThrow(() -> ApiException.notFound("ANTENNA_NOT_FOUND",
                        "天线不存在: " + request.antennaCode()));
        long id = taskRepository.insert(request.taskCode(), antennaId, request.startAt(),
                request.endAt(), Instant.now(clock));
        return taskRepository.findById(id).orElseThrow();
    }

    /** 查询任务。 */
    @Transactional(readOnly = true)
    public ReceiveTask getTask(long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
    }

    /**
     * 绑定任务到校准记录：requestId 同参重放返回原绑定、异参 409；
     * 校验校准通过、未作废、任务区间落在有效期内、天线无重叠占用，全部通过才落库，失败不占键。
     */
    @Transactional
    public TaskBinding bind(BindRequest request) {
        var replay = bindingRepository.findByRequestId(request.requestId());
        if (replay.isPresent()) {
            return resolveReplay(replay.get(), request);
        }
        ReceiveTask task = taskRepository.findById(request.taskId())
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND",
                        "任务不存在: " + request.taskId()));
        // 与作废共用天线行锁，串行化并发绑定与作废竞争。
        antennaRepository.lockById(task.antennaId());
        CalibrationRecord calibration = calibrationRepository.findById(request.calibrationId())
                .orElseThrow(() -> ApiException.notFound("CALIBRATION_NOT_FOUND",
                        "校准记录不存在: " + request.calibrationId()));
        if (calibration.antennaId() != task.antennaId()) {
            throw ApiException.unprocessable("ANTENNA_MISMATCH", "校准记录与任务不属于同一天线");
        }
        if (calibration.status() == CalibrationStatus.VOIDED) {
            throw ApiException.conflict("CALIBRATION_VOIDED", "校准记录已作废，不能用于绑定");
        }
        if (!calibration.passed()) {
            throw ApiException.unprocessable("CALIBRATION_NOT_PASSED", "校准未通过，不能用于绑定");
        }
        if (task.startAt().isBefore(calibration.calibratedAt())
                || task.endAt().isAfter(calibration.validUntil())) {
            throw ApiException.unprocessable("TASK_OUTSIDE_VALIDITY",
                    "任务起止区间未完全落在校准有效期内");
        }
        var existingForTask = bindingRepository.findByTaskId(task.id());
        if (existingForTask.isPresent()
                && existingForTask.get().status() == com.example.starter.domain.BindingStatus.BOUND) {
            throw ApiException.conflict("TASK_ALREADY_BOUND", "任务已存在有效绑定");
        }
        if (bindingRepository.countOverlapping(task.antennaId(), task.startAt(), task.endAt()) > 0) {
            throw ApiException.conflict("ANTENNA_OVERLAP", "任务区间与已有绑定重叠占用同一天线");
        }
        Instant now = Instant.now(clock);
        try {
            long id = bindingRepository.insert(request.requestId(), task.id(), task.antennaId(),
                    calibration.id(), calibration.azimuthDeviation(), calibration.elevationDeviation(),
                    calibration.validUntil(), now);
            return bindingRepository.findById(id).orElseThrow();
        } catch (DuplicateKeyException duplicate) {
            // 并发下同 requestId 已提交：按已提交内容裁决同参重放或异参冲突。
            TaskBinding committed = bindingRepository.findByRequestId(request.requestId())
                    .orElseThrow(() -> duplicate);
            return resolveReplay(committed, request);
        }
    }

    /** 按 requestId 查询绑定。 */
    @Transactional(readOnly = true)
    public TaskBinding getByRequestId(String requestId) {
        return bindingRepository.findByRequestId(requestId)
                .orElseThrow(() -> ApiException.notFound("BINDING_NOT_FOUND",
                        "绑定不存在: " + requestId));
    }

    private TaskBinding resolveReplay(TaskBinding committed, BindRequest request) {
        if (committed.taskId() == request.taskId()
                && committed.calibrationId() == request.calibrationId()) {
            return committed;
        }
        throw ApiException.conflict("REQUEST_ID_CONFLICT",
                "requestId 已使用且参数不一致: " + request.requestId());
    }

    /** 按 requestId 查询绑定，不存在时返回空（用于校验失败不占键）。 */
    @Transactional(readOnly = true)
    public java.util.Optional<TaskBinding> getByRequestIdOptional(String requestId) {
        return bindingRepository.findByRequestId(requestId);
    }}
