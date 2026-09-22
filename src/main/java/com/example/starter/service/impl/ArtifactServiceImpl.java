package com.example.starter.service.impl;

import com.example.starter.domain.ApiException;
import com.example.starter.repository.ArtifactDao;
import com.example.starter.repository.ArtifactDao.ArtifactRow;
import com.example.starter.repository.RepositoryVersionDao;
import com.example.starter.service.ArtifactService;
import com.example.starter.service.FingerprintHasher;
import com.example.starter.service.IdempotencyService;
import com.example.starter.web.dto.ArtifactResponse;
import com.example.starter.web.dto.DependencyRequest;
import com.example.starter.web.dto.DependencyView;
import com.example.starter.web.dto.RegisterArtifactRequest;
import com.example.starter.web.dto.WithdrawRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 制品登记与撤回服务：依赖创建后不可改；登记/撤回均使仓库版本加一。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;
    private static final int MAX_DEPENDENCIES = 10;

    private final ArtifactDao artifactDao;
    private final RepositoryVersionDao repositoryVersionDao;
    private final IdempotencyService idempotencyService;
    private final FingerprintHasher fingerprintHasher;

    public ArtifactServiceImpl(ArtifactDao artifactDao,
                               RepositoryVersionDao repositoryVersionDao,
                               IdempotencyService idempotencyService,
                               FingerprintHasher fingerprintHasher) {
        this.artifactDao = artifactDao;
        this.repositoryVersionDao = repositoryVersionDao;
        this.idempotencyService = idempotencyService;
        this.fingerprintHasher = fingerprintHasher;
    }

    @Override
    public ArtifactResponse register(RegisterArtifactRequest request) {
        List<DependencyRequest> dependencies = request.dependencies() == null
                ? List.of()
                : new ArrayList<>(request.dependencies());
        validateDependencies(dependencies);
        // 依赖按名称排序后参与指纹与持久化展示，语义相同但顺序不同的请求视为同参
        dependencies.sort((a, b) -> a.name().compareTo(b.name()));

        RegisterFingerprint params = new RegisterFingerprint(
                request.name(), request.version(),
                dependencies.stream()
                        .map(d -> new DependencyView(d.name(), d.minVersion(), d.maxVersion()))
                        .toList());
        String fingerprint = fingerprintHasher.hash("REGISTER", params);

        IdempotencyService.WriteOutcome<ArtifactResponse> outcome = idempotencyService.execute(
                request.requestId(), "REGISTER", fingerprint, ArtifactResponse.class,
                () -> doRegister(request.name(), request.version(), dependencies));
        return outcome.body();
    }

    protected IdempotencyService.WriteOutcome<ArtifactResponse> doRegister(
            String name, int version, List<DependencyRequest> dependencies) {
        if (artifactDao.existsArtifact(name, version)) {
            throw new ApiException(409, "ARTIFACT_ALREADY_EXISTS",
                    "同名同版本制品已存在，创建后依赖不可改");
        }
        int existingVersions = artifactDao.countVersions(name);
        if (existingVersions >= MAX_VERSIONS_PER_NAME) {
            throw new ApiException(422, "VERSION_LIMIT_EXCEEDED",
                    "每个制品名称最多登记" + MAX_VERSIONS_PER_NAME + "个版本（含撤回）");
        }
        boolean nameIsNew = existingVersions == 0;
        if (nameIsNew && artifactDao.countDistinctNames() >= MAX_NAMES) {
            throw new ApiException(422, "NAME_LIMIT_EXCEEDED",
                    "仓库最多包含" + MAX_NAMES + "个制品名称");
        }

        long artifactId = artifactDao.insertArtifact(name, version);
        for (DependencyRequest dependency : dependencies) {
            artifactDao.insertDependency(artifactId, dependency.name(),
                    dependency.minVersion(), dependency.maxVersion());
        }
        long repositoryVersion = repositoryVersionDao.lockAndIncrement();

        ArtifactResponse response = new ArtifactResponse(name, version,
                dependencies.stream()
                        .map(d -> new DependencyView(d.name(), d.minVersion(), d.maxVersion()))
                        .toList());
        return IdempotencyService.WriteOutcome.created(response);
    }

    @Override
    public ArtifactResponse withdraw(String name, int version, WithdrawRequest request) {
        String fingerprint = fingerprintHasher.hash("WITHDRAW", new WithdrawFingerprint(name, version));
        IdempotencyService.WriteOutcome<ArtifactResponse> outcome = idempotencyService.execute(
                request.requestId(), "WITHDRAW", fingerprint, ArtifactResponse.class,
                () -> doWithdraw(name, version));
        return outcome.body();
    }

    protected IdempotencyService.WriteOutcome<ArtifactResponse> doWithdraw(String name, int version) {
        ArtifactRow artifact = artifactDao.findOne(name, version)
                .orElseThrow(() -> new ApiException(404, "ARTIFACT_NOT_FOUND",
                        "指定制品版本不存在"));
        if (artifact.withdrawn()) {
            throw new ApiException(409, "ARTIFACT_ALREADY_WITHDRAWN",
                    "指定制品版本已撤回，撤回不可重复执行");
        }
        artifactDao.markWithdrawn(name, version);
        repositoryVersionDao.lockAndIncrement();
        List<DependencyView> dependencies = artifactDao.findDependencies(artifact.id()).stream()
                .map(d -> new DependencyView(d.depName(), d.minVersion(), d.maxVersion()))
                .toList();
        return IdempotencyService.WriteOutcome.ok(
                new ArtifactResponse(name, version, dependencies));
    }

    private void validateDependencies(List<DependencyRequest> dependencies) {
        if (dependencies.size() > MAX_DEPENDENCIES) {
            throw new ApiException(400, "TOO_MANY_DEPENDENCIES",
                    "每个版本最多声明" + MAX_DEPENDENCIES + "条依赖");
        }
        Set<String> names = new HashSet<>();
        for (DependencyRequest dependency : dependencies) {
            if (dependency.minVersion() > dependency.maxVersion()) {
                throw new ApiException(400, "INVALID_DEPENDENCY_RANGE",
                        "依赖最低版本不能高于最高版本：" + dependency.name());
            }
            if (!names.add(dependency.name())) {
                throw new ApiException(400, "DUPLICATE_DEPENDENCY_NAME",
                        "同一制品内依赖名称必须唯一：" + dependency.name());
            }
        }
    }

    private record RegisterFingerprint(String name, int version, List<DependencyView> dependencies) {
    }

    private record WithdrawFingerprint(String name, int version) {
    }
}
