package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.ComponentInstallView;
import com.example.starter.maintenance.api.dto.ComponentResponse;
import com.example.starter.maintenance.api.dto.InstallComponentRequest;
import com.example.starter.maintenance.api.dto.RegisterComponentRequest;
import com.example.starter.maintenance.api.dto.RemoveComponentRequest;
import com.example.starter.maintenance.domain.Component;
import com.example.starter.maintenance.domain.ComponentInstall;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.store.ComponentRepository;
import com.example.starter.maintenance.store.EquipmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 部件装拆账事务业务服务。写操作流程：设备行锁 → 部件行锁 → 幂等判定 → 版本校验 → 业务规则 → 变更并版本加一。
 * 与读数修订共用设备行锁，装拆与读数更正联合判定、原子提交；去重记录与业务变更同事务，失败整体回滚不占键。
 */
@Service
public class ComponentTxService {

    private final ComponentRepository componentRepository;
    private final EquipmentRepository equipmentRepository;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ComponentTxService(ComponentRepository componentRepository,
                              EquipmentRepository equipmentRepository,
                              IdempotencyService idempotency,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.componentRepository = componentRepository;
        this.equipmentRepository = equipmentRepository;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- 登记部件 ----------

    @Transactional
    public ComponentResponse register(RegisterComponentRequest req) {
        String fingerprint = fingerprint("REGISTER_COMPONENT", req.operator(), req.componentId(),
                Map.of("lifespanMinutes", req.lifespanMinutes()));
        return idempotency.execute(req.requestId(), "REGISTER_COMPONENT", fingerprint,
                ComponentResponse.class, () -> {
                    if (componentRepository.findComponent(req.componentId()).isPresent()) {
                        throw ApiException.conflict("COMPONENT_EXISTS", "部件已存在：" + req.componentId());
                    }
                    componentRepository.insertComponent(req.componentId(), req.lifespanMinutes(),
                            clock.instant());
                    return new ComponentResponse(req.componentId(), req.lifespanMinutes(), 0L,
                            null, false, 1L);
                });
    }

    // ---------- 安装 ----------

    @Transactional
    public ComponentInstallView install(String componentId, InstallComponentRequest req) {
        // 先锁设备（与读数修订互斥），再锁部件，锁顺序固定避免死锁
        lockEquipment(req.equipmentId());
        Component component = lockComponent(componentId);
        String fingerprint = fingerprint("INSTALL_COMPONENT", req.operator(), componentId,
                Fingerprints.params("expectedVersion", req.expectedVersion(),
                        "equipmentId", req.equipmentId(),
                        "readingId", req.readingId()));
        return idempotency.execute(req.requestId(), "INSTALL_COMPONENT", fingerprint,
                ComponentInstallView.class, () -> {
                    checkVersion(component, req.expectedVersion());
                    if (componentRepository.findOpenInstall(componentId).isPresent()) {
                        throw ApiException.conflict("COMPONENT_INSTALLED",
                                "部件已在装，须先拆卸才能换装：" + componentId);
                    }
                    if (component.accumulatedMinutes() >= component.lifespanMinutes()) {
                        throw ApiException.conflict("LIFESPAN_EXHAUSTED",
                                "部件累计已达寿命上限，禁止再安装：" + componentId);
                    }
                    Reading reading = findReading(req.equipmentId(), req.readingId());
                    Instant now = clock.instant();
                    ComponentInstall install = new ComponentInstall(0L, componentId, req.equipmentId(),
                            req.readingId(), reading.sampledAt(), reading.cumulativeMinutes(),
                            null, null, null, req.operator(), null, now, null);
                    long installId = componentRepository.insertInstall(install, req.requestId(), now);
                    componentRepository.incrementVersion(componentId);
                    return toView(new ComponentInstall(installId, componentId, req.equipmentId(),
                            req.readingId(), reading.sampledAt(), reading.cumulativeMinutes(),
                            null, null, null, req.operator(), null, now, null),
                            component.version() + 1);
                });
    }

    // ---------- 拆卸 ----------

    @Transactional
    public ComponentInstallView remove(String componentId, RemoveComponentRequest req) {
        lockEquipment(req.equipmentId());
        Component component = lockComponent(componentId);
        String fingerprint = fingerprint("REMOVE_COMPONENT", req.operator(), componentId,
                Fingerprints.params("expectedVersion", req.expectedVersion(),
                        "equipmentId", req.equipmentId(),
                        "readingId", req.readingId()));
        return idempotency.execute(req.requestId(), "REMOVE_COMPONENT", fingerprint,
                ComponentInstallView.class, () -> {
                    checkVersion(component, req.expectedVersion());
                    ComponentInstall open = componentRepository.findOpenInstall(componentId)
                            .orElseThrow(() -> ApiException.conflict("COMPONENT_NOT_INSTALLED",
                                    "部件当前未在装，无法拆卸：" + componentId));
                    if (!open.equipmentId().equals(req.equipmentId())) {
                        throw ApiException.conflict("COMPONENT_INSTALLED_ELSEWHERE",
                                "部件在装于设备 " + open.equipmentId() + "，拆卸设备不匹配：" + req.equipmentId());
                    }
                    Reading reading = findReading(req.equipmentId(), req.readingId());
                    if (reading.sampledAt().isBefore(open.installSampledAt())) {
                        throw ApiException.unprocessable("REMOVAL_TIME_BEFORE_INSTALL",
                                "拆卸读数时刻早于安装读数时刻，区间无效");
                    }
                    if (reading.cumulativeMinutes() < open.installCumulativeMinutes()) {
                        throw ApiException.unprocessable("REMOVAL_CUMULATIVE_BEFORE_INSTALL",
                                "拆卸累计（" + reading.cumulativeMinutes() + "）小于安装累计（"
                                        + open.installCumulativeMinutes() + "）");
                    }
                    long segment = reading.cumulativeMinutes() - open.installCumulativeMinutes();
                    Instant now = clock.instant();
                    componentRepository.closeInstall(open.installId(), req.readingId(),
                            reading.sampledAt(), reading.cumulativeMinutes(), req.operator(),
                            req.requestId(), now);
                    componentRepository.settleOnRemoval(componentId, segment);
                    ComponentInstall closed = new ComponentInstall(open.installId(), componentId,
                            open.equipmentId(), open.installReadingId(), open.installSampledAt(),
                            open.installCumulativeMinutes(), req.readingId(), reading.sampledAt(),
                            reading.cumulativeMinutes(), open.installOperator(), req.operator(),
                            open.installedAt(), now);
                    return toView(closed, component.version() + 1);
                });
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public ComponentResponse getComponent(String componentId) {
        Component component = componentRepository.findComponent(componentId)
                .orElseThrow(() -> componentNotFound(componentId));
        String currentEquipmentId = componentRepository.findOpenInstall(componentId)
                .map(ComponentInstall::equipmentId).orElse(null);
        boolean exhausted = component.accumulatedMinutes() >= component.lifespanMinutes();
        return new ComponentResponse(component.componentId(), component.lifespanMinutes(),
                component.accumulatedMinutes(), currentEquipmentId, exhausted, component.version());
    }

    @Transactional(readOnly = true)
    public List<ComponentInstallView> listInstalls(String componentId) {
        componentRepository.findComponent(componentId)
                .orElseThrow(() -> componentNotFound(componentId));
        return componentRepository.listInstalls(componentId).stream()
                .map(install -> toView(install, 0L))
                .toList();
    }

    // ---------- 内部规则 ----------

    private ComponentInstallView toView(ComponentInstall install, long componentVersion) {
        return new ComponentInstallView(install.installId(), install.componentId(),
                install.equipmentId(), install.installReadingId(), install.installSampledAt(),
                install.installCumulativeMinutes(), install.removeReadingId(),
                install.removeSampledAt(), install.removeCumulativeMinutes(),
                install.segmentMinutes(), install.open(), componentVersion);
    }

    private Equipment lockEquipment(String equipmentId) {
        return equipmentRepository.findEquipmentForUpdate(equipmentId)
                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_NOT_FOUND",
                        "设备不存在：" + equipmentId));
    }

    private Component lockComponent(String componentId) {
        return componentRepository.findComponentForUpdate(componentId)
                .orElseThrow(() -> componentNotFound(componentId));
    }

    private Reading findReading(String equipmentId, String readingId) {
        return equipmentRepository.findReading(equipmentId, readingId)
                .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                        "读数不存在：" + readingId));
    }

    private ApiException componentNotFound(String componentId) {
        return ApiException.notFound("COMPONENT_NOT_FOUND", "部件不存在：" + componentId);
    }

    private void checkVersion(Component component, long expectedVersion) {
        if (component.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "部件版本冲突：期望 " + expectedVersion + "，当前 " + component.version());
        }
    }

    private String fingerprint(String operation, String operator, String resource,
                               Map<String, ?> params) {
        return Fingerprints.of(objectMapper, operation, operator, resource, params);
    }
}