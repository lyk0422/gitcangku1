package com.example.starter.firmware.service;

import com.example.starter.firmware.api.ApproveRestoreRequest;
import com.example.starter.firmware.api.FreezeStatusResponse;
import com.example.starter.firmware.api.FreezeVersionRequest;
import com.example.starter.firmware.api.FreezeView;
import com.example.starter.firmware.api.RestoreFreezeRequest;
import com.example.starter.firmware.api.RestoreView;
import com.example.starter.firmware.domain.FreezeRestore;
import com.example.starter.firmware.domain.FreezeStatus;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RestoreStatus;
import com.example.starter.firmware.domain.VersionFreeze;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.FreezeRepository;
import com.example.starter.firmware.repo.RestoreRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 目标固件版本正式发布冻结：冻结创建（创建人/原因/UTC 时刻）、恢复申请与异于冻结人的审批、
 * 当前冻结与审计查询。
 *
 * <p>冻结与任务创建（设备拉取）的竞争以提交顺序决定结果：冻结事务先以行锁锁定该版本所有未终结
 * 发布单（与拉取事务持有的发布单行锁互斥），再写入冻结；冻结先提交则后到拉取读到冻结返回 409，
 * 拉取先提交则其任务已落库，冻结随后生效。冻结失败（重复冻结、审批被拒）整体回滚，不占幂等键。
 */
@Service
public class FreezeService {

