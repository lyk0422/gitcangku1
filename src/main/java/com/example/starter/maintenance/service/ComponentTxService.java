package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.ComponentResponse;
import com.example.starter.maintenance.api.dto.ComponentStatusResponse;
import com.example.starter.maintenance.api.dto.InstallComponentRequest;
import com.example.starter.maintenance.api.dto.InstallResponse;
import com.example.starter.maintenance.api.dto.InstallView;
import com.example.starter.maintenance.api.dto.RegisterComponentRequest;
import com.example.starter.maintenance.api.dto.UninstallComponentRequest;
import com.example.starter.maintenance.api.dto.UninstallResponse;
import com.example.starter.maintenance.domain.Component;
import com.example.starter.maintenance.domain.ComponentInstall;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.store.ComponentRepository;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 部件装拆账事务业务服务。写操作流程：部件行锁 → 幂等判定 → 版本校验 → 业务规则 →
 * 变更并版本加一；装拆时再加设备行锁（固定顺序：部件 → 设备），与读数更正联合判定。
 * 去重记录与业务变更同事务提交；任一规则失败抛异常整体回滚。
 */
@Service
public class ComponentTxService {

    private final ComponentRepository componentRepository;
    private final EquipmentRepository equipmentRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public ComponentTxService(ComponentRepository componentRepository,
                              EquipmentRepository equipmentRepository,
                              IdempotencyService idempotency, Clock clock) {
        this.componentRepository = componentRepository;
        this.equipmentRepository = equipmentRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ---------- 登记部件 ----------

    @Transactional
    public ComponentResponse register(RegisterComponentRequest req) {
        String fingerprint = Fingerprints.of("REGISTER_COMPONENT", req.operator(), req.componentId(),
                Map.of("lifetimeMinutes", req.lifetimeMinutes()));
        return idempotency.execute(req.requestId(), "REGISTER_COMPONENT", fingerprint,
                ComponentResponse.class, () -> {
                    if (componentRepository.findComponent(req.componentId()).isPresent()) {
                        throw ApiException.conflict("COMPONENT_EXISTS", "部件已存在：" + req.componentId());
                    }
                    componentRepository.insertComponent(req.componentId(), req.lifetimeMinutes(),
                            clock.instant());
                    return new ComponentResponse(req.componentId(), req.lifetimeMinutes(), 0L, 1L);
                });
    }

    // ---------- 安装 ----------

    @Transactional
    public InstallResponse install(String componentId, InstallComponentRequest req) {
        Component component = lockComponent(componentId);
        String fingerprint = Fingerprints.of("INSTALL_COMPONENT", req.operator(), componentId,
                Map.of("expectedVersion", req.expectedVersion(),
                        "equipmentId", req.equipmentId(),
                        "readingId", req.readingId()));
        return idempotency.execute(req.requestId(), "INSTALL_COMPONENT", fingerprint,
                InstallResponse.class, () -> {
                    checkVersion(component, req.expectedVersion());
                    if (component.accumulatedMinutes() >= component.lifetimeMinutes()) {
                        throw ApiException.conflict("COMPONENT_LIFE_EXHAUSTED",
                                "部件寿命已达上限（" + component.lifetimeMinutes() + " 分钟），禁止再安装");
                    }
                    if (componentRepository.findOpenInstall(componentId).isPresent()) {
                        throw ApiException.conflict("COMPONENT_ALREADY_INSTALLED",
                                "部件已在装，须先拆卸才能换装：" + componentId);
                    }
                    equipmentRepository.findEquipmentForUpdate(req.equipmentId())
                            .orElseThrow(() -> ApiException.notFound("EQUIPMENT_NOT_FOUND",
                                    "设备不存在：" + req.equipmentId()));
                    Reading reading = equipmentRepository.findReading(req.equipmentId(), req.readingId())
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                    "读数不存在：" + req.readingId()));
                    Instant now = clock.instant();
                    long installId = componentRepository.insertInstall(componentId, req.equipmentId(),
                            req.readingId(), reading.sampledAt(), reading.cumulativeMinutes(),
                            req.requestId(), now);
                    componentRepository.insertCurrent(componentId, installId, req.equipmentId(), now);
                    componentRepository.incrementVersion(componentId);
                    return new InstallResponse(installId, componentId, req.equipmentId(),
                            req.readingId(), reading.sampledAt(), reading.cumulativeMinutes(),
                            component.version() + 1);
                });
    }

    // ---------- 拆卸 ----------

    @Transactional
    public UninstallResponse uninstall(String componentId, UninstallComponentRequest req) {
        Component component = lockComponent(componentId);
        String fingerprint = Fingerprints.of("UNINSTALL_COMPONENT", req.operator(), componentId,
                Map.of("expectedVersion", req.expectedVersion(), "readingId", req.readingId()));
        return idempotency.execute(req.requestId(), "UNINSTALL_COMPONENT", fingerprint,
                UninstallResponse.class, () -> {
                    checkVersion(component, req.expectedVersion());
                    ComponentInstall open = componentRepository.findOpenInstall(componentId)
                            .orElseThrow(() -> ApiException.conflict("COMPONENT_NOT_INSTALLED",
                                    "部件当前未在装：" + componentId));
                    equipmentRepository.findEquipmentForUpdate(open.equipmentId())
                            .orElseThrow(() -> ApiException.notFound("EQUIPMENT_NOT_FOUND",
                                    "设备不存在：" + open.equipmentId()));
                    Reading reading = equipmentRepository
                            .findReading(open.equipmentId(), req.readingId())
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                    "读数不存在：" + req.readingId()));
                    if (reading.sampledAt().isBefore(open.installSampledAt())
                            || reading.cumulativeMinutes() < open.installCumulativeMinutes()) {
                        throw ApiException.unprocessable("UNINSTALL_ORDER_VIOLATION",
                                "拆卸累计（" + reading.cumulativeMinutes() + "）不得小于安装累计（"
                                        + open.installCumulativeMinutes() + "）");
                    }
                    long duration = reading.cumulativeMinutes() - open.installCumulativeMinutes();
                    Instant now = clock.instant();
                    componentRepository.closeInstall(open.installId(), req.readingId(),
                            reading.sampledAt(), reading.cumulativeMinutes(), duration,
                            req.requestId(), now);
                    componentRepository.deleteCurrent(componentId);
                    componentRepository.addAccumulatedAndIncrementVersion(componentId, duration);
                    return new UninstallResponse(open.installId(), componentId, open.equipmentId(),
                            req.readingId(), reading.sampledAt(), reading.cumulativeMinutes(),
                            duration, component.accumulatedMinutes() + duration,
                            component.version() + 1);
                });
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public ComponentStatusResponse getStatus(String componentId) {
        Component component = componentRepository.findComponent(componentId)
                .orElseThrow(() -> ApiException.notFound("COMPONENT_NOT_FOUND",
                        "部件不存在：" + componentId));
        List<InstallView> installs = componentRepository.listInstalls(componentId).stream()
                .map(row -> new InstallView(row.installId(), row.equipmentId(),
                        row.installReadingId(), row.installSampledAt(),
                        row.installCumulativeMinutes(), row.uninstallReadingId(),
                        row.uninstallSampledAt(), row.uninstallCumulativeMinutes(),
                        row.durationMinutes(), row.open()))
                .toList();
        Long currentInstallId = installs.stream().filter(InstallView::open)
                .map(InstallView::installId).findFirst().orElse(null);
        String currentEquipmentId = installs.stream().filter(InstallView::open)
                .map(InstallView::equipmentId).findFirst().orElse(null);
        long remaining = Math.max(0L, component.lifetimeMinutes() - component.accumulatedMinutes());
        return new ComponentStatusResponse(componentId, component.lifetimeMinutes(),
                component.accumulatedMinutes(), remaining, currentEquipmentId, currentInstallId,
                component.version(), installs);
    }

    // ---------- 内部规则 ----------

    private Component lockComponent(String componentId) {
        return componentRepository.findComponentForUpdate(componentId)
                .orElseThrow(() -> ApiException.notFound("COMPONENT_NOT_FOUND",
                        "部件不存在：" + componentId));
    }

    private void checkVersion(Component component, long expectedVersion) {
        if (component.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "部件版本冲突：期望 " + expectedVersion + "，当前 " + component.version());
        }
    }
}
