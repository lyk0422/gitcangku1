package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CertChainListResponse;
import com.example.starter.firmware.api.CertChainView;
import com.example.starter.firmware.api.CertRejectionListResponse;
import com.example.starter.firmware.api.CertRejectionView;
import com.example.starter.firmware.api.RegisterCertChainRequest;
import com.example.starter.firmware.api.RevokeCertChainRequest;
import com.example.starter.firmware.api.RotateCertChainRequest;
import com.example.starter.firmware.domain.CertChain;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.error.CertRejectionException;
import com.example.starter.firmware.repo.CertChainRepository;
import com.example.starter.firmware.repo.CertRejectionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.TreeSet;

/**
 * 固件签名证书链：登记、轮换、撤销与拉取/启动校验。
 * 同一固件版本的链在事务内统一加行锁，轮换原子截断旧链并插入新链，保证任意时刻至多一条有效链；
 * 撤销与回执、拉取按提交顺序裁决。certKey（requestId）指纹含固件版本、链版本、生效时刻、
 * 设备集合与操作者，同键重放，失败不占键；证书域失败在事务回滚后补写拒绝原因审计日志。
 */
@Service
public class CertService {

    private final CertChainRepository certChainRepository;
    private final CertRejectionRepository rejectionRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public CertService(CertChainRepository certChainRepository, CertRejectionRepository rejectionRepository,
                       IdempotencyService idempotency, Clock clock) {
        this.certChainRepository = certChainRepository;
        this.rejectionRepository = rejectionRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 登记证书链：区间不得与同固件版本既有链（含已撤销）重叠。
     */
    public CertChainView register(String firmwareVersion, RegisterCertChainRequest request) {
        Instant validFrom = parseUtc(request.validFromUtc());
        Instant validTo = parseUtcNullable(request.validToUtc());
        validateInterval(validFrom, validTo);
        List<String> deviceSet = normalizeDeviceSet(request.deviceSet());
        String fingerprint = fingerprint("cert.register", firmwareVersion, request.chainVersion(),
                request.validFromUtc(), request.validToUtc(), deviceSet, request.operator());
        try {
            return idempotency.execute(request.requestId(), "cert.register", fingerprint, () -> {
                List<CertChain> chains = certChainRepository.findByFirmwareForUpdate(firmwareVersion);
                ensureChainVersionAbsent(chains, firmwareVersion, request.chainVersion(), "REGISTER");
                ensureNoOverlap(chains, null, validFrom, validTo, firmwareVersion, "REGISTER");
                long id = insertChain(firmwareVersion, request.chainVersion(), validFrom, validTo,
                        deviceSet, request.operator());
                return CertChainView.of(findChain(id), Instant.now(clock));
            }, CertChainView.class);
        } catch (CertRejectionException e) {
            recordRejection(e);
            throw e;
        }
    }

    /**
     * 轮换：必须指定旧链与新链版本；新链生效时刻即旧链失效时刻（原子截断旧链），
     * 新链区间不得与其他链重叠。旧链到期或撤销后不可再作为当前链。
     */
    public CertChainView rotate(String firmwareVersion, RotateCertChainRequest request) {
        Instant validFrom = parseUtc(request.validFromUtc());
        Instant validTo = parseUtcNullable(request.validToUtc());
        validateInterval(validFrom, validTo);
        List<String> deviceSet = normalizeDeviceSet(request.deviceSet());
        String fingerprint = fingerprint("cert.rotate", firmwareVersion,
                request.oldChainVersion() + ">" + request.newChainVersion(),
                request.validFromUtc(), request.validToUtc(), deviceSet, request.operator());
        try {
            return idempotency.execute(request.requestId(), "cert.rotate", fingerprint, () -> {
                List<CertChain> chains = certChainRepository.findByFirmwareForUpdate(firmwareVersion);
                CertChain oldChain = chains.stream()
                        .filter(c -> c.chainVersion().equals(request.oldChainVersion()))
                        .findFirst()
                        .orElseThrow(() -> CertRejectionException.notFound("CERT_CHAIN_NOT_FOUND",
                                "旧证书链不存在: " + request.oldChainVersion(), "ROTATE",
                                firmwareVersion, request.oldChainVersion(), null));
                ensureChainVersionAbsent(chains, firmwareVersion, request.newChainVersion(), "ROTATE");
                if (!validFrom.isAfter(oldChain.validFromUtc())) {
                    throw CertRejectionException.conflict("CERT_INTERVAL_OVERLAP",
                            "新链生效时刻必须晚于旧链生效时刻: " + oldChain.validFromUtc(), "ROTATE",
                            firmwareVersion, request.newChainVersion(), null);
                }
                if (oldChain.validToUtc() == null || oldChain.validToUtc().isAfter(validFrom)) {
                    certChainRepository.truncateValidTo(oldChain.id(), validFrom);
                }
                ensureNoOverlap(chains, oldChain.chainVersion(), validFrom, validTo, firmwareVersion, "ROTATE");
                long id = insertChain(firmwareVersion, request.newChainVersion(), validFrom, validTo,
                        deviceSet, request.operator());
                return CertChainView.of(findChain(id), Instant.now(clock));
            }, CertChainView.class);
        } catch (CertRejectionException e) {
            recordRejection(e);
            throw e;
        }
    }

    /**
     * 撤销证书链：撤销后不再作为有效链；重复撤销（新 requestId）幂等返回当前状态。
     */
    public CertChainView revoke(String firmwareVersion, String chainVersion, RevokeCertChainRequest request) {
        String fingerprint = fingerprint("cert.revoke", firmwareVersion, chainVersion,
                null, null, List.of(), request.operator());
        try {
            return idempotency.execute(request.requestId(), "cert.revoke", fingerprint, () -> {
                List<CertChain> chains = certChainRepository.findByFirmwareForUpdate(firmwareVersion);
                CertChain chain = chains.stream()
                        .filter(c -> c.chainVersion().equals(chainVersion))
                        .findFirst()
                        .orElseThrow(() -> CertRejectionException.notFound("CERT_CHAIN_NOT_FOUND",
                                "证书链不存在: " + chainVersion, "REVOKE", firmwareVersion, chainVersion, null));
                if (!chain.revoked()) {
                    certChainRepository.revoke(chain.id(), Instant.now(clock));
                }
                return CertChainView.of(findChain(chain.id()), Instant.now(clock));
            }, CertChainView.class);
        } catch (CertRejectionException e) {
            recordRejection(e);
            throw e;
        }
    }

    /**
     * 拉取/批量启动的证书链校验：目标固件未登记任何链时不强制（兼容无证书投放）；
     * 已登记则要求请求时刻存在唯一有效链且设备信任该链，否则 422 且原因可区分。
     * 调用方须处于事务内；本方法对链行加锁，与轮换/撤销按提交顺序裁决。
     *
     * @return 当前有效链；未登记任何链时返回 null
     */
    public CertChain requireCurrentChain(String firmwareVersion, String deviceId, String operation) {
        List<CertChain> chains = certChainRepository.findByFirmwareForUpdate(firmwareVersion);
        if (chains.isEmpty()) {
            return null;
        }
        Instant now = Instant.now(clock);
        CertChain current = chains.stream().filter(c -> c.validAt(now)).findFirst().orElse(null);
        if (current == null) {
            throw noValidChainException(chains, now, firmwareVersion, operation, deviceId);
        }
        if (!current.trustedBy(deviceId)) {
            throw CertRejectionException.unprocessable("DEVICE_NOT_TRUSTED",
                    "设备不在证书链信任集合内: " + deviceId, operation,
                    firmwareVersion, current.chainVersion(), deviceId);
        }
        return current;
    }

    /**
     * 判断任务固化的证书链是否已撤销（回执时调用，须处于事务内，对链行加锁）。
     * 链缺失时按已撤销处理，强制进入人工复核。
     */
    public boolean isChainRevoked(String firmwareVersion, String chainVersion) {
        return certChainRepository.findByFirmwareForUpdate(firmwareVersion).stream()
                .filter(c -> c.chainVersion().equals(chainVersion))
                .findFirst()
                .map(CertChain::revoked)
                .orElse(true);
    }

    /**
     * 证书链查询（只读）。
     */
    public CertChainListResponse listChains(String firmwareVersion) {
        Instant now = Instant.now(clock);
        List<CertChainView> chains = certChainRepository.findByFirmware(firmwareVersion).stream()
                .map(c -> CertChainView.of(c, now))
                .toList();
        return new CertChainListResponse(chains);
    }

    /**
     * 拒绝原因查询（只读）。
     */
    public CertRejectionListResponse listRejections() {
        List<CertRejectionView> rejections = rejectionRepository.findAll().stream()
                .map(CertRejectionView::of)
                .toList();
        return new CertRejectionListResponse(rejections);
    }

    /**
     * 证书域失败在业务事务回滚后补写拒绝原因审计日志（不随回滚消失）。
     */
    public void recordRejection(CertRejectionException e) {
        rejectionRepository.insert(e.operation(), e.firmwareVersion(), e.chainVersion(), e.deviceId(),
                e.code(), e.getMessage(), Instant.now(clock).toString());
    }

    private CertChain findChain(long id) {
        return certChainRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("证书链写入后读取失败: " + id));
    }

