package com.example.starter.artifact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.starter.artifact.dto.ArtifactResponse;
import com.example.starter.artifact.dto.ArtifactView;
import com.example.starter.artifact.dto.DependencyDto;
import com.example.starter.artifact.dto.RegisterArtifactRequest;
import com.example.starter.artifact.dto.RepositoryView;
import com.example.starter.artifact.dto.RetractResponse;
import com.example.starter.artifact.repository.ArtifactRepository;
import com.example.starter.artifact.repository.ArtifactRow;
import com.example.starter.artifact.repository.DependencyRow;
import com.example.starter.artifact.repository.MetaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 制品登记与撤回业务。所有方法须在 {@link IdempotentExecutor} 开启的、
 * 已持有仓库元信息行锁的事务内调用。
 */
@Service
public class ArtifactService {

    /** 仓库最多容纳的制品名称数量。 */
    static final int MAX_NAMES = 20;

    /** 每个名称最多容纳的版本数量（含已撤回）。 */
    static final int MAX_VERSIONS_PER_NAME = 5;

    private final ArtifactRepository artifactRepository;
    private final MetaRepository metaRepository;

    public ArtifactService(ArtifactRepository artifactRepository, MetaRepository metaRepository) {
        this.artifactRepository = artifactRepository;
        this.metaRepository = metaRepository;
    }

    /**
     * 登记制品版本。name + version 联合唯一；仓库最多 20 个名称、
     * 每名称最多 5 个版本（含撤回）；成功后仓库版本加一。
     *
     * @param request 登记请求（已通过参数校验）
     * @return 登记结果
     */
    public ArtifactResponse register(RegisterArtifactRequest request) {
        List<DependencyDto> dependencies = request.dependencies() == null
                ? List.of() : request.dependencies();
        validateDependencies(dependencies);
        if (artifactRepository.findByNameAndVersion(request.name(), request.version()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "制品版本已存在: " + request.name() + ":" + request.version());
        }
        if (!artifactRepository.existsName(request.name())
                && artifactRepository.countDistinctNames() >= MAX_NAMES) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "仓库名称数量超限，最多 " + MAX_NAMES + " 个");
        }
        if (artifactRepository.countVersions(request.name()) >= MAX_VERSIONS_PER_NAME) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "制品 " + request.name() + " 版本数量超限，最多 " + MAX_VERSIONS_PER_NAME + " 个");
        }
        long id = artifactRepository.insert(request.name(), request.version());
        if (!dependencies.isEmpty()) {
            artifactRepository.insertDependencies(id, dependencies);
        }
        long repositoryVersion = metaRepository.increment();
        return new ArtifactResponse(id, request.name(), request.version(), false, repositoryVersion);
    }

    /**
     * 撤回制品版本：仅标记不删除，成功后仓库版本加一。
     *
     * @param name    制品名称
     * @param version 制品版本
     * @return 撤回结果
     */
    public RetractResponse retract(String name, int version) {
        ArtifactRow row = artifactRepository.findByNameAndVersion(name, version)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "制品不存在: " + name + ":" + version));
        if (row.retracted()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "制品已撤回: " + name + ":" + version);
        }
        artifactRepository.markRetracted(row.id());
        long repositoryVersion = metaRepository.increment();
        return new RetractResponse(name, version, true, repositoryVersion);
    }

    private void validateDependencies(List<DependencyDto> dependencies) {
        Set<String> names = new HashSet<>();
        for (DependencyDto dep : dependencies) {
            if (!names.add(dep.name())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "依赖名称重复: " + dep.name());
            }
            if (dep.minVersion() > dep.maxVersion()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "依赖版本区间无效: " + dep.name()
                                + " [" + dep.minVersion() + ", " + dep.maxVersion() + "]");
            }
        }
    }

    /**
     * 查询仓库整体视图：当前仓库版本号与全部制品版本（含已撤回）。
     */
    @Transactional(readOnly = true)
    public RepositoryView repositoryView() {
        Map<Long, List<DependencyDto>> depsByArtifact = new HashMap<>();
        for (DependencyRow dep : artifactRepository.findAllDependencies()) {
            depsByArtifact.computeIfAbsent(dep.artifactId(), k -> new ArrayList<>())
                    .add(new DependencyDto(dep.depName(), dep.minVersion(), dep.maxVersion()));
        }
        List<ArtifactView> artifacts = artifactRepository.findAll().stream()
                .map(row -> new ArtifactView(row.id(), row.name(), row.version(), row.retracted(),
                        depsByArtifact.getOrDefault(row.id(), List.of())))
                .toList();
        return new RepositoryView(metaRepository.currentVersion(), artifacts);
    }
}
