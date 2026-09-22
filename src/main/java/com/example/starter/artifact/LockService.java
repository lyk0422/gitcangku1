package com.example.starter.artifact;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.example.starter.artifact.dto.LockEntryDto;
import com.example.starter.artifact.dto.LockFileResponse;
import com.example.starter.artifact.dto.LockFileSummary;
import com.example.starter.artifact.dto.LockRequest;
import com.example.starter.artifact.repository.ArtifactRepository;
import com.example.starter.artifact.repository.ArtifactRow;
import com.example.starter.artifact.repository.DependencyRow;
import com.example.starter.artifact.repository.LockFileRow;
import com.example.starter.artifact.repository.LockRepository;
import com.example.starter.artifact.repository.MetaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 依赖锁定业务。解析规则：根版本固定；其余名称按字典序选择下一个尚未解析的
 * 依赖，候选版本从高到低尝试，遇冲突回退，取首个完整可行结果；全部组合均
 * 不可行才返回 422，且不保存半成品。
 */
@Service
public class LockService {

    private final ArtifactRepository artifactRepository;
    private final LockRepository lockRepository;
    private final MetaRepository metaRepository;

    public LockService(ArtifactRepository artifactRepository, LockRepository lockRepository,
                       MetaRepository metaRepository) {
        this.artifactRepository = artifactRepository;
        this.lockRepository = lockRepository;
        this.metaRepository = metaRepository;
    }