    private long insertChain(String firmwareVersion, String chainVersion, Instant validFrom, Instant validTo,
                             List<String> deviceSet, String operator) {
        try {
            return certChainRepository.insert(firmwareVersion, chainVersion, validFrom, validTo,
                    deviceSet, operator);
        } catch (DuplicateKeyException e) {
            throw CertRejectionException.conflict("CERT_CHAIN_EXISTS",
                    "证书链版本已存在: " + chainVersion, "REGISTER", firmwareVersion, chainVersion, null);
        }
    }

    private void ensureChainVersionAbsent(List<CertChain> chains, String firmwareVersion, String chainVersion,
                                          String operation) {
        if (chains.stream().anyMatch(c -> c.chainVersion().equals(chainVersion))) {
            throw CertRejectionException.conflict("CERT_CHAIN_EXISTS",
                    "证书链版本已存在: " + chainVersion, operation, firmwareVersion, chainVersion, null);
        }
    }

    /**
     * 半开区间重叠检查：[from, to) 与既有效区间相交则拒绝；excludeChainVersion 用于轮换时排除已截断的旧链。
     */
    private void ensureNoOverlap(List<CertChain> chains, String excludeChainVersion,
                                 Instant validFrom, Instant validTo, String firmwareVersion, String operation) {
        for (CertChain chain : chains) {
            if (chain.chainVersion().equals(excludeChainVersion)) {
                continue;
            }
            boolean overlap = validFrom.isBefore(chain.validToUtc() == null ? Instant.MAX : chain.validToUtc())
                    && chain.validFromUtc().isBefore(validTo == null ? Instant.MAX : validTo);
            if (overlap) {
                throw CertRejectionException.conflict("CERT_INTERVAL_OVERLAP",
                        "证书链有效区间与链 " + chain.chainVersion() + " 重叠", operation,
                        firmwareVersion, chain.chainVersion(), null);
            }
        }
    }

