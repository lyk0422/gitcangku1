package com.example.starter.service.impl;

import com.example.starter.domain.ApiException;
import com.example.starter.repository.ArtifactDao;
import com.example.starter.repository.ArtifactDao.ArtifactRow;
import com.example.starter.repository.ArtifactDao.SnapshotRow;
import com.example.starter.repository.LockDao;
import com.example.starter.repository.LockDao.LockRow;
import com.example.starter.repository.RepositoryVersionDao;
import com.example.starter.resolver.DependencyResolver;
import com.example.starter.resolver.DependencyResolver.ArtifactVersion;
import com.example.starter.resolver.DependencyResolver.DependencyRange;
import com.example.starter.service.FingerprintHasher;
import com.example.starter.service.IdempotencyService;
import com.example.starter.service.LockService;
import com.example.starter.web.dto.CreateLockRequest;
import com.example.starter.web.dto.LockFileResponse;
import com.example.starter.web.dto.ResolvedItem;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 依赖锁定与历史锁文件查询服务。
 *
 * <p>创建锁定时在仓库版本行锁内核对 expectedRepositoryVersion 并读取一致快照；
 * 根必须存在且未撤回，版本不符返回409，无可行组合返回422且不落任何数据；
 * 成功后锁文件、明细与幂等记录同事务原子提交。历史查询只读不可变的已落库数据，
 * 制品后续撤回不影响已完成锁文件。</p>
 */
@Service
public class LockServiceImpl implements LockService {

    private final ArtifactDao artifactDao;
    private final LockDao lockDao;
    private final RepositoryVersionDao repositoryVersionDao;
    private final IdempotencyService idempotencyService;
    private final FingerprintHasher fingerprintHasher;

    public LockServiceImpl(ArtifactDao artifactDao, LockDao lockDao,
                           RepositoryVersionDao repositoryVersionDao,
                           IdempotencyService idempotencyService,
                           FingerprintHasher fingerprintHasher) {
        this.artifactDao = artifactDao;
        this.lockDao = lockDao;
        this.repositoryVersionDao = repositoryVersionDao;
        this.idempotencyService = idempotencyService;
        this.fingerprintHasher = fingerprintHasher;
    }

    @Override
    public LockFileResponse createLock(CreateLockRequest request) {
        LockFingerprint params = new LockFingerprint(
                request.rootName(), request.rootVersion(), request.expectedRepositoryVersion());
        String fingerprint = fingerprintHasher.hash("LOCK", params);
        IdempotencyService.WriteOutcome<LockFileResponse> outcome = idempotencyService.execute(
                request.requestId(), "LOCK", fingerprint, LockFileResponse.class,
                () -> doCreateLock(request));
        return outcome.body();
    }

    private IdempotencyService.WriteOutcome<LockFileResponse> doCreateLock(CreateLockRequest request) {
        // 已在幂等执行器内持有仓库版本行锁，登记/撤回无法并发提交
        long repositoryVersion = repositoryVersionDao.lockAndGet();
        if (repositoryVersion != request.expectedRepositoryVersion()) {
            throw new ApiException(409, "REPOSITORY_VERSION_MISMATCH",
                    "仓库版本已变化，请读取最新版本后重试");
        }

        ArtifactRow root = artifactDao.findOne(request.rootName(), request.rootVersion())
                .orElseThrow(() -> new ApiException(404, "ROOT_NOT_FOUND",
                        "根制品版本不存在"));
        if (root.withdrawn()) {
            throw new ApiException(409, "ROOT_WITHDRAWN",
                    "根制品版本已撤回，不能作为锁定根");
        }

        List<ArtifactVersion> snapshot = buildSnapshot(artifactDao.loadActiveSnapshot());
        Map<String, Integer> resolved = DependencyResolver.resolve(
                request.rootName(), request.rootVersion(), snapshot)
                .orElseThrow(() -> new ApiException(422, "NO_FEASIBLE_RESOLUTION",
                        "不存在满足全部依赖区间的未撤回版本组合"));

        long lockFileId = lockDao.insertLock(request.rootName(), request.rootVersion(),
                repositoryVersion, request.requestId());
        List<ResolvedItem> items = new ArrayList<>();
        // resolved 按名称字典序（解析器输出的 LinkedHashMap 顺序）
        for (Map.Entry<String, Integer> entry : resolved.entrySet()) {
            lockDao.insertItem(lockFileId, entry.getKey(), entry.getValue());
            items.add(new ResolvedItem(entry.getKey(), entry.getValue()));
        }
        Instant createdAt = lockDao.findById(lockFileId)
                .map(LockRow::createdAt)
                .orElseThrow(() -> new IllegalStateException("锁文件写入后无法读取"));
        return IdempotencyService.WriteOutcome.created(
                new LockFileResponse(lockFileId, request.rootName(), request.rootVersion(),
                        repositoryVersion, items, createdAt));
    }

    @Override
    public LockFileResponse getLock(long id) {
        LockRow lock = lockDao.findById(id)
                .orElseThrow(() -> new ApiException(404, "LOCK_NOT_FOUND", "锁文件不存在"));
        return toResponse(lock);
    }

    @Override
    public List<LockFileResponse> listLocks(String rootName) {
        return lockDao.findAll(rootName).stream()
                .map(this::toResponse)
                .toList();
    }

    private LockFileResponse toResponse(LockRow lock) {
        List<ResolvedItem> items = lockDao.findItems(lock.id()).stream()
                .map(item -> new ResolvedItem(item.artifactName(), item.artifactVersion()))
                .toList();
        return new LockFileResponse(lock.id(), lock.rootName(), lock.rootVersion(),
                lock.repositoryVersion(), items, lock.createdAt());
    }

    /**
     * 将 LEFT JOIN 拍平的快照行重组为制品版本聚合对象。
     */
    private List<ArtifactVersion> buildSnapshot(List<SnapshotRow> rows) {
        // key 按名称、版本排序，依赖收集到列表后按名称排序
        TreeMap<String, TreeMap<Integer, List<DependencyRange>>> grouped = new TreeMap<>();
        for (SnapshotRow row : rows) {
            List<DependencyRange> dependencies = grouped
                    .computeIfAbsent(row.name(), k -> new TreeMap<>())
                    .computeIfAbsent(row.version(), k -> new ArrayList<>());
            if (row.depName() != null) {
                dependencies.add(new DependencyRange(
                        row.depName(), row.minVersion(), row.maxVersion()));
            }
        }
        List<ArtifactVersion> result = new ArrayList<>();
        for (Map.Entry<String, TreeMap<Integer, List<DependencyRange>>> byName : grouped.entrySet()) {
            for (Map.Entry<Integer, List<DependencyRange>> byVersion : byName.getValue().entrySet()) {
                List<DependencyRange> dependencies = new ArrayList<>(byVersion.getValue());
                dependencies.sort((a, b) -> a.name().compareTo(b.name()));
                result.add(new ArtifactVersion(byName.getKey(), byVersion.getKey(),
                        List.copyOf(dependencies)));
            }
        }
        return result;
    }

    private record LockFingerprint(String rootName, int rootVersion,
                                   long expectedRepositoryVersion) {
    }
}
