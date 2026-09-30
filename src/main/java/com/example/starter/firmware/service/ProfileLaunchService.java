package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CreateLaunchRequest;
import com.example.starter.firmware.api.DeviceTasksView;
import com.example.starter.firmware.api.LaunchExclusionView;
import com.example.starter.firmware.api.LaunchStatsView;
import com.example.starter.firmware.api.LaunchTaskView;
import com.example.starter.firmware.api.LaunchView;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.DeviceProfile;
import com.example.starter.firmware.domain.FirmwareVersion;
import com.example.starter.firmware.domain.ProfileCompatibility;
import com.example.starter.firmware.domain.ProfileLaunch;
import com.example.starter.firmware.domain.ProfileTask;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceProfileRepository;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.ProfileExclusionRepository;
import com.example.starter.firmware.repo.ProfileLaunchRepository;
import com.example.starter.firmware.repo.ProfileTaskRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 画像投放：发布人选择固件版本后，按设备当次冻结画像筛选目标。
 * 型号匹配、内存达标且包含全部必需标签才创建任务；不兼容设备只记录排除原因，不计入失败率。
 * 筛选在同一事务内对画像加行锁读取，画像更新与任务创建竞争时每个任务只引用一个完整画像版本。
 * 同一 requestId 重放首次目标清单，换版本（异参）返回 409。
 */
@Service
public class ProfileLaunchService {

    private final ProfileLaunchRepository launchRepository;
    private final ProfileTaskRepository taskRepository;
    private final ProfileExclusionRepository exclusionRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceProfileRepository profileRepository;
    private final FirmwareVersionService versionService;
    private final DeviceService deviceService;
    private final IdempotencyService idempotency;

    public ProfileLaunchService(ProfileLaunchRepository launchRepository,
                                ProfileTaskRepository taskRepository,
                                ProfileExclusionRepository exclusionRepository,
                                DeviceRepository deviceRepository,
                                DeviceProfileRepository profileRepository,
                                FirmwareVersionService versionService, DeviceService deviceService,
                                IdempotencyService idempotency) {
        this.launchRepository = launchRepository;
        this.taskRepository = taskRepository;
        this.exclusionRepository = exclusionRepository;
        this.deviceRepository = deviceRepository;
        this.profileRepository = profileRepository;
        this.versionService = versionService;
        this.deviceService = deviceService;
        this.idempotency = idempotency;
    }

    /**
     * 创建投放：冻结当次画像逐台评估，兼容设备建任务，不兼容设备落排除记录。
     */
    public LaunchView launch(CreateLaunchRequest request) {
        String fingerprint = String.join("|", "profile.launch.create",
                String.valueOf(request.versionId()));
        return idempotency.execute(request.requestId(), "profile.launch.create", fingerprint, () -> {
            FirmwareVersion version = versionService.findVersion(request.versionId());
            long launchId = launchRepository.insert(request.requestId(), request.versionId());
            Map<String, DeviceProfile> profiles = profileRepository.findAllForUpdate().stream()
                    .collect(Collectors.toMap(DeviceProfile::deviceId, Function.identity()));
            for (Device device : deviceRepository.findAll()) {
                DeviceProfile profile = profiles.get(device.deviceId());
                String reason = ProfileCompatibility.incompatibilityReason(version, profile);
                if (reason == null) {
                    taskRepository.insert(launchId, device.deviceId(), profile.profileVersion());
                } else {
                    exclusionRepository.insert(launchId, device.deviceId(),
                            profile == null ? null : profile.profileVersion(), reason);
                }
            }
            return buildView(launchId, version);
        }, LaunchView.class);
    }

    /**
     * 发布人查询投放单：兼容设备任务（含冻结画像版本）、排除设备及原因、失败率统计。
     */
    public LaunchView getLaunch(long launchId) {
        ProfileLaunch launch = findLaunch(launchId);
        FirmwareVersion version = versionService.findVersion(launch.versionId());
        return buildView(launchId, version);
    }

    /**
     * 设备端查询：只看自己的任务，每条携带筛选时冻结的画像版本。
     */
    public DeviceTasksView listDeviceTasks(String deviceId) {
        deviceService.findDevice(deviceId);
        List<LaunchTaskView> tasks = taskRepository.findByDevice(deviceId).stream()
                .map(LaunchTaskView::of)
                .toList();
        return new DeviceTasksView(deviceId, tasks);
    }

    /**
     * 任务回执：首次回执终结任务；同结果重复成功（不重复计数），改结果 409。
     */
    public LaunchTaskView receipt(long taskId, ReceiptRequest request) {
        String fingerprint = String.join("|", "profile.task.receipt", String.valueOf(taskId),
                request.result().name());
        return idempotency.execute(request.requestId(), "profile.task.receipt", fingerprint, () -> {
            ProfileTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            return switch (task.status()) {
                case PENDING -> {
                    taskRepository.complete(taskId, request.result());
                    yield LaunchTaskView.of(taskRepository.findById(taskId).orElseThrow());
                }
                case SUCCESS, FAILED -> {
                    if (task.firstResult() == request.result()) {
                        yield LaunchTaskView.of(task);
                    }
                    throw ApiException.conflict("RECEIPT_RESULT_CONFLICT",
                            "任务已终结为 " + task.firstResult() + "，不能改为 " + request.result());
                }
                case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，回执不再受理");
            };
        }, LaunchTaskView.class);
    }

    public ProfileLaunch findLaunch(long launchId) {
        return launchRepository.findById(launchId)
                .orElseThrow(() -> ApiException.notFound("LAUNCH_NOT_FOUND", "投放单不存在: " + launchId));
    }

    private LaunchView buildView(long launchId, FirmwareVersion version) {
        List<LaunchTaskView> tasks = taskRepository.findByLaunch(launchId).stream()
                .map(LaunchTaskView::of)
                .toList();
        List<LaunchExclusionView> exclusions = exclusionRepository.findByLaunch(launchId).stream()
                .map(LaunchExclusionView::of)
                .toList();
        LaunchStatsView stats = LaunchStatsView.of(tasks.size(),
                taskRepository.countByLaunchAndStatus(launchId, TaskStatus.SUCCESS),
                taskRepository.countByLaunchAndStatus(launchId, TaskStatus.FAILED),
                exclusions.size());
        return new LaunchView(launchId, version.id(), version.name(), tasks, exclusions, stats);
    }
}
