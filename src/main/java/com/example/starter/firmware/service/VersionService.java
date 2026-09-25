package com.example.starter.firmware.service;

import com.example.starter.firmware.api.RegisterVersionRequest;
import com.example.starter.firmware.api.VersionChainResponse;
import com.example.starter.firmware.api.VersionView;
import com.example.starter.firmware.domain.FirmwareVersion;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.VersionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 固件版本链：登记直接前置版本（至多一个），校验前置已存在且不形成环，违反返回 422。
 * 已登记版本再次登记视为修改前置版本；并发登记通过版本行锁按事务提交顺序裁决。
 */
@Service
public class VersionService {

    private final VersionRepository versionRepository;
    private final IdempotencyService idempotency;

    public VersionService(VersionRepository versionRepository, IdempotencyService idempotency) {
        this.versionRepository = versionRepository;
        this.idempotency = idempotency;
    }

    public VersionView register(RegisterVersionRequest request) {
        String predecessor = request.predecessor();
        String fingerprint = String.join("|", "version.register", request.version(),
                predecessor == null ? "" : predecessor);
        return idempotency.execute(request.requestId(), "version.register", fingerprint, () -> {
            if (predecessor != null) {
                if (predecessor.equals(request.version())) {
                    throw ApiException.unprocessableEntity("VERSION_CHAIN_CYCLE",
                            "前置版本不能是版本自身: " + predecessor);
                }
                // 按版本号字典序加锁，避免并发登记互相等待形成死锁
                String first = request.version().compareTo(predecessor) < 0 ? request.version() : predecessor;
                String second = first.equals(request.version()) ? predecessor : request.version();
                versionRepository.findByVersionForUpdate(first);
                versionRepository.findByVersionForUpdate(second);
                versionRepository.findByVersion(predecessor)
                        .orElseThrow(() -> ApiException.unprocessableEntity("PREDECESSOR_NOT_FOUND",
                                "前置版本未登记: " + predecessor));
                ensureNoCycle(request.version(), predecessor);
            } else {
                versionRepository.findByVersionForUpdate(request.version());
            }
            if (versionRepository.findByVersion(request.version()).isPresent()) {
                versionRepository.updatePredecessor(request.version(), predecessor);
            } else {
                try {
                    versionRepository.insert(request.version(), predecessor);
                } catch (DuplicateKeyException e) {
                    throw ApiException.conflict("VERSION_REGISTER_CONFLICT",
                            "版本并发登记冲突，请重试: " + request.version());
                }
            }
            return new VersionView(request.version(), predecessor);
        }, VersionView.class);
    }

    /**
     * 版本链明细：从指定版本沿前置链到链起点。版本未登记返回 404。
     */
    public VersionChainResponse chain(String version) {
        FirmwareVersion start = versionRepository.findByVersion(version)
                .orElseThrow(() -> ApiException.notFound("VERSION_NOT_FOUND", "版本未登记: " + version));
        List<VersionView> nodes = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        FirmwareVersion current = start;
        while (current != null && visited.add(current.version())) {
            nodes.add(new VersionView(current.version(), current.predecessor()));
            current = current.predecessor() == null ? null
                    : versionRepository.findByVersion(current.predecessor()).orElse(null);
        }
        return new VersionChainResponse(version, nodes);
    }

    /**
     * 路径判定：目标版本前置链上存在设备尚未安装的中间版本时，返回下一个必须安装的版本；
     * 设备当前版本为目标版本、目标版本的直接前置，或目标版本无前置链时返回空（按既有规则处理）。
     * 设备版本不在链上时，下一个必须安装的版本为链起点。
     */
    public Optional<String> nextRequiredVersion(String deviceVersion, String targetVersion) {
        if (deviceVersion.equals(targetVersion)) {
            return Optional.empty();
        }
        Optional<FirmwareVersion> target = versionRepository.findByVersion(targetVersion);
        if (target.isEmpty() || target.get().predecessor() == null) {
            return Optional.empty();
        }
        List<String> missing = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String node = target.get().predecessor();
        while (node != null && !node.equals(deviceVersion)) {
            if (!visited.add(node)) {
                throw new IllegalStateException("版本链存在环: " + node);
            }
            missing.add(node);
            node = versionRepository.findByVersion(node).map(FirmwareVersion::predecessor).orElse(null);
        }
        if (missing.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(missing.get(missing.size() - 1));
    }

    /**
     * 环校验：从候选前置版本沿链向上，若回到待登记版本则形成环。
     */
    private void ensureNoCycle(String version, String predecessor) {
        Set<String> visited = new HashSet<>();
        String node = predecessor;
        while (node != null) {
            if (node.equals(version)) {
                throw ApiException.unprocessableEntity("VERSION_CHAIN_CYCLE",
                        "前置链形成环: " + version + " -> " + predecessor);
            }
            if (!visited.add(node)) {
                throw new IllegalStateException("版本链已存在环: " + node);
            }
            node = versionRepository.findByVersion(node).map(FirmwareVersion::predecessor).orElse(null);
        }
    }
}