    private final FreezeRepository freezeRepository;
    private final RestoreRepository restoreRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public FreezeService(FreezeRepository freezeRepository, RestoreRepository restoreRepository,
                         IdempotencyService idempotency, Clock clock) {
        this.freezeRepository = freezeRepository;
        this.restoreRepository = restoreRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 创建版本冻结：同版本已有生效冻结返回 409。先锁该版本未终结发布单行，再写冻结，形成提交顺序屏障。
     */
    public FreezeView freeze(FreezeVersionRequest request) {
        String fingerprint = String.join("|", "freeze.create", request.version(),
                request.operator(), request.reason());
        return idempotency.execute(request.requestId(), "freeze.create", fingerprint, () -> {
            freezeRepository.lockOpenReleasesByToVersion(request.version());
            if (freezeRepository.findActiveByVersion(request.version()).isPresent()) {
                throw ApiException.conflict("VERSION_FROZEN", "版本已处于正式发布冻结中: " + request.version());
            }
            long id;
            try {
                id = freezeRepository.insert(request.version(), request.operator(), request.reason(),
                        Instant.now(clock).toString());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("VERSION_FROZEN", "版本已处于正式发布冻结中: " + request.version());
            }
            return FreezeView.of(freezeRepository.findById(id)
                    .orElseThrow(() -> new IllegalStateException("冻结创建后读取失败")));
        }, FreezeView.class);
    }

    /**
     * 恢复申请：仅 FROZEN 冻结可申请；同一冻结已有待审批申请返回 422（不允许挂起多份）。
     */
    public RestoreView requestRestore(String version, RestoreFreezeRequest request) {
        String fingerprint = String.join("|", "freeze.restore", version, request.operator(), request.reason());
        return idempotency.execute(request.requestId(), "freeze.restore", fingerprint, () -> {
            VersionFreeze freeze = freezeRepository.findActiveByVersion(version)
                    .orElseThrow(() -> ApiException.conflict("FREEZE_NOT_ACTIVE",
                            "版本无生效中的冻结，不能申请恢复: " + version));
            VersionFreeze locked = freezeRepository.findByIdForUpdate(freeze.id())
                    .orElseThrow(() -> ApiException.notFound("FREEZE_NOT_FOUND", "冻结记录不存在"));
            if (locked.status() != FreezeStatus.FROZEN) {
                throw ApiException.conflict("FREEZE_NOT_ACTIVE",
                        "冻结已恢复，不能重复申请: " + version);
            }
            if (restoreRepository.existsPendingByFreeze(locked.id())) {
                throw ApiException.unprocessable("RESTORE_ALREADY_PENDING",
                        "该冻结已有待审批的恢复申请，请勿重复提交");
            }
            long id = restoreRepository.insertPending(locked.id(), request.operator(), request.reason(),
                    Instant.now(clock).toString());
            return RestoreView.of(restoreRepository.findById(id)
                    .orElseThrow(() -> new IllegalStateException("恢复申请创建后读取失败")));
        }, RestoreView.class);
    }

    /**
     * 审批恢复：审批人必须不同于冻结创建人，同人审批 422；冻结/申请已不存在或已终结按 404/409 处理。
     * 审批通过与冻结解除在同一事务原子提交，失败回滚不改冻结状态。
     */
    public RestoreView approve(String version, long restoreId, ApproveRestoreRequest request) {
        String fingerprint = String.join("|", "freeze.approve", version, String.valueOf(restoreId),
                request.approver());
        return idempotency.execute(request.requestId(), "freeze.approve", fingerprint, () -> {
            FreezeRestore restore = restoreRepository.findById(restoreId)
                    .orElseThrow(() -> ApiException.notFound("RESTORE_NOT_FOUND",
                            "恢复申请不存在: " + restoreId));
            VersionFreeze freeze = freezeRepository.findByIdForUpdate(restore.freezeId())
                    .orElseThrow(() -> ApiException.notFound("FREEZE_NOT_FOUND", "冻结记录不存在"));
            if (!freeze.versionKey().equals(version)) {
                throw ApiException.conflict("FREEZE_VERSION_MISMATCH",
                        "恢复申请不属于版本: " + version);
            }
            FreezeRestore lockedRestore = restoreRepository.findByIdForUpdate(restoreId)
                    .orElseThrow(() -> ApiException.notFound("RESTORE_NOT_FOUND",
                            "恢复申请不存在: " + restoreId));
            if (lockedRestore.status() == RestoreStatus.APPROVED) {
                return RestoreView.of(lockedRestore);
            }
            if (freeze.status() != FreezeStatus.FROZEN) {
                throw ApiException.conflict("FREEZE_NOT_ACTIVE", "冻结已恢复，申请不再可审批: " + version);
            }
            if (request.approver().equals(freeze.frozenBy())) {
                throw ApiException.unprocessable("RESTORE_APPROVER_CONFLICT",
                        "恢复审批人必须不同于冻结创建人: " + freeze.frozenBy());
            }
            String reviewedAtUtc = Instant.now(clock).toString();
            if (restoreRepository.approve(restoreId, request.approver(), reviewedAtUtc) != 1) {
                throw ApiException.conflict("RESTORE_NOT_PENDING", "恢复申请已被处理，请刷新后重试");
            }
            if (freezeRepository.markResumed(freeze.id(), request.approver(), reviewedAtUtc) != 1) {
                throw ApiException.conflict("FREEZE_NOT_ACTIVE", "冻结已被其他审批解除: " + version);
            }
            return RestoreView.of(restoreRepository.findById(restoreId)
                    .orElseThrow(() -> new IllegalStateException("审批后恢复申请读取失败")));
        }, RestoreView.class);
    }

    /**
     * 查询版本当前冻结（最近一条区间，从未冻结为 null）及其全部恢复申请/审批审计，按创建顺序排列。
     */
    public FreezeStatusResponse status(String version) {
        List<VersionFreeze> freezes = freezeRepository.findByVersion(version);
        if (freezes.isEmpty()) {
            return new FreezeStatusResponse(version, null, List.of());
        }
        VersionFreeze latest = freezes.get(freezes.size() - 1);
        List<RestoreView> restores = restoreRepository.findByFreeze(latest.id()).stream()
                .map(RestoreView::of).toList();
        return new FreezeStatusResponse(version, FreezeView.of(latest), restores);
    }

    /**
     * 调用方已持有发布单行锁：该发布单目标版本处于冻结生效中时抛出 409，阻止新建正式任务。
     * 已有任务回执与只读查询不经过此检查。
     */
    public void verifyNewTaskAllowed(ReleaseOrder order) {
        if (freezeRepository.findActiveByVersion(order.toVersion()).isPresent()) {
            throw ApiException.conflict("RELEASE_VERSION_FROZEN",
                    "目标版本 " + order.toVersion() + " 已冻结正式发布，不能新建投放任务");
        }
    }
}
