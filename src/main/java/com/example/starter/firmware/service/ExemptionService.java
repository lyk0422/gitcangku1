package com.example.starter.firmware.service;

import com.example.starter.firmware.api.ExemptionHistoryResponse;
import com.example.starter.firmware.api.ExemptionListResponse;
import com.example.starter.firmware.api.ExemptionUpdateRequest;
import com.example.starter.firmware.domain.ExemptionEntry;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.ExemptionRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 发布单模块豁免：每次修改整体替换为新版本（历史版本保留），按设备组与模块显式生效，
 * 到期时刻左闭右开自动失效。豁免只作用于模块兼容校验，不绕过签名、失败率暂停等既有门禁。
 */
@Service
public class ExemptionService {

    private final ExemptionRepository exemptionRepository;
    private final ReleaseRepository releaseRepository;
    private final ReleaseService releaseService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public ExemptionService(ExemptionRepository exemptionRepository,
                            ReleaseRepository releaseRepository, ReleaseService releaseService,
                            IdempotencyService idempotency, Clock clock) {
        this.exemptionRepository = exemptionRepository;
        this.releaseRepository = releaseRepository;
        this.releaseService = releaseService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 整体替换豁免集合：先校验完整最终状态（发布单未终结、到期时刻合法且在未来、
     * 请求内（设备组，模块）不重复），再在一个事务内写入新版本并推进发布单豁免版本。
     */
    public ExemptionListResponse replace(long releaseId, ExemptionUpdateRequest request) {
        StringBuilder fingerprint = new StringBuilder("release.exemptions.replace|").append(releaseId);
        for (ExemptionUpdateRequest.ExemptionEntry entry : request.exemptions()) {
            fingerprint.append('|').append(entry.deviceGroup()).append(',')
                    .append(String.join("+", entry.modules())).append(',').append(entry.expiresAtUtc());
        }
        return idempotency.execute(request.requestId(), "release.exemptions.replace",
                fingerprint.toString(), () -> {
                    ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                            .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND",
                                    "发布单不存在: " + releaseId));
                    if (order.status() == ReleaseStatus.CANCELLED) {
                        throw ApiException.conflict("RELEASE_CANCELLED", "发布单已取消，不能修改豁免");
                    }
                    Instant now = Instant.now(clock);
                    List<ValidatedExemption> validated = validate(request, now);
                    int newVersion = order.exemptionVersion() + 1;
                    String createdAtUtc = now.toString();
                    for (ValidatedExemption exemption : validated) {
                        for (String module : exemption.modules()) {
                            exemptionRepository.insert(releaseId, newVersion, exemption.deviceGroup(),
                                    module, exemption.expiresAtUtc(), createdAtUtc);
                        }
                    }
                    releaseRepository.updateExemptionVersion(releaseId, newVersion);
                    return new ExemptionListResponse(releaseId, newVersion, toGroups(validated));
                }, ExemptionListResponse.class);
    }

    /**
     * 当前豁免（只读，不改变状态）：发布单当前豁免版本对应的豁免集合。
     */
    public ExemptionListResponse current(long releaseId) {
        ReleaseOrder order = releaseService.findOrder(releaseId);
        List<ExemptionEntry> rows = exemptionRepository.findByReleaseAndVersion(releaseId,
                order.exemptionVersion());
        return new ExemptionListResponse(releaseId, order.exemptionVersion(), groupRows(rows));
    }

    /**
     * 豁免历史（只读，不改变状态）：全部版本按版本号升序。
     */
    public ExemptionHistoryResponse history(long releaseId) {
        releaseService.findOrder(releaseId);
        List<ExemptionEntry> rows = exemptionRepository.findByRelease(releaseId);
        Map<Integer, List<ExemptionEntry>> byVersion = new TreeMap<>();
        for (ExemptionEntry row : rows) {
            byVersion.computeIfAbsent(row.exemptionVersion(), key -> new ArrayList<>()).add(row);
        }
        List<ExemptionHistoryResponse.ExemptionVersionView> versions = new ArrayList<>();
        for (Map.Entry<Integer, List<ExemptionEntry>> entry : byVersion.entrySet()) {
            String createdAtUtc = entry.getValue().get(0).createdAtUtc();
            versions.add(new ExemptionHistoryResponse.ExemptionVersionView(entry.getKey(),
                    createdAtUtc, groupRows(entry.getValue())));
        }
        return new ExemptionHistoryResponse(releaseId, versions);
    }

    /**
     * 拉取校验使用：指定豁免版本下，该设备组在 now 时刻仍有效（now < 到期时刻）的豁免模块集合。
     */
    public Set<String> activeExemptedModules(long releaseId, int exemptionVersion,
                                             String deviceGroup, Instant now) {
        Set<String> exempted = new HashSet<>();
        if (exemptionVersion <= 0) {
            return exempted;
        }
        for (ExemptionEntry row : exemptionRepository.findByReleaseAndVersion(releaseId,
                exemptionVersion)) {
            if (row.deviceGroup().equals(deviceGroup) && now.isBefore(Instant.parse(row.expiresAtUtc()))) {
                exempted.add(row.module());
            }
        }
        return exempted;
    }

    private record ValidatedExemption(String deviceGroup, List<String> modules, String expiresAtUtc) {
    }

    private List<ValidatedExemption> validate(ExemptionUpdateRequest request, Instant now) {
        Set<String> seen = new HashSet<>();
        List<ValidatedExemption> validated = new ArrayList<>();
        for (ExemptionUpdateRequest.ExemptionEntry entry : request.exemptions()) {
            Instant expiresAt;
            try {
                expiresAt = Instant.parse(entry.expiresAtUtc());
            } catch (DateTimeParseException e) {
                throw ApiException.badRequest("INVALID_EXEMPTION_EXPIRY",
                        "到期时刻必须是UTC ISO-8601格式（如2026-10-01T00:00:00Z）: " + entry.expiresAtUtc());
            }
            if (!expiresAt.isAfter(now)) {
                throw ApiException.badRequest("INVALID_EXEMPTION_EXPIRY",
                        "到期时刻必须晚于当前时刻（有效期左闭右开）: " + entry.expiresAtUtc());
            }
            List<String> modules = entry.modules().stream().distinct().sorted().toList();
            for (String module : modules) {
                if (!seen.add(entry.deviceGroup() + " " + module)) {
                    throw ApiException.badRequest("DUPLICATE_EXEMPTION",
                            "同一请求内豁免重复: " + entry.deviceGroup() + "/" + module);
                }
            }
            validated.add(new ValidatedExemption(entry.deviceGroup(), modules, entry.expiresAtUtc()));
        }
        return validated;
    }

    private static List<ExemptionListResponse.ExemptionGroup> toGroups(
            List<ValidatedExemption> validated) {
        return validated.stream()
                .map(exemption -> new ExemptionListResponse.ExemptionGroup(exemption.deviceGroup(),
                        exemption.modules(), exemption.expiresAtUtc()))
                .toList();
    }

    /**
     * 豁免行按（设备组，到期时刻）归组，模块升序；组间按设备组、到期时刻升序，排序稳定。
     */
    private static List<ExemptionListResponse.ExemptionGroup> groupRows(List<ExemptionEntry> rows) {
        Map<String, List<String>> modulesByKey = new LinkedHashMap<>();
        for (ExemptionEntry row : rows) {
            String key = row.deviceGroup() + " " + row.expiresAtUtc();
            modulesByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(row.module());
        }
        List<ExemptionListResponse.ExemptionGroup> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : modulesByKey.entrySet()) {
            String key = entry.getKey();
            String deviceGroup = key.substring(0, key.indexOf(' '));
            String expiresAtUtc = key.substring(key.indexOf(' ') + 1);
            result.add(new ExemptionListResponse.ExemptionGroup(deviceGroup,
                    entry.getValue().stream().sorted().toList(), expiresAtUtc));
        }
        return result;
    }
}