    /**
     * 执行锁定。须在 {@link IdempotentExecutor} 开启的、已持有仓库元信息行锁的
     * 事务内调用，保证与并发的登记/撤回看到一致的仓库状态。
     *
     * @param request 锁定请求（已通过参数校验）
     * @return 锁定结果，条目按名称升序
     */
    public LockFileResponse lock(LockRequest request) {
        long repositoryVersion = metaRepository.currentVersion();
        if (repositoryVersion != request.expectedRepositoryVersion()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "仓库版本不匹配: 期望 " + request.expectedRepositoryVersion()
                            + "，实际 " + repositoryVersion);
        }
        ArtifactRow root = artifactRepository
                .findByNameAndVersion(request.rootName(), request.rootVersion())
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "根制品不存在: " + request.rootName() + ":" + request.rootVersion()));
        if (root.retracted()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "根制品已撤回: " + request.rootName() + ":" + request.rootVersion());
        }

        List<ArtifactRow> allArtifacts = artifactRepository.findAll();
        Map<Long, List<DependencyRow>> depsByArtifact = new HashMap<>();
        for (DependencyRow dep : artifactRepository.findAllDependencies()) {
            depsByArtifact.computeIfAbsent(dep.artifactId(), k -> new ArrayList<>()).add(dep);
        }

        Map<String, Integer> solution = resolve(root, allArtifacts, depsByArtifact);
        List<LockEntryDto> entries = solution.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new LockEntryDto(e.getKey(), e.getValue()))
                .toList();

        long lockFileId = lockRepository.insertLockFile(
                request.requestId(), root.name(), root.version(), repositoryVersion);
        lockRepository.insertEntries(lockFileId, entries);
        return new LockFileResponse(lockFileId, root.name(), root.version(),
                repositoryVersion, entries);
    }

    /**
     * 查询全部历史锁文件，按 id 升序。
     */
    @Transactional(readOnly = true)
    public List<LockFileSummary> listLocks() {
        return lockRepository.findAllLocks().stream()
                .map(row -> new LockFileSummary(row.id(), row.rootName(), row.rootVersion(),
                        row.repoVersion(), row.createdAt()))
                .toList();
    }

    /**
     * 按 id 查询锁文件详情，条目按名称升序。锁文件在制品后来撤回后仍可查询且不被改写。
     */
    @Transactional(readOnly = true)
    public LockFileResponse getLock(long lockFileId) {
        LockFileRow row = lockRepository.findLock(lockFileId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "锁文件不存在: " + lockFileId));
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repoVersion(), lockRepository.findEntries(row.id()));
    }

    /**
     * 带回退的依赖解析。返回名称到精确版本的映射；不可行时抛 422。
     */
    private Map<String, Integer> resolve(ArtifactRow root, List<ArtifactRow> allArtifacts,
                                         Map<Long, List<DependencyRow>> depsByArtifact) {
        Map<String, List<ArtifactRow>> candidatesByName = new HashMap<>();
        for (ArtifactRow row : allArtifacts) {
            if (!row.retracted()) {
                candidatesByName.computeIfAbsent(row.name(), k -> new ArrayList<>()).add(row);
            }
        }
        candidatesByName.values().forEach(list ->
                list.sort(Comparator.comparingInt(ArtifactRow::version).reversed()));

        Map<String, Integer> selected = new LinkedHashMap<>();
        selected.put(root.name(), root.version());
        Map<String, Interval> constraints = new TreeMap<>();
        if (applyDeps(constraints, depsByArtifact.getOrDefault(root.id(), List.of()),
                selected) == null) {
            throw infeasible();
        }
        if (!search(candidatesByName, depsByArtifact, selected, constraints)) {
            throw infeasible();
        }
        return selected;
    }

    private boolean search(Map<String, List<ArtifactRow>> candidatesByName,
                           Map<Long, List<DependencyRow>> depsByArtifact,
                           Map<String, Integer> selected, Map<String, Interval> constraints) {
        String next = null;
        for (Map.Entry<String, Interval> entry : constraints.entrySet()) {
            if (!selected.containsKey(entry.getKey())) {
                next = entry.getKey();
                break;
            }
        }
        if (next == null) {
            return true;
        }
        Interval interval = constraints.get(next);
        for (ArtifactRow candidate : candidatesByName.getOrDefault(next, List.of())) {
            if (candidate.version() < interval.min() || candidate.version() > interval.max()) {
                continue;
            }
            selected.put(next, candidate.version());
            List<Map.Entry<String, Interval>> undo = applyDeps(constraints,
                    depsByArtifact.getOrDefault(candidate.id(), List.of()), selected);
            if (undo != null) {
                if (search(candidatesByName, depsByArtifact, selected, constraints)) {
                    return true;
                }
                rollback(constraints, undo);
            }
            selected.remove(next);
        }
        return false;
    }

    /**
     * 把一组依赖声明合并进约束表：已选定的名称校验区间包含，未选定的名称取交集。
     * 成功返回撤销日志；任一处冲突则回滚本次改动并返回 null。
     */
    private List<Map.Entry<String, Interval>> applyDeps(Map<String, Interval> constraints,
                                                        List<DependencyRow> deps,
                                                        Map<String, Integer> selected) {
        List<Map.Entry<String, Interval>> undo = new ArrayList<>();
        for (DependencyRow dep : deps) {
            Integer selectedVersion = selected.get(dep.depName());
            if (selectedVersion != null) {
                if (selectedVersion < dep.minVersion() || selectedVersion > dep.maxVersion()) {
                    rollback(constraints, undo);
                    return null;
                }
                continue;
            }
            Interval old = constraints.get(dep.depName());
            Interval merged = old == null
                    ? new Interval(dep.minVersion(), dep.maxVersion())
                    : old.intersect(dep.minVersion(), dep.maxVersion());
            if (merged == null) {
                rollback(constraints, undo);
                return null;
            }
            undo.add(new AbstractMap.SimpleImmutableEntry<>(dep.depName(), old));
            constraints.put(dep.depName(), merged);
        }
        return undo;
    }

    private void rollback(Map<String, Interval> constraints,
                          List<Map.Entry<String, Interval>> undo) {
        for (int i = undo.size() - 1; i >= 0; i--) {
            Map.Entry<String, Interval> entry = undo.get(i);
            if (entry.getValue() == null) {
                constraints.remove(entry.getKey());
            } else {
                constraints.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private ApiException infeasible() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "依赖约束不可满足，无可行的版本组合");
    }

    /**
     * 闭区间版本约束。
     *
     * @param min 最低版本（含）
     * @param max 最高版本（含）
     */
    private record Interval(int min, int max) {

        Interval intersect(int otherMin, int otherMax) {
            int newMin = Math.max(min, otherMin);
            int newMax = Math.min(max, otherMax);
            return newMin <= newMax ? new Interval(newMin, newMax) : null;
        }
    }
}
