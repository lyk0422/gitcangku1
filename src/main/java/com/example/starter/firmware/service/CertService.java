package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CertChainListResponse;
import com.example.starter.firmware.api.CertChainView;
import com.example.starter.firmware.api.RegisterCertRequest;
import com.example.starter.firmware.domain.CertChain;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.CertChainRepository;
import com.example.starter.firmware.repo.DeviceTrustRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 证书链生命周期：登记（首条）与轮换（须指定旧链版本）、撤销、查询。
 * 同一固件版本的登记/轮换按固件版本分段锁串行化，保证任意时刻至多一条有效链、区间不重叠；
 * 撤销与回执按提交顺序裁决（回执在事务内读取已提交的撤销状态）。
 */
@Service
public class CertService {

    private static final int LOCK_STRIPES = 64;

    private final Object[] locks = new Object[LOCK_STRIPES];

    private final CertChainRepository certChainRepository;
    private final DeviceTrustRepository deviceTrustRepository;
    private final DeviceService deviceService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public CertService(CertChainRepository certChainRepository, DeviceTrustRepository deviceTrustRepository,
                       DeviceService deviceService, IdempotencyService idempotency, Clock clock) {
        this.certChainRepository = certChainRepository;
        this.deviceTrustRepository = deviceTrustRepository;
        this.deviceService = deviceService;
        this.idempotency = idempotency;
        this.clock = clock;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    /**
     * 登记或轮换证书链。certKey（requestId）指纹含固件版本、链版本、生效时刻、设备集合与操作者；
     * 同键同参重放原结果，失败不占键。
     */
    public CertChainView register(RegisterCertRequest request) {
        Instant validFrom = parseUtc(request.validFromUtc());
        Instant validTo = parseUtc(request.validToUtc());
        if (!validFrom.isBefore(validTo)) {
            throw ApiException.badRequest("CERT_INTERVAL_INVALID", "生效时刻必须早于失效时刻");
        }
        List<String> deviceIds = request.deviceIds() == null ? List.of() : request.deviceIds();
        String deviceSet = String.join(",", new TreeSet<>(deviceIds));
        String fingerprint = String.join("|", "cert.register", request.firmwareVersion(),
                request.chainVersion(), request.validFromUtc(), request.validToUtc(), deviceSet,
                request.operator(), request.previousChainVersion() == null ? "" : request.previousChainVersion());
        synchronized (locks[Math.floorMod(request.firmwareVersion().hashCode(), LOCK_STRIPES)]) {
            return registerLocked(request, fingerprint, deviceIds);
        }
    }

    private CertChainView registerLocked(RegisterCertRequest request, String fingerprint,
                                         List<String> deviceIds) {
        return idempotency.execute(request.requestId(), "cert.register", fingerprint, () -> {
            List<CertChain> existing = certChainRepository.findByFirmwareVersion(request.firmwareVersion());
            validateRotation(request, existing);
            validateNoOverlap(request, existing);
            for (String deviceId : deviceIds) {
                deviceService.findDevice(deviceId);
            }
            long chainId;
            try {
                chainId = certChainRepository.insert(request.firmwareVersion(), request.chainVersion(),
                        request.validFromUtc(), request.validToUtc(), request.operator());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("CERT_CHAIN_EXISTS",
                        "证书链版本已存在: " + request.chainVersion());
            }
            for (String deviceId : deviceIds) {
                deviceTrustRepository.grant(deviceId, chainId);
            }
            return CertChainView.of(findChain(chainId));
        }, CertChainView.class);
    }

    /**
     * 撤销证书链：撤销后不再作为当前链用于新任务；已固化该链的任务回执转人工复核。
     * 重复撤销（新 requestId）幂等返回当前状态。
     */
    public CertChainView revoke(long chainId, String requestId) {
        String fingerprint = String.join("|", "cert.revoke", String.valueOf(chainId));
        return idempotency.execute(requestId, "cert.revoke", fingerprint, () -> {
            CertChain chain = certChainRepository.findByIdForUpdate(chainId)
                    .orElseThrow(() -> ApiException.notFound("CERT_CHAIN_NOT_FOUND", "证书链不存在: " + chainId));
            if (chain.revokedAtUtc() == null) {
                certChainRepository.revoke(chainId, Instant.now(clock).toString());
            }
            return CertChainView.of(findChain(chainId));
        }, CertChainView.class);
    }

    /**
     * 查询某固件版本已登记的全部证书链（含已撤销），按生效时刻升序。
     */
    public CertChainListResponse list(String firmwareVersion) {
        return new CertChainListResponse(certChainRepository.findByFirmwareVersion(firmwareVersion)
                .stream().map(CertChainView::of).toList());
    }

    /**
     * 查询请求时刻的当前链：唯一覆盖该时刻且未撤销的链；不存在则 404。
     */
    public CertChainView current(String firmwareVersion) {
        return currentChain(firmwareVersion, Instant.now(clock))
                .map(CertChainView::of)
                .orElseThrow(() -> ApiException.notFound("NO_CURRENT_CERT_CHAIN",
                        "当前时刻无有效证书链: " + firmwareVersion));
    }

    /**
     * 指定时刻唯一有效（覆盖该时刻且未撤销）的链；无任何登记或均失效/撤销时为空。
     */
    public Optional<CertChain> currentChain(String firmwareVersion, Instant moment) {
        return certChainRepository.findByFirmwareVersion(firmwareVersion).stream()
                .filter(chain -> chain.revokedAtUtc() == null && chain.covers(moment))
                .findFirst();
    }

    public boolean hasAnyChain(String firmwareVersion) {
        return !certChainRepository.findByFirmwareVersion(firmwareVersion).isEmpty();
    }

    public CertChain findChain(long chainId) {
        return certChainRepository.findById(chainId)
                .orElseThrow(() -> ApiException.notFound("CERT_CHAIN_NOT_FOUND", "证书链不存在: " + chainId));
    }

    private void validateRotation(RegisterCertRequest request, List<CertChain> existing) {
        if (existing.isEmpty()) {
            if (request.previousChainVersion() != null) {
                throw ApiException.unprocessableEntity("PREVIOUS_CHAIN_NOT_FOUND",
                        "首条证书链登记不得指定旧链版本: " + request.previousChainVersion());
            }
            return;
        }
        if (request.previousChainVersion() == null) {
            throw ApiException.badRequest("PREVIOUS_CHAIN_REQUIRED", "轮换必须指定旧证书链版本");
        }
        boolean previousExists = existing.stream()
                .anyMatch(chain -> chain.chainVersion().equals(request.previousChainVersion()));
        if (!previousExists) {
            throw ApiException.unprocessableEntity("PREVIOUS_CHAIN_NOT_FOUND",
                    "旧证书链版本不存在: " + request.previousChainVersion());
        }
    }

    private void validateNoOverlap(RegisterCertRequest request, List<CertChain> existing) {
        Instant validFrom = Instant.parse(request.validFromUtc());
        Instant validTo = Instant.parse(request.validToUtc());
        for (CertChain chain : existing) {
            Instant existingFrom = Instant.parse(chain.validFromUtc());
            Instant existingTo = Instant.parse(chain.validToUtc());
            if (validFrom.isBefore(existingTo) && existingFrom.isBefore(validTo)) {
                throw ApiException.conflict("CERT_INTERVAL_OVERLAP",
                        "有效区间与已有链 " + chain.chainVersion() + " 重叠");
            }
        }
    }

    private Instant parseUtc(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("CERT_TIME_INVALID", "UTC 时刻须为 ISO-8601 格式: " + value);
        }
    }
}
