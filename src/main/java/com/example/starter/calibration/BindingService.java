package com.example.starter.calibration;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务绑定业务：有效期校验、重叠占用判定、幂等与并发控制。
 */
@Service
public class BindingService {

    private final BindingRepository bindingRepository;
    private final TaskRepository taskRepository;
    private final CalibrationRepository calibrationRepository;
    private final AntennaRepository antennaRepository;
    private final BindingViews bindingViews;
    private final Clock clock;

    public BindingService(BindingRepository bindingRepository,
            TaskRepository taskRepository,
            CalibrationRepository calibrationRepository,
            AntennaRepository antennaRepository,
            BindingViews bindingViews,
            Clock clock) {
        this.bindingRepository = bindingRepository;
        this.taskRepository = taskRepository;
        this.calibrationRepository = calibrationRepository;
        this.antennaRepository = antennaRepository;
        this.bindingViews = bindingViews;
        this.clock = clock;
    }

    /**
     * 绑定任务到校准。
     * 任务区间必须完全落在校准有效期内；同一天线重叠占用返回409；
     * requestId 同参重放返回原绑定，异参返回409；失败不占键。
     */
    @Transactional
    public BindingView bind(CreateBindingRequest request) {
        Optional<TaskBinding> replay = bindingRepository.findByRequestId(request.requestId());
        if (replay.isPresent()) {
            return resolveReplay(request, replay.get());
        }
        ReceiveTask task = taskRepository.findById(request.taskId())
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND",
                        "任务不存在: " + request.taskId()));
        antennaRepository.lockById(task.antennaId());
        Optional<TaskBinding> replayAfterLock = bindingRepository.findByRequestId(request.requestId());
        if (replayAfterLock.isPresent()) {
            return resolveReplay(request, replayAfterLock.get());
        }
        AntennaCalibration calibration = calibrationRepository.findByIdForUpdate(request.calibrationId())
                .orElseThrow(() -> ApiException.notFound("CALIBRATION_NOT_FOUND",
                        "校准记录不存在: " + request.calibrationId()));
        validateBindable(task, calibration);

        Optional<TaskBinding> current = bindingRepository.findCurrentByTaskId(task.id());
        if (current.isPresent() && current.get().status() == BindingStatus.BOUND) {
            throw ApiException.conflict("TASK_ALREADY_BOUND",
                    "任务已存在生效绑定: " + request.taskId());
        }
        List<TaskBinding> overlaps = bindingRepository.findActiveOverlapping(
                task.antennaId(), task.startTime(), task.endTime());
        if (!overlaps.isEmpty()) {
            throw ApiException.conflict("ANTENNA_OCCUPIED",
                    "天线在该时段已被绑定占用: bindingId=" + overlaps.get(0).id());
        }
        current.ifPresent(old -> bindingRepository.markSuperseded(old.id()));

        TaskBinding binding = new TaskBinding(null, request.requestId(), task.id(), calibration.id(),
                task.antennaId(), calibration.azimuthDeviationDeg(), calibration.elevationDeviationDeg(),
                BindingStatus.BOUND, false, Instant.now(clock));
        long id;
        try {
            id = bindingRepository.insert(binding);
        } catch (DuplicateKeyException ex) {
            return resolveReplay(request, bindingRepository.findByRequestId(request.requestId())
                    .orElseThrow(() -> ApiException.conflict("REQUEST_ID_CONFLICT",
                            "幂等键冲突且无法读取已存在绑定: " + request.requestId())));
        }
        return bindingViews.of(bindingRepository.findByRequestId(request.requestId())
                .orElseThrow(() -> ApiException.conflict("DATA_INCONSISTENT",
                        "绑定写入后读取失败: " + id)));
    }

    /** 按幂等键查询绑定。 */
    @Transactional(readOnly = true)
    public BindingView getByRequestId(String requestId) {
        TaskBinding binding = bindingRepository.findByRequestId(requestId)
                .orElseThrow(() -> ApiException.notFound("BINDING_NOT_FOUND",
                        "绑定不存在: " + requestId));
        return bindingViews.of(binding);
    }

    private BindingView resolveReplay(CreateBindingRequest request, TaskBinding existing) {
        if (existing.taskId() == request.taskId() && existing.calibrationId() == request.calibrationId()) {
            return bindingViews.of(existing);
        }
        throw ApiException.conflict("REQUEST_ID_CONFLICT",
                "幂等键已使用且参数不一致: " + request.requestId());
    }

    private void validateBindable(ReceiveTask task, AntennaCalibration calibration) {
        if (calibration.antennaId() != task.antennaId()) {
            throw ApiException.unprocessable("ANTENNA_MISMATCH", "校准与任务不属于同一天线");
        }
        if (calibration.status() == CalibrationStatus.INVALIDATED) {
            throw ApiException.unprocessable("CALIBRATION_INVALIDATED",
                    "校准记录已作废: " + calibration.id());
        }
        if (!calibration.passed()) {
            throw ApiException.unprocessable("CALIBRATION_NOT_PASSED",
                    "校准未通过，无有效期: " + calibration.id());
        }
        if (task.startTime().isBefore(calibration.validFrom())
                || task.endTime().isAfter(calibration.validUntil())) {
            throw ApiException.unprocessable("TASK_WINDOW_OUTSIDE_VALIDITY",
                    "任务区间未完全落在校准有效期内");
        }
    }
}