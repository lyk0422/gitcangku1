package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CertChainListResponse;
import com.example.starter.firmware.api.CertChainView;
import com.example.starter.firmware.api.CompatView;
import com.example.starter.firmware.api.RegisterCertChainRequest;
import com.example.starter.firmware.api.RegisterFirmwareRequest;
import com.example.starter.firmware.api.RevokeCertChainRequest;
import com.example.starter.firmware.api.RolloverCertChainRequest;
import com.example.starter.firmware.api.UpsertCompatRequest;
import com.example.starter.firmware.domain.CertChain;
import com.example.starter.firmware.domain.CertChainStatus;
import com.example.starter.firmware.domain.CompatRule;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.CertChainRepository;
import com.example.starter.firmware.repo.CompatRepository;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.DeviceTrustRepository;
import com.example.starter.firmware.repo.FirmwareVersionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 固件签名证书链管理与投放守卫。
 *
 * 时态规则：区间为闭开区间 [validFrom, validTo)；任意时刻同一固件版本至多一条有效链，
 * 新链在生效时刻前不可用，旧链在新链生效时刻闭合。登记/轮换/撤销均在 firmware_version
 * 行锁内完成，按提交顺序串行裁决。
 *
 * 守卫规则：目标固件版本未纳管证书时保持旧投放行为（兼容存量）；一旦纳管，启动发布与
 * 设备拉取必须同时满足兼容矩阵允许、请求时刻存在唯一当前有效链、设备信任集合包含该链。
 */
@Service
public class CertificateService {

    private final FirmwareVersionRepository firmwareVersionRepository;
    private final CertChainRepository certChainRepository;
    private final CompatRepository compatRepository;
    private final DeviceTrustRepository deviceTrustRepository;
    private final DeviceRepository deviceRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public CertificateService(FirmwareVersionRepository firmwareVersionRepository,
                              CertChainRepository certChainRepository,
                              CompatRepository compatRepository,
                              DeviceTrustRepository deviceTrustRepository,
                              DeviceRepository deviceRepository,
                              IdempotencyService idempotency, Clock clock) {
        this.firmwareVersionRepository = firmwareVersionRepository;
        this.certChainRepository = certChainRepository;
        this.compatRepository = compatRepository;
        this.deviceTrustRepository = deviceTrustRepository;
        this.deviceRepository = deviceRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public String registerFirmware(RegisterFirmwareRequest request) {
        String fingerprint = String.join("|", "firmware.register", request.model(),
                request.version(), request.operator());
        return idempotency.execute(request.requestId(), "firmware.register", fingerprint, () -> {
            try {
                firmwareVersionRepository.insert(request.model(), request.version(), request.operator());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("FIRMWARE_EXISTS",
                        "固件版本已登记: " + request.model() + " " + request.version());
            }
            return request.model() + ":" + request.version();
        }, String.class);
    }

    /**
     * 登记首个证书链（chainVersion=1）与初始信任设备集合。
     */
    public CertChainView registerChain(RegisterCertChainRequest request) {
        Instant validFrom = parseInstant(request.validFromUtc(), "validFromUtc");
        Instant validTo = request.validToUtc() == null || request.validToUtc().isBlank()
                ? null : parseInstant(request.validToUtc(), "validToUtc");
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw ApiException.badRequest("INVALID_VALID_WINDOW", "validToUtc 必须晚于 validFromUtc");
        }
        List<String> deviceIds = normalizeDevices(request.deviceIds());
        String fingerprint = String.join("|", "cert.register", request.model(), request.firmwareVersion(),
                String.valueOf(request.chainVersion()), String.valueOf(request.certFingerprint()),
                request.validFromUtc(), String.valueOf(request.validToUtc()),
                String.join(",", deviceIds), request.operator());
        return idempotency.execute(request.requestId(), "cert.register", fingerprint, () -> {
            var firmware = firmwareVersionRepository.findForUpdate(request.model(), request.firmwareVersion())
                    .orElseThrow(() -> ApiException.notFound("FIRMWARE_NOT_FOUND",
                            "固件版本未登记: " + request.model() + " " + request.firmwareVersion()));
            List<CertChain> chains = certChainRepository.findByFirmware(firmware.model(), firmware.version());
            if (!chains.isEmpty()) {
                throw ApiException.conflict("CHAIN_ALREADY_EXISTS",
                        "该固件版本已存在证书链，追加链必须走轮换接口");
            }
            if (request.chainVersion() != 1) {
                throw ApiException.conflict("CHAIN_VERSION_SEQUENCE", "首个证书链版本必须为1");
            }
            assertDevicesExist(deviceIds);
            long id = certChainRepository.insert(firmware.model(), firmware.version(), 1,
                    request.certFingerprint(), request.validFromUtc(),
                    validTo == null ? null : request.validToUtc(), request.operator());
            grantTrust(deviceIds, firmware.model(), firmware.version(), 1, request.operator());
            return CertChainView.of(certChainRepository.findById(id).orElseThrow(),
                    isCurrent(certChainRepository.findById(id).orElseThrow(), Instant.now(clock)));
        }, CertChainView.class);
    }

