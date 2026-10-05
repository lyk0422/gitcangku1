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
import com.example.starter.maintenance.api.dto.InstallationResponse;
import com.example.starter.maintenance.api.dto.RegisterComponentRequest;
import com.example.starter.maintenance.api.dto.RemoveComponentRequest;
import com.example.starter.maintenance.domain.Component;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.Installation;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.store.ComponentRepository;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 部件装拆账事务业务服务。写操作流程：部件行锁（→ 设备行锁）→ 幂等判定 → 版本校验 →
 * 业务规则 → 变更并版本加一。去重记录与业务变更同事务提交；任一规则失败抛异常整体回滚。
 * 锁顺序固定为部件 → 设备，与读数修订（仅设备锁）共同保证装拆与更正的联合判定。
 */
@Service
public class ComponentTxService {

    private final ComponentRepository componentRepository;
    private final EquipmentRepository equipmentRepository;
    private final IdempotencyService idempotency;
    private final RequestFingerprint fingerprint;
    private final Clock clock;

    public ComponentTxService(ComponentRepository componentRepository,
                              EquipmentRepository equipmentRepository,
                              IdempotencyService idempotency,
                              RequestFingerprint fingerprint,
                              Clock clock) {
        this.componentRepository = componentRepository;
        this.equipmentRepository = equipmentRepository;
        this.idempotency = idempotency;
        this.fingerprint = fingerprint;
        this.clock = clock;
    }

    // ---------- 登记部件 ----------

    @Transactional
    public ComponentResponse register(RegisterComponentRequest req) {
        String fp = fingerprint.of("REGISTER_COMPONENT", req.operator(), req.componentId(),
                Map.of("lifetimeMinutes", req.lifetimeMinutes()), null);
        return idempotency.execute(req.requestId(), "REGISTER_COMPONENT", fp,
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
    public InstallationResponse install(String componentId, InstallComponentRequest req) {
        Component component = lockComponent(componentId);
        equipmentRepository.findEquipmentForUpdate(req.equipmentId())
                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_NOT_FOUND",
                        "设备不存在：" + req.equipmentId()));
        String fp = fingerprint.of("INSTALL_COMPONENT", req.operator(), componentId,
                Map.of("equipmentId", req.equipmentId(), "readingId", req.readingId()),
                req.expectedVersion());
        return idempotency.execute(req.requestId(), "INSTALL_COMPONENT", fp,
                InstallationResponse.class, () -> {
                    checkVersion(component, req.expectedVersion());
                    if (component.usedMinutes() >= component.lifetimeMinutes()) {
                        throw ApiException.conflict("COMPONENT_LIFE_EXHAUSTED",
                                "部件寿命已达上限（" + component.usedMinutes() + "/"
                                        + component.lifetimeMinutes() + " 分钟），禁止再安装");
                    }
                    if (componentRepository.findActiveInstallation(componentId).isPresent()) {
                        throw ApiException.conflict("COMPONENT_ALREADY_INSTALLED",
                                "部件已安装在设备上，须先拆卸才能再安装：" + componentId);
                    }
                    Reading reading = equipmentRepository.findReading(req.equipmentId(), req.readingId())
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                    "读数不存在：" + req.readingId()));
                    componentRepository.findLastRemoval(componentId).ifPresent(last -> {
                        if (reading.sampledAt().isBefore(last.removeSampledAt())) {
                            throw ApiException.unprocessable("COMPONENT_INTERVAL_OVERLAP",
                                    "安装时刻早于上次拆卸时刻，装拆区间不得重叠（左闭右开，相邻接续合法）");
                        }
                    });
                    Instant now = clock.instant();
                    long installationId = componentRepository.insertInstallation(componentId,
                            req.equipmentId(), req.readingId(), reading.sampledAt(),
                            reading.cumulativeMinutes(), req.requestId(), now);
                    componentRepository.incrementVersion(componentId);
                    return new InstallationResponse(installationId, componentId, req.equipmentId(),
                            req.readingId(), reading.sampledAt(), reading.cumulativeMinutes(),
                            null, null, null, null, component.usedMinutes(), true,
                            component.version() + 1);
                });
    }

    // ---------- 拆卸 ----------

    @Transactional
    public InstallationResponse remove(String componentId, RemoveComponentRequest req) {
        Component component = lockComponent(componentId);
        String fp = fingerprint.of("REMOVE_COMPONENT", req.operator(), componentId,
                Map.of("readingId", req.readingId()), req.expectedVersion());
        return idempotency.execute(req.requestId(), "REMOVE_COMPONENT", fp,
                InstallationResponse.class, () -> {
                    checkVersion(component, req.expectedVersion());
                    Installation active = componentRepository.findActiveInstallation(componentId)
                            .orElseThrow(() -> ApiException.conflict("COMPONENT_NOT_INSTALLED",
                                    "部件当前未安装在任何设备上：" + componentId));
                    equipmentRepository.findEquipmentForUpdate(active.equipmentId())
                            .orElseThrow(() -> ApiException.notFound("EQUIPMENT_NOT_FOUND",
                                    "设备不存在：" + active.equipmentId()));
                    Reading reading = equipmentRepository.findReading(active.equipmentId(), req.readingId())
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                    "读数不存在：" + req.readingId()));
                    if (reading.sampledAt().isBefore(active.installSampledAt())
                            || reading.cumulativeMinutes() < active.installCumulativeMinutes()) {
                        throw ApiException.unprocessable("COMPONENT_INTERVAL_INVALID",
                                "拆卸累计（" + reading.cumulativeMinutes() + "）不得小于安装累计（"
                                        + active.installCumulativeMinutes() + "）");
                    }
                    long delta = reading.cumulativeMinutes() - active.installCumulativeMinutes();
                    Instant now = clock.instant();
                    componentRepository.completeInstallation(active.installationId(), req.readingId(),
                            reading.sampledAt(), reading.cumulativeMinutes(), req.requestId(), now);
                    componentRepository.addUsedMinutes(componentId, delta);
                    componentRepository.incrementVersion(componentId);
                    return new InstallationResponse(active.installationId(), componentId,
                            active.equipmentId(), active.installReadingId(), active.installSampledAt(),
                            active.installCumulativeMinutes(), req.readingId(), reading.sampledAt(),
                            reading.cumulativeMinutes(), delta, component.usedMinutes() + delta,
                            false, component.version() + 1);
                });
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public ComponentStatusResponse getComponent(String componentId) {
        Component component = componentRepository.findComponent(componentId)
                .orElseThrow(() -> ApiException.notFound("COMPONENT_NOT_FOUND",
                        "部件不存在：" + componentId));
        List<Installation> installations = componentRepository.listInstallations(componentId);
        String currentEquipment = installations.stream()
                .filter(Installation::active)
                .map(Installation::equipmentId)
                .findFirst()
                .orElse(null);
        List<ComponentStatusResponse.InstallationView> views = installations.stream()
                .map(installation -> new ComponentStatusResponse.InstallationView(
                        installation.installationId(), installation.equipmentId(),
                        installation.installReadingId(), installation.installSampledAt(),
                        installation.installCumulativeMinutes(), installation.removeReadingId(),
                        installation.removeSampledAt(), installation.removeCumulativeMinutes(),
                        installation.active()))
                .toList();
        return new ComponentStatusResponse(componentId, component.lifetimeMinutes(),
                component.usedMinutes(), component.lifetimeMinutes() - component.usedMinutes(),
                component.usedMinutes() >= component.lifetimeMinutes(), currentEquipment,
                component.version(), views);
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
