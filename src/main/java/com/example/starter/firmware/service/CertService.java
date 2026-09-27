package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CertChainView;
import com.example.starter.firmware.api.RegisterCertChainRequest;
import com.example.starter.firmware.api.RevokeCertRequest;
import com.example.starter.firmware.api.RolloverCertRequest;
import com.example.starter.firmware.domain.CertChainStatus;
import com.example.starter.firmware.domain.DeviceTrust;
import com.example.starter.firmware.domain.FirmwareCertChain;
import com.example.starter.firmware.domain.FirmwareVersion;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.CertChainDeviceRepository;
import com.example.starter.firmware.repo.CertChainRepository;
import com.example.starter.firmware.repo.DeviceTrustRepository;
import com.example.starter.firmware.repo.FirmwareVersionRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * 固件签名证书链生命周期：登记、轮换、撤销与按请求时刻的当前链查询。
 * 所有变更在同一事务内锁定该固件的全部链后串行裁决，保证：
 * 同一固件任意 UTC 时刻至多一条有效（ACTIVE 且落在半开区间内）链，轮换区间不重叠。
 */
@Service
public class CertService {

    private final CertChainRepository chainRepository;
    private final CertChainDeviceRepository chainDeviceRepository;
    private final FirmwareVersionRepository firmwareRepository;
    private final DeviceTrustRepository trustRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public CertService(CertChainRepository chainRepository,
                       CertChainDeviceRepository chainDeviceRepository,
                       FirmwareVersionRepository firmwareRepository,
                       DeviceTrustRepository trustRepository,
                       IdempotencyService idempotency, Clock clock) {
        this.chainRepository = chainRepository;
        this.chainDeviceRepository = chainDeviceRepository;
        this.firmwareRepository = firmwareRepository;
        this.trustRepository = trustRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 登记第一条或额外的证书链。新区间不得与该固件任何既有链（含已撤销的历史区间）重叠。
     * 链版本由服务端按固件内最大值加一分配。
     */
    public CertChainView registerChain(RegisterCertChainRequest request) {
        Instant validFrom = parseInstant(request.validFromUtc(), "validFromUtc");
        Instant validTo = request.validToUtc() == null || request.validToUtc().isBlank()
                ? null : parseInstant(request.validToUtc(), "validToUtc");
        if (validTo != null && !validFrom.isBefore(validTo)) {
            throw ApiException.badRequest("INVALID_CERT_INTERVAL", "生效时刻必须早于到期时刻（半开区间）");
        }
        String deviceSetFingerprint = deviceSetFingerprint(request.deviceIds());
        String fingerprint = String.join("|", "cert.register", request.model(), request.firmwareVersion(),
                request.subjectDn(), request.issuerDn(), request.fingerprintSha256(),
                request.validFromUtc(), String.valueOf(validTo), deviceSetFingerprint, request.operator());
        return idempotency.execute(request.requestId(), "cert.register", fingerprint, () -> {
            FirmwareVersion firmware = requireFirmware(request.model(), request.firmwareVersion());
            List<FirmwareCertChain> chains = chainRepository.findByFirmwareForUpdate(firmware.id());
            for (FirmwareCertChain existing : chains) {
                if (CertIntervalPolicy.overlaps(validFrom, validTo, existing.validFrom(), existing.validTo())) {
                    throw ApiException.conflict("CERT_INTERVAL_OVERLAP",
                            "新区间与链版本 " + existing.chainVersion() + " 的有效区间重叠");
                }
            }
            int chainVersion = chains.stream().mapToInt(FirmwareCertChain::chainVersion).max().orElse(0) + 1;
            long chainId = chainRepository.insert(firmware.id(), chainVersion, request.subjectDn(),
                    request.issuerDn(), request.fingerprintSha256(), validFrom, validTo);
            saveDeviceSet(chainId, request.deviceIds());
            return view(chainRepository.findById(chainId).orElseThrow(), firmware);
        }, CertChainView.class);
    }

    /**
     * 轮换：必须指定旧链版本与新链版本。旧链须为 ACTIVE 的开放区间链，在生效时刻被截断；
     * 新链版本必须为该固件下一序号，自生效时刻起开放有效。半开区间端点相接、不重叠。
     */
    public CertChainView rollover(RolloverCertRequest request) {
        Instant effectiveAt = parseInstant(request.effectiveAtUtc(), "effectiveAtUtc");
        String deviceSetFingerprint = deviceSetFingerprint(request.deviceIds());
        String fingerprint = String.join("|", "cert.rollover", request.model(), request.firmwareVersion(),
                String.valueOf(request.oldChainVersion()), String.valueOf(request.newChainVersion()),
                request.effectiveAtUtc(), request.subjectDn(), request.issuerDn(),
                request.fingerprintSha256(), deviceSetFingerprint, request.operator());
        return idempotency.execute(request.requestId(), "cert.rollover", fingerprint, () -> {
            FirmwareVersion firmware = requireFirmware(request.model(), request.firmwareVersion());
            List<FirmwareCertChain> chains = chainRepository.findByFirmwareForUpdate(firmware.id());
            FirmwareCertChain oldChain = chains.stream()
                    .filter(c -> c.chainVersion() == request.oldChainVersion())
                    .findFirst()
                    .orElseThrow(() -> ApiException.notFound("CERT_CHAIN_NOT_FOUND",
                            "旧证书链版本不存在: " + request.oldChainVersion()));
            int expectedNewVersion = chains.stream().mapToInt(FirmwareCertChain::chainVersion).max().orElse(0) + 1;
            if (request.newChainVersion() != expectedNewVersion) {
                throw ApiException.conflict("CERT_CHAIN_VERSION_SEQUENCE",
                        "新链版本必须为下一序号 " + expectedNewVersion + "，实际: " + request.newChainVersion());
            }
            if (oldChain.status() != CertChainStatus.ACTIVE) {
                throw ApiException.conflict("OLD_CERT_NOT_ACTIVE",
                        "旧证书链版本 " + oldChain.chainVersion() + " 已撤销，不能轮换");
            }
            if (oldChain.validTo() != null) {
                throw ApiException.conflict("OLD_CERT_ALREADY_CLOSED",
                        "旧证书链版本 " + oldChain.chainVersion() + " 的有效区间已截止，只能轮换当前开放链");
            }
            if (effectiveAt.isBefore(Instant.now(clock))) {
                throw ApiException.conflict("ROLLOVER_IN_PAST", "轮换生效时刻不得早于请求时刻");
            }
            if (effectiveAt.isBefore(oldChain.validFrom())) {
                throw ApiException.badRequest("ROLLOVER_BEFORE_CHAIN_START",
                        "轮换生效时刻不得早于旧链生效时刻");
            }
            // 调用方已在本事务锁定该固件全部链行，旧链必为 ACTIVE 开放区间，截断必恰好命中一行
            int truncated = chainRepository.truncateOpenEnd(oldChain.id(), effectiveAt);
            if (truncated != 1) {
                throw ApiException.conflict("OLD_CERT_ALREADY_CLOSED",
                        "旧证书链版本 " + oldChain.chainVersion() + " 的有效区间已截止");
            }
            long newChainId = chainRepository.insert(firmware.id(), request.newChainVersion(),
                    request.subjectDn(), request.issuerDn(), request.fingerprintSha256(),
                    effectiveAt, null);
            saveDeviceSet(newChainId, request.deviceIds());
            return view(chainRepository.findById(newChainId).orElseThrow(), firmware);
        }, CertChainView.class);
    }

    /**
     * 撤销：链置 REVOKED（终态），开放区间截止到撤销时刻；尚未生效的未来链截止到其生效时刻（零窗口）。
     */
    public CertChainView revoke(RevokeCertRequest request) {
        String fingerprint = String.join("|", "cert.revoke", request.model(), request.firmwareVersion(),
                String.valueOf(request.chainVersion()), request.operator());
        return idempotency.execute(request.requestId(), "cert.revoke", fingerprint, () -> {
            FirmwareVersion firmware = requireFirmware(request.model(), request.firmwareVersion());
            List<FirmwareCertChain> chains = chainRepository.findByFirmwareForUpdate(firmware.id());
            FirmwareCertChain chain = chains.stream()
                    .filter(c -> c.chainVersion() == request.chainVersion())
                    .findFirst()
                    .orElseThrow(() -> ApiException.notFound("CERT_CHAIN_NOT_FOUND",
                            "证书链版本不存在: " + request.chainVersion()));
            if (chain.status() != CertChainStatus.ACTIVE) {
                throw ApiException.conflict("CERT_NOT_ACTIVE",
                        "证书链版本 " + chain.chainVersion() + " 已撤销，不能重复撤销");
            }
            Instant now = Instant.now(clock);
            int affected;
            if (chain.validTo() == null) {
                Instant end = now.isBefore(chain.validFrom()) ? chain.validFrom() : now;
                affected = chainRepository.revokeOpenEnd(chain.id(), now, end);
            } else {
                affected = chainRepository.revokeKeepEnd(chain.id(), now);
            }
            if (affected != 1) {
                throw ApiException.conflict("CERT_NOT_ACTIVE",
                        "证书链版本 " + chain.chainVersion() + " 已被并发撤销");
            }
            return view(chainRepository.findById(chain.id()).orElseThrow(), firmware);
        }, CertChainView.class);
    }

    public List<CertChainView> listChains(String model, String firmwareVersion) {
        FirmwareVersion firmware = requireFirmware(model, firmwareVersion);
        return chainRepository.findByFirmware(firmware.id()).stream()
                .map(chain -> view(chain, firmware)).toList();
    }

    /**
     * 在事务内锁定链行并判定是否已撤销：使回执与撤销按提交顺序裁决。
     */
    public boolean isChainRevokedForUpdate(long chainId) {
        return chainRepository.findByIdForUpdate(chainId)
                .map(chain -> chain.status() == CertChainStatus.REVOKED)
                .orElse(false);
    }

    /**
     * 链授权设备集合（空列表表示全开放）。
     */
    public List<String> deviceIdsOf(long chainId) {
        return chainDeviceRepository.findDeviceIds(chainId);
    }

    /**
     * 请求时刻唯一有效链；固件未登记任何链时返回 empty（未启用证书的固件走历史流程）。
     */
    public Optional<FirmwareCertChain> currentForFirmware(long firmwareId, Instant at) {
        return CertIntervalPolicy.currentAt(chainRepository.findByFirmware(firmwareId), at);
    }

    /**
     * 在事务内锁定某固件的全部证书链行并取请求时刻当前链，
     * 与轮换/撤销串行化，使启动与拉取按链行锁的提交顺序裁决。
     */
    public Optional<FirmwareCertChain> lockCurrentForFirmware(long firmwareId, Instant at) {
        return CertIntervalPolicy.currentAt(chainRepository.findByFirmwareForUpdate(firmwareId), at);
    }

    /**
     * 拉取/批量启动时的设备信任集合校验（仅用于启用证书的固件）：
     * 设备必须有最终信任记录且 trusted=true，并且当链授权集合非空时必须位于集合内；
     * 显式 trusted=false 或无信任记录一律拒绝（批量启动返回 422）。
     */
    public void assertDeviceAccepted(FirmwareCertChain chain, String deviceId) {
        DeviceTrust trust = trustRepository.findByDevice(deviceId).orElse(null);
        if (trust == null || !trust.trusted()) {
            throw ApiException.unprocessable("DEVICE_NOT_TRUSTED",
                    "设备最终信任状态缺失或为不信任: " + deviceId);
        }
        if (chainDeviceRepository.countDevices(chain.id()) > 0
                && !chainDeviceRepository.exists(chain.id(), deviceId)) {
            throw ApiException.unprocessable("DEVICE_NOT_IN_CERT_SCOPE",
                    "设备不在证书链版本 " + chain.chainVersion() + " 的授权集合内: " + deviceId);
        }
    }

    private FirmwareVersion requireFirmware(String model, String version) {
        return firmwareRepository.findByModelVersion(model, version)
                .orElseThrow(() -> ApiException.notFound("FIRMWARE_NOT_FOUND",
                        "固件版本未登记: " + model + "/" + version));
    }

    private void saveDeviceSet(long chainId, List<String> deviceIds) {
        if (deviceIds == null) {
            return;
        }
        for (String deviceId : deviceIds.stream().distinct().toList()) {
            chainDeviceRepository.addDevice(chainId, deviceId);
        }
    }

    private CertChainView view(FirmwareCertChain chain, FirmwareVersion firmware) {
        return CertChainView.of(chain, firmware.model(), firmware.version(),
                chainDeviceRepository.findDeviceIds(chain.id()));
    }

    private static Instant parseInstant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_INSTANT",
                    field + " 必须为 UTC ISO-8601 时刻（如 2026-09-27T00:00:00Z）");
        }
    }

    private static String deviceSetFingerprint(List<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return "";
        }
        return String.join(",", deviceIds.stream().distinct().sorted().toList());
    }
}