    /**
     * 轮换：显式指定旧链版本与新链版本；旧链必须是唯一开放链，新链版本接续，
     * 旧链在新链生效时刻闭合，新链区间开放。按 firmware 行锁提交顺序裁决。
     */
    public CertChainView rollover(RolloverCertChainRequest request) {
        Instant effectiveAt = parseInstant(request.validFromUtc(), "validFromUtc");
        List<String> deviceIds = normalizeDevices(request.deviceIds());
        String fingerprint = String.join("|", "cert.rollover", request.model(), request.firmwareVersion(),
                String.valueOf(request.oldChainVersion()), String.valueOf(request.newChainVersion()),
                request.certFingerprint(), request.validFromUtc(),
                String.join(",", deviceIds), request.operator());
        return idempotency.execute(request.requestId(), "cert.rollover", fingerprint, () -> {
            var firmware = firmwareVersionRepository.findForUpdate(request.model(), request.firmwareVersion())
                    .orElseThrow(() -> ApiException.notFound("FIRMWARE_NOT_FOUND",
                            "固件版本未登记: " + request.model() + " " + request.firmwareVersion()));
            List<CertChain> chains = certChainRepository.findByFirmware(firmware.model(), firmware.version());
            if (chains.isEmpty()) {
                throw ApiException.conflict("CHAIN_NOT_FOUND",
                        "该固件版本尚无证书链，请先登记首个链");
            }
            CertChain latest = chains.get(chains.size() - 1);
            CertChain oldChain = certChainRepository
                    .findByChainVersion(firmware.model(), firmware.version(), request.oldChainVersion())
                    .orElseThrow(() -> ApiException.notFound("OLD_CHAIN_NOT_FOUND",
                            "旧证书链不存在: " + request.oldChainVersion()));
            if (oldChain.status() != CertChainStatus.ACTIVE) {
                throw ApiException.conflict("OLD_CHAIN_NOT_ACTIVE",
                        "旧证书链状态为 " + oldChain.status() + "，不能轮换");
            }
            if (latest.chainVersion() != oldChain.chainVersion()) {
                throw ApiException.conflict("OLD_CHAIN_NOT_OPEN",
                        "仅最新链版本可被轮换，当前最新链版本: " + latest.chainVersion());
            }
            if (request.newChainVersion() != latest.chainVersion() + 1) {
                throw ApiException.conflict("NEW_CHAIN_VERSION_SEQUENCE",
                        "新链版本必须接续为 " + (latest.chainVersion() + 1));
            }
            Instant oldFrom = parseInstant(oldChain.validFromUtc(), "validFromUtc");
            if (!effectiveAt.isAfter(oldFrom)) {
                throw ApiException.badRequest("INVALID_VALID_WINDOW",
                        "新链生效时刻必须晚于旧链生效时刻 " + oldChain.validFromUtc());
            }
            // 旧链为开放区间：在新链生效时刻闭合，两链首尾相接；旧链已是有限区间：
            // 新链生效时刻不得早于旧链失效时刻，保证区间不重叠（之间允许有空窗，空窗期无当前链）。
            boolean closeOld = oldChain.validToUtc() == null;
            if (!closeOld && effectiveAt.isBefore(parseInstant(oldChain.validToUtc(), "validToUtc"))) {
                throw ApiException.badRequest("CERT_WINDOW_OVERLAP",
                        "新链生效时刻早于旧链失效时刻 " + oldChain.validToUtc() + "，轮换区间重叠");
            }
            assertDevicesExist(deviceIds);
            long id = certChainRepository.insert(firmware.model(), firmware.version(),
                    request.newChainVersion(), request.certFingerprint(),
                    request.validFromUtc(), null, request.operator());
            if (closeOld && certChainRepository.closeChain(oldChain.id(), request.validFromUtc()) != 1) {
                throw new IllegalStateException("旧链闭合失败，可能已被并发轮换");
            }
            grantTrust(deviceIds, firmware.model(), firmware.version(),
                    request.newChainVersion(), request.operator());
            CertChain created = certChainRepository.findById(id).orElseThrow();
            return CertChainView.of(created, isCurrent(created, Instant.now(clock)));
        }, CertChainView.class);
    }

