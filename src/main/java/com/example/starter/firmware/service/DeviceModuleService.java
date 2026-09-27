package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceModulesView;
import com.example.starter.firmware.api.ModuleEntry;
import com.example.starter.firmware.api.ReportDeviceModulesRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.ModuleSnapshot;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceModuleRepository;
import com.example.starter.firmware.support.ModuleVersion;
import com.example.starter.firmware.support.UtcTimes;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 设备模块清单上报。清单为全量替换：先验证完整后态（设备存在、版本格式合法、模块名不重复），
 * 再在一个事务内删除旧清单并写入新清单；旧上报不可回写，已固化的任务与判定证据不受影响。
 * 仅回执 SUCCESS 更新设备固件版本，上报模块清单绝不自动虚构固件或模块升级。
 */
@Service
public class DeviceModuleService {

    private final DeviceModuleRepository deviceModuleRepository;
    private final DeviceService deviceService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public DeviceModuleService(DeviceModuleRepository deviceModuleRepository, DeviceService deviceService,
                               IdempotencyService idempotency, Clock clock) {
        this.deviceModuleRepository = deviceModuleRepository;
        this.deviceService = deviceService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public DeviceModulesView report(String deviceId, ReportDeviceModulesRequest request) {
        // 先验证完整后态：版本格式与模块名唯一性，失败不产生任何删除或写入
        Set<String> names = new HashSet<>();
        for (ModuleEntry entry : request.modules()) {
            ModuleVersion.parse(entry.version());
            if (!names.add(entry.moduleName())) {
                throw ApiException.unprocessable("DUPLICATE_MODULE",
                        "设备模块清单存在重复模块: " + entry.moduleName());
            }
        }
        String fingerprint = String.join("|", "device.modules.report", deviceId,
                fingerprintOf(request.modules()));
        return idempotency.execute(request.requestId(), "device.modules.report", fingerprint, () -> {
            Device device = deviceService.findDevice(deviceId);
            String now = UtcTimes.now(clock);
            deviceModuleRepository.deleteByDevice(device.deviceId());
            for (ModuleEntry entry : request.modules()) {
                deviceModuleRepository.insert(device.deviceId(), entry.moduleName(),
                        entry.version(), entry.digest(), now);
            }
            return view(device.deviceId(), now);
        }, DeviceModulesView.class);
    }

    /**
     * 查询设备当前模块清单（只读，不修改状态）；设备不存在返回 404。
     * 从未上报时 modules 为空列表、reportedAtUtc 为 null。
     */
    public DeviceModulesView get(String deviceId) {
        Device device = deviceService.findDevice(deviceId);
        List<ModuleSnapshot> snapshots = deviceModuleRepository.findSnapshotsByDevice(device.deviceId());
        return new DeviceModulesView(device.deviceId(),
                snapshots.isEmpty() ? null : deviceModuleRepository.findReportedAt(device.deviceId()),
                snapshots.stream().map(DeviceModulesView.Item::of).toList());
    }

    private DeviceModulesView view(String deviceId, String reportedAtUtc) {
        List<ModuleSnapshot> snapshots = deviceModuleRepository.findSnapshotsByDevice(deviceId);
        return new DeviceModulesView(deviceId, reportedAtUtc,
                snapshots.stream().map(DeviceModulesView.Item::of).toList());
    }

    private String fingerprintOf(List<ModuleEntry> modules) {
        return modules.stream()
                .sorted((a, b) -> a.moduleName().compareTo(b.moduleName()))
                .map(entry -> String.join(":", entry.moduleName(), entry.version(), entry.digest()))
                .reduce((a, b) -> a + ";" + b)
                .orElse("");
    }
}