    private CertRejectionException noValidChainException(List<CertChain> chains, Instant now,
                                                         String firmwareVersion, String operation,
                                                         String deviceId) {
        List<CertChain> nonRevoked = chains.stream().filter(c -> !c.revoked()).toList();
        if (nonRevoked.isEmpty()) {
            return CertRejectionException.unprocessable("CERT_CHAIN_REVOKED",
                    "证书链已全部撤销，固件版本: " + firmwareVersion, operation, firmwareVersion, null, deviceId);
        }
        boolean allFuture = nonRevoked.stream().allMatch(c -> now.isBefore(c.validFromUtc()));
        if (allFuture) {
            return CertRejectionException.unprocessable("CERT_CHAIN_NOT_YET_VALID",
                    "新证书链尚未生效，固件版本: " + firmwareVersion, operation, firmwareVersion,
                    nonRevoked.get(0).chainVersion(), deviceId);
        }
        boolean allExpired = nonRevoked.stream()
                .allMatch(c -> c.validToUtc() != null && !now.isBefore(c.validToUtc()));
        if (allExpired) {
            return CertRejectionException.unprocessable("CERT_CHAIN_EXPIRED",
                    "旧证书链已到期，不可新建任务，固件版本: " + firmwareVersion, operation, firmwareVersion,
                    nonRevoked.get(0).chainVersion(), deviceId);
        }
        return CertRejectionException.unprocessable("NO_VALID_CERT_CHAIN",
                "请求时刻无有效证书链，固件版本: " + firmwareVersion, operation, firmwareVersion, null, deviceId);
    }

    private static String fingerprint(String api, String firmwareVersion, String chainVersion,
                                      String validFromUtc, String validToUtc, List<String> deviceSet,
                                      String operator) {
        return String.join("|", api, firmwareVersion, chainVersion,
                validFromUtc == null ? "" : validFromUtc, validToUtc == null ? "" : validToUtc,
                String.join(",", deviceSet), operator);
    }

    private static List<String> normalizeDeviceSet(List<String> deviceSet) {
        if (deviceSet == null) {
            return List.of();
        }
        return List.copyOf(new TreeSet<>(deviceSet));
    }

    private static Instant parseUtc(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.badRequest("INVALID_UTC_TIME", "UTC 时刻格式非法: " + value);
        }
    }

    private static Instant parseUtcNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseUtc(value);
    }

    private static void validateInterval(Instant validFrom, Instant validTo) {
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw ApiException.badRequest("CERT_INTERVAL_INVALID", "失效时刻必须晚于生效时刻");
        }
    }
}