    /**
     * 撤销：请求时刻立即生效（业务时钟）。撤销链不得再用于新拉取，在途任务回执转人工复核。
     */
    public CertChainView revoke(RevokeCertChainRequest request) {
        String fingerprint = String.join("|", "cert.revoke", request.model(), request.firmwareVersion(),
                String.valueOf(request.chainVersion()), request.reason(), request.operator());
        return idempotency.execute(request.requestId(), "cert.revoke", fingerprint, () -> {
            var firmware = firmwareVersionRepository.findForUpdate(request.model(), request.firmwareVersion())
                    .orElseThrow(() -> ApiException.notFound("FIRMWARE_NOT_FOUND",
                            "固件版本未登记: " + request.model() + " " + request.firmwareVersion()));
            CertChain chain = certChainRepository
                    .findByChainVersion(firmware.model(), firmware.version(), request.chainVersion())
                    .orElseThrow(() -> ApiException.notFound("CHAIN_NOT_FOUND",
                            "证书链不存在: " + request.chainVersion()));
            if (chain.status() != CertChainStatus.ACTIVE) {
                throw ApiException.conflict("CHAIN_NOT_ACTIVE",
                        "证书链状态为 " + chain.status() + "，不能撤销");
            }
            String now = Instant.now(clock).toString();
            if (certChainRepository.revoke(chain.id(), now, request.reason()) != 1) {
                throw new IllegalStateException("证书链撤销失败，可能已被并发撤销");
            }
            CertChain revoked = certChainRepository.findById(chain.id()).orElseThrow();
            return CertChainView.of(revoked, false);
        }, CertChainView.class);
    }

    public CompatView upsertCompat(UpsertCompatRequest request) {
        String fingerprint = String.join("|", "compat.upsert", request.model(), request.fromVersion(),
                request.toVersion(), String.valueOf(request.allowed()), request.operator());
        return idempotency.execute(request.requestId(), "compat.upsert", fingerprint, () -> {
            if (request.fromVersion().equals(request.toVersion())) {
                throw ApiException.badRequest("SAME_VERSION", "来源版本与目标版本必须不同");
            }
            compatRepository.upsert(request.model(), request.fromVersion(), request.toVersion(),
                    request.allowed(), request.operator());
            CompatRule rule = compatRepository.find(request.model(), request.fromVersion(),
                    request.toVersion()).orElseThrow();
            return CompatView.of(rule);
        }, CompatView.class);
    }

    public CertChainListResponse listChains(String model, String firmwareVersion) {
        firmwareVersionRepository.find(model, firmwareVersion)
                .orElseThrow(() -> ApiException.notFound("FIRMWARE_NOT_FOUND",
                        "固件版本未登记: " + model + " " + firmwareVersion));
        List<CertChain> chains = certChainRepository.findByFirmware(model, firmwareVersion);
        Instant now = Instant.now(clock);
        List<CertChain> currents = chains.stream().filter(c -> isCurrent(c, now)).toList();
        if (currents.size() > 1) {
            throw new IllegalStateException("同一固件版本存在多条当前有效链，数据被破坏");
        }
        Integer currentVersion = currents.isEmpty() ? null : currents.get(0).chainVersion();
        List<CertChainView> views = chains.stream()
                .map(c -> CertChainView.of(c, c.chainVersion() == (currentVersion == null ? -1 : currentVersion)))
                .toList();
        return new CertChainListResponse(model, firmwareVersion, currentVersion, views);
    }

    public List<CompatView> listCompat(String model) {
        return compatRepository.findByModel(model).stream().map(CompatView::of).toList();
    }

    /**
     * 启动发布守卫：目标固件未纳管证书时放行（存量行为）；纳管后必须兼容矩阵允许且当前存在有效链。
     * 设备集合在启动时未知，逐设备信任校验发生在批量启动/拉取建任务时。
     */
    public void assertStartupGuard(String model, String fromVersion, String toVersion) {
        if (firmwareVersionRepository.find(model, toVersion).isEmpty()) {
            return;
        }
        CompatRule rule = compatRepository.find(model, fromVersion, toVersion)
                .orElseThrow(() -> ApiException.unprocessable("COMPAT_RULE_MISSING",
                        "兼容矩阵缺少规则: " + fromVersion + " -> " + toVersion));
        if (!rule.allowed()) {
            throw ApiException.unprocessable("COMPAT_NOT_ALLOWED",
                    "兼容矩阵拒绝该版本路径: " + fromVersion + " -> " + toVersion);
        }
        if (findCurrentChain(model, toVersion, Instant.now(clock)).isEmpty()) {
            throw ApiException.unprocessable("NO_VALID_CERT_CHAIN",
                    "固件 " + toVersion + " 当前时刻无有效签名证书链");
        }
    }

