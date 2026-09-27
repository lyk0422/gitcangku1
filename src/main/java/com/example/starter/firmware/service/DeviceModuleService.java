package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceModuleReportHistoryResponse;
import com.example.starter.firmware.api.DeviceModuleReportRequest;
import com.example.starter.firmware.api.DeviceModulesView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.DeviceModuleEntry;
import com.example.starter.firmware.domain.DeviceModuleReport;
import com.example.starter.firmware.domain.ModuleDigest;
import com.example.starter.firmware.domain.ModuleVersions;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceModuleRepository;
import com.example.starter.firmware.repo.DeviceRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 设备模块上报与查询：每次上报整体替换当前清单、上报版本加一并固化摘要与历史。
 * 模块升级只以设备实际上报为准，回执成功不会虚构模块变化。
 */
@Service
public class DeviceModuleService {

    private final DeviceModuleRepository deviceModuleRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceService deviceService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public DeviceModuleService(DeviceModuleRepository deviceModuleRepository,
                               DeviceRepository deviceRepository, DeviceService deviceService,
                               IdempotencyService idempotency, Clock clock) {
        this.deviceModuleRepository = deviceModuleRepository;
        this.deviceRepository = deviceRepository;
        this.deviceService = deviceService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 上报模块清单：先锁设备行使并发上报按提交顺序串行，再校验完整最终状态
     * （版本格式、请求内模块不重复），最后在一个事务内整体替换清单、推进上报版本并落历史；
     * 失败整体回滚、不占幂等键。
     */
    public DeviceModulesView report(String deviceId, DeviceModuleReportRequest request) {
        StringBuilder fingerprint = new StringBuilder("device.modules.report|").append(deviceId);
        for (DeviceModuleReportRequest.ModuleEntry entry : request.modules()) {
            fingerprint.append('|').append(entry.module()).append(',').append(entry.version());
        }
        return idempotency.execute(request.requestId(), "device.modules.report", fingerprint.toString(),
                () -> {
                    Device device = deviceRepository.findByIdForUpdate(deviceId)
                            .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND",
                                    "设备不存在: " + deviceId));
                    List<DeviceModuleEntry> modules = validate(request);
                    String digest = ModuleDigest.of(modules);
                    int reportVersion = device.moduleReportVersion() + 1;
                    deviceModuleRepository.replaceAll(deviceId, modules);
                    deviceRepository.updateModuleReport(deviceId, digest, reportVersion);
                    deviceModuleRepository.insertReport(deviceId,
                            new DeviceModuleReport(reportVersion, digest, Instant.now(clock).toString()));
                    return new DeviceModulesView(deviceId, reportVersion, digest, modules);
                }, DeviceModulesView.class);
    }

    /**
     * 当前模块清单（只读，不改变状态）。
     */
    public DeviceModulesView current(String deviceId) {
        Device device = deviceService.findDevice(deviceId);
        return new DeviceModulesView(deviceId, device.moduleReportVersion(), device.moduleDigest(),
                deviceModuleRepository.findByDevice(deviceId));
    }

    /**
     * 上报历史（只读，不改变状态）。
     */
    public DeviceModuleReportHistoryResponse history(String deviceId) {
        deviceService.findDevice(deviceId);
        return new DeviceModuleReportHistoryResponse(deviceId,
                deviceModuleRepository.findReportsByDevice(deviceId));
    }

    /**
     * 设备当前模块清单的 Map 形式（模块名到版本），供拉取校验使用。
     */
    public Map<String, String> currentModuleMap(String deviceId) {
        Map<String, String> map = new TreeMap<>();
        for (DeviceModuleEntry entry : deviceModuleRepository.findByDevice(deviceId)) {
            map.put(entry.module(), entry.version());
        }
        return map;
    }

    private List<DeviceModuleEntry> validate(DeviceModuleReportRequest request) {
        Set<String> seen = new HashSet<>();
        List<DeviceModuleEntry> modules = new ArrayList<>();
        for (DeviceModuleReportRequest.ModuleEntry entry : request.modules()) {
            ModuleVersions.validate(entry.version());
            if (!seen.add(entry.module())) {
                throw ApiException.badRequest("DUPLICATE_MODULE",
                        "同一请求内模块重复: " + entry.module());
            }
            modules.add(new DeviceModuleEntry(entry.module(), entry.version()));
        }
        modules.sort((a, b) -> a.module().compareTo(b.module()));
        return modules;
    }
}
