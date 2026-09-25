package com.example.starter.firmware.service;

import com.example.starter.firmware.api.RegisterVersionRequest;
import com.example.starter.firmware.api.VersionChainView;
import com.example.starter.firmware.domain.FirmwareVersion;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.VersionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 固件版本链：登记（校验前置已存在且不形成环，违反返回422）与链明细查询。
 * 版本与其前置链接登记后不可修改，因此从任一版本回溯到的链在登记完成后稳定不变。
 */
@Service
public class VersionService {

    private final VersionRepository versionRepository;
    private final IdempotencyService idempotency;

    public VersionService(VersionRepository versionRepository, IdempotencyService idempotency) {
        this.versionRepository = versionRepository;
        this.idempotency = idempotency;
    }

    /**
     * 登记版本及其直接前置版本（至多一个）。前置版本必须已登记且不形成环，违反返回 422；
     * 版本重复登记返回 409。无前置版本的版本视为链起点。
     */
    public VersionChainView register(RegisterVersionRequest request) {
        String predecessor = normalize(request.predecessorVersion());
        String fingerprint = String.join("|", "version.register", request.version(),
                predecessor == null ? "" : predecessor);
        return idempotency.execute(request.requestId(), "version.register", fingerprint, () -> {
            if (versionRepository.find(request.version()).isPresent()) {
                throw ApiException.conflict("VERSION_EXISTS", "版本已登记: " + request.version());
            }
            if (predecessor != null) {
                if (predecessor.equals(request.version())) {
                    throw ApiException.unprocessable("VERSION_CHAIN_CYCLE",
                            "前置版本不能是版本自身: " + predecessor);
                }
                if (versionRepository.find(predecessor).isEmpty()) {
                    throw ApiException.unprocessable("PREDECESSOR_NOT_FOUND",
                            "前置版本未登记: " + predecessor);
                }
                if (chainFrom(predecessor).contains(request.version())) {
                    throw ApiException.unprocessable("VERSION_CHAIN_CYCLE",
                            "登记后前置链将形成环: " + request.version() + " -> " + predecessor);
                }
            }
            try {
                versionRepository.insert(request.version(), predecessor);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("VERSION_EXISTS", "版本已登记: " + request.version());
            }
            return new VersionChainView(request.version(), predecessor, chainFrom(request.version()));
        }, VersionChainView.class);
    }

    /**
     * 版本链明细：从该版本自身沿前置链接回溯到链起点。
     */
    public VersionChainView chain(String version) {
        FirmwareVersion row = versionRepository.find(version)
                .orElseThrow(() -> ApiException.notFound("VERSION_NOT_FOUND", "版本未登记: " + version));
        return new VersionChainView(row.version(), row.predecessorVersion(), chainFrom(version));
    }

    /**
     * 从指定版本回溯前置链（含自身）。版本未登记时返回空链；带访问集合防御意外成环。
     */
    public List<String> chainFrom(String version) {
        List<String> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String current = version;
        while (current != null && visited.add(current)) {
            var row = versionRepository.find(current);
            if (row.isEmpty()) {
                break;
            }
            chain.add(current);
            current = row.get().predecessorVersion();
        }
        return chain;
    }

    private static String normalize(String predecessor) {
        return predecessor == null || predecessor.isBlank() ? null : predecessor;
    }
}
