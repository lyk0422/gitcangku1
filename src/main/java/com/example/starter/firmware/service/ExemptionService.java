package com.example.starter.firmware.service;

import com.example.starter.firmware.api.ExemptionEntry;
import com.example.starter.firmware.api.ExemptionView;
import com.example.starter.firmware.api.ModifyExemptionsRequest;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.ExemptionRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.support.UtcTimes;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布单模块豁免：按设备组逐模块显式配置，必须带到期时刻（UTC，左闭右开，到达即失效）。
 * 豁免修改为整体替换：先验证完整后态（发布单未取消、时刻合法且未到期、条目无重复），
 * 再在持有发布单行锁的同一事务内写入新版本全部条目并递增发布单豁免版本；
 * 与拉取、回执、取消、恢复串行裁决，失败回滚不留部分结果。
 * 豁免只放行模块兼容门禁，绝不绕过签名与失败率暂停等既有门禁。
 */
@Service
public class ExemptionService {

    private final ExemptionRepository exemptionRepository;
    private final ReleaseRepository releaseRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public ExemptionService(ExemptionRepository exemptionRepository, ReleaseRepository releaseRepository,
                            IdempotencyService idempotency, Clock clock) {
        this.exemptionRepository = exemptionRepository;
        this.releaseRepository = releaseRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 整体修改某发布单的模块豁免；entries 为后态全量集合，允许空列表（清空豁免，仍产生新版本）。
     */
    public ExemptionView modify(long releaseId, ModifyExemptionsRequest request) {
        // 先验证完整后态（不持锁部分）：时刻合法、到期晚于当前时刻、条目唯一
        Instant now = Instant.now(clock);
        Map<String, ExemptionEntry> unique = new HashMap<>();
        for (ExemptionEntry entry : request.entries() == null ? List.<ExemptionEntry>of() : request.entries()) {
            Instant expiresAt = UtcTimes.parse(entry.expiresAtUtc());
            if (!expiresAt.isAfter(now)) {
                throw ApiException.unprocessable("EXEMPTION_ALREADY_EXPIRED",
                        "豁免到期时刻必须晚于当前时刻（UTC，左闭右开）: " + entry.deviceGroup()
                                + "/" + entry.moduleName() + " 到期 " + entry.expiresAtUtc());
            }
            String key = entry.deviceGroup() + "|" + entry.moduleName();
            if (unique.put(key, entry) != null) {
                throw ApiException.unprocessable("DUPLICATE_EXEMPTION",
                        "豁免清单存在重复条目: " + entry.deviceGroup() + "/" + entry.moduleName());
            }
        }
        List<ExemptionEntry> ordered = request.entries() == null ? List.of() : request.entries();
        String fingerprint = String.join("|", "exemption.modify", String.valueOf(releaseId),
                fingerprintOf(ordered));
        return idempotency.execute(request.requestId(), "exemption.modify", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.status() == ReleaseStatus.CANCELLED) {
                throw ApiException.conflict("RELEASE_CANCELLED", "发布单已取消，不能修改模块豁免");
            }
            String nowUtc = UtcTimes.now(clock);
            int newVersion = order.exemptionVersion() + 1;
            for (ExemptionEntry entry : ordered) {
                exemptionRepository.insertEntry(releaseId, newVersion, entry.deviceGroup(),
                        entry.moduleName(), entry.expiresAtUtc(), nowUtc);
            }
            releaseRepository.bumpExemptionVersion(releaseId);
            return viewOf(releaseId, newVersion, nowUtc, ordered);
        }, ExemptionView.class);
    }

    /**
     * 查询某发布单豁免版本；version 为 null 查最新版本，版本0或从未修改返回空 entries。只读，不修改状态。
     * 当前版本号以发布单行 exemption_version 为权威计数（清空豁免的版本不含任何条目行）。
     */
    public ExemptionView get(long releaseId, Integer version) {
        ReleaseOrder order = releaseRepository.findById(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
        int target = version == null ? order.exemptionVersion() : version;
        if (target <= 0) {
            return new ExemptionView(releaseId, 0, null, List.of());
        }
        var entries = exemptionRepository.findByVersion(releaseId, target);
        if (entries.isEmpty()) {
            // 最新版本允许为空集合（整体清空产生的版本）；显式查询超过当前版本或不存在的版本返回 404
            if (version != null && version > order.exemptionVersion()) {
                throw ApiException.notFound("EXEMPTION_VERSION_NOT_FOUND",
                        "豁免版本不存在: releaseId=" + releaseId + ", version=" + target);
            }
            return new ExemptionView(releaseId, target, null, List.of());
        }
        return new ExemptionView(releaseId, target, entries.get(0).createdAtUtc(),
                entries.stream().map(ExemptionView.Item::of).toList());
    }

    private ExemptionView viewOf(long releaseId, int version, String createdAtUtc, List<ExemptionEntry> entries) {
        return new ExemptionView(releaseId, version, createdAtUtc,
                entries.stream()
                        .sorted((a, b) -> {
                            int cmp = a.deviceGroup().compareTo(b.deviceGroup());
                            return cmp != 0 ? cmp : a.moduleName().compareTo(b.moduleName());
                        })
                        .map(entry -> new ExemptionView.Item(entry.deviceGroup(), entry.moduleName(),
                                entry.expiresAtUtc()))
                        .toList());
    }

    private String fingerprintOf(List<ExemptionEntry> entries) {
        return entries.stream()
                .sorted((a, b) -> {
                    int cmp = a.deviceGroup().compareTo(b.deviceGroup());
                    return cmp != 0 ? cmp : a.moduleName().compareTo(b.moduleName());
                })
                .map(entry -> String.join(":", entry.deviceGroup(), entry.moduleName(), entry.expiresAtUtc()))
                .reduce((a, b) -> a + ";" + b)
                .orElse("");
    }
}