    /**
     * 设备新建任务守卫。目标固件未纳管证书时返回 null（走存量逻辑）；纳管后依次校验
     * 兼容性、当前有效链、设备信任集合，任一不过抛 422 且错误码可区分。
     *
     * @return 必须固化到任务上的当前证书链；未纳管固件返回 null
     */
    public CertChain resolvePullChain(Device device, ReleaseOrder order) {
        String model = order.model();
        String toVersion = order.toVersion();
        if (firmwareVersionRepository.find(model, toVersion).isEmpty()) {
            return null;
        }
        CompatRule rule = compatRepository.find(model, order.fromVersion(), toVersion)
                .orElseThrow(() -> ApiException.unprocessable("COMPAT_RULE_MISSING",
                        "兼容矩阵缺少规则: " + order.fromVersion() + " -> " + toVersion));
        if (!rule.allowed()) {
            throw ApiException.unprocessable("COMPAT_NOT_ALLOWED",
                    "兼容矩阵拒绝该版本路径: " + order.fromVersion() + " -> " + toVersion);
        }
        CertChain current = findCurrentChain(model, toVersion, Instant.now(clock))
                .orElseThrow(() -> ApiException.unprocessable("NO_VALID_CERT_CHAIN",
                        "固件 " + toVersion + " 当前时刻无有效签名证书链"));
        if (!deviceTrustRepository.exists(device.deviceId(), model, toVersion, current.chainVersion())) {
            throw ApiException.unprocessable("DEVICE_CERT_NOT_TRUSTED",
                    "设备 " + device.deviceId() + " 不信任固件 " + toVersion
                            + " 的当前证书链版本 " + current.chainVersion());
        }
        return current;
    }

    /**
     * 按任务快照的（型号，固件版本，链版本）读取链；链不存在属于数据不一致。
     */
    public CertChain getSnapshotChain(String model, String firmwareVersion, int chainVersion) {
        return certChainRepository.findByChainVersion(model, firmwareVersion, chainVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "任务固化的证书链不存在: " + model + " " + firmwareVersion + " v" + chainVersion));
    }

    public Optional<CertChain> findCurrentChain(String model, String firmwareVersion, Instant at) {
        List<CertChain> currents = certChainRepository.findByFirmware(model, firmwareVersion).stream()
                .filter(c -> isCurrent(c, at)).toList();
        if (currents.size() > 1) {
            throw new IllegalStateException("同一固件版本同一时刻存在多条有效链: " + model + " " + firmwareVersion);
        }
        return currents.stream().findFirst();
    }

    private boolean isCurrent(CertChain chain, Instant at) {
        if (chain.status() != CertChainStatus.ACTIVE) {
            return false;
        }
        Instant from = parseInstant(chain.validFromUtc(), "validFromUtc");
        if (at.isBefore(from)) {
            return false;
        }
        return chain.validToUtc() == null || at.isBefore(parseInstant(chain.validToUtc(), "validToUtc"));
    }

    private void grantTrust(List<String> deviceIds, String model, String firmwareVersion,
                            int chainVersion, String operator) {
        for (String deviceId : deviceIds) {
            deviceTrustRepository.addIfAbsent(deviceId, model, firmwareVersion, chainVersion, operator);
        }
    }

    private void assertDevicesExist(List<String> deviceIds) {
        for (String deviceId : deviceIds) {
            deviceRepository.findById(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
        }
    }

    private List<String> normalizeDevices(List<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            throw ApiException.badRequest("EMPTY_DEVICE_SET", "设备集合不能为空");
        }
        List<String> distinct = deviceIds.stream().distinct().sorted().toList();
        if (distinct.size() != deviceIds.size()) {
            throw ApiException.badRequest("DUPLICATE_DEVICE_IDS", "设备集合存在重复设备");
        }
        return distinct;
    }

    private Instant parseInstant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_INSTANT", field + " 不是合法的 UTC ISO-8601 时刻: " + value);
        }
    }
}
