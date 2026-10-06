package com.example.starter.firmware.service;

import com.example.starter.firmware.api.ActivateGroupRequest;
import com.example.starter.firmware.api.CancelGroupRequest;
import com.example.starter.firmware.api.CreateGroupReleaseRequest;
import com.example.starter.firmware.api.GroupMemberView;
import com.example.starter.firmware.api.GroupReleaseView;
import com.example.starter.firmware.api.PrepareReceiptRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.GroupMember;
import com.example.starter.firmware.domain.GroupMemberStatus;
import com.example.starter.firmware.domain.GroupRelease;
import com.example.starter.firmware.domain.GroupReleaseStatus;
import com.example.starter.firmware.domain.PrepareResult;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.GroupMemberRepository;
import com.example.starter.firmware.repo.GroupReleaseRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/**
 * 固定设备组预备屏障：建组（2~8台同型号）、PREPARE 回执（暂存不切版本，任一 FAILED 整组 ABORTED）、
 * 全部当前代次齐备后 ACTIVATE（原子生成同一组激活代次并更新全组版本）、激活前 CANCEL 释放占用。
 * 准备、取消与激活共用组行锁，竞争只产生一个完整终态；未齐备激活返回 422 且不占请求键。
 */
@Service
public class GroupReleaseService {

    private final GroupReleaseRepository groupReleaseRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final DeviceRepository deviceRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public GroupReleaseService(GroupReleaseRepository groupReleaseRepository,
                               GroupMemberRepository groupMemberRepository,
                               DeviceRepository deviceRepository,
                               IdempotencyService idempotency, Clock clock) {
        this.groupReleaseRepository = groupReleaseRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.deviceRepository = deviceRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public GroupReleaseView create(CreateGroupReleaseRequest request) {
        List<String> deviceIds = request.deviceIds();
        if (new HashSet<>(deviceIds).size() != deviceIds.size()) {
            throw ApiException.badRequest("DUPLICATE_DEVICE", "组内设备重复");
        }
        String fingerprint = idempotency.structuredFingerprint("group.create", request.operator(),
                request.model(), request.toVersion(), deviceIds);
        return idempotency.execute(request.requestId(), "group.create", fingerprint, () -> {
            for (String deviceId : deviceIds) {
                Device device = deviceRepository.findById(deviceId)
                        .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
                if (!device.model().equals(request.model())) {
                    throw ApiException.badRequest("MODEL_MISMATCH",
                            "设备 " + deviceId + " 型号为 " + device.model() + "，与组型号 " + request.model() + " 不一致");
                }
            }
            long groupId = groupReleaseRepository.insert(request.model(), request.toVersion());
            try {
                for (String deviceId : deviceIds) {
                    groupMemberRepository.insert(groupId, deviceId, 1);
                }
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_IN_ACTIVE_GROUP", "设备已属于另一活动组发布");
            }
            return view(groupId);
        }, GroupReleaseView.class);
    }

    /**
     * PREPARE 回执：PREPARED 仅暂存不改设备版本；FAILED 使整组 ABORTED 并记录失败成员，
     * 已暂存成员也不切版本。旧代次回执（代次不匹配或组已终结）不影响当前组。
     */
    public GroupReleaseView prepareReceipt(long groupId, String deviceId, PrepareReceiptRequest request) {
        String fingerprint = idempotency.structuredFingerprint("group.prepare", request.operator(),
                groupId, deviceId, request.expectedPrepareGeneration(), request.result().name());
        return idempotency.execute(request.requestId(), "group.prepare", fingerprint, () -> {
            GroupRelease group = lockGroup(groupId);
            if (group.status() != GroupReleaseStatus.PREPARING) {
                throw ApiException.conflict("GROUP_NOT_PREPARING",
                        "组发布单状态为 " + group.status() + "，不再受理准备回执");
            }
            if (group.prepareGeneration() != request.expectedPrepareGeneration()) {
                throw ApiException.conflict("PREPARE_GENERATION_MISMATCH",
                        "准备代次不匹配，当前代次: " + group.prepareGeneration());
            }
            GroupMember member = groupMemberRepository.findByGroupAndDevice(groupId, deviceId)
                    .orElseThrow(() -> ApiException.notFound("GROUP_MEMBER_NOT_FOUND",
                            "设备 " + deviceId + " 不属于组发布单 " + groupId));
            switch (member.status()) {
                case PENDING -> {
                    if (request.result() == PrepareResult.PREPARED) {
                        groupMemberRepository.markPrepared(member.id());
                    } else {
                        groupMemberRepository.markFailed(member.id());
                        groupReleaseRepository.markAborted(groupId, deviceId);
                        groupMemberRepository.releaseOccupancyByGroup(groupId);
                    }
                }
                case PREPARED, FAILED -> {
                    boolean sameResult = (member.status() == GroupMemberStatus.PREPARED)
                            == (request.result() == PrepareResult.PREPARED);
                    if (!sameResult) {
                        throw ApiException.conflict("RECEIPT_RESULT_CONFLICT",
                                "成员已终结为 " + member.status() + "，不能改为 " + request.result());
                    }
                }
                default -> throw ApiException.conflict("GROUP_MEMBER_TERMINAL",
                        "成员状态为 " + member.status() + "，不再受理准备回执");
            }
            return view(groupId);
        }, GroupReleaseView.class);
    }

    /**
     * 激活：全部当前代次成员 PREPARED 才允许；未齐备 422 且不占请求键，齐备后可同键重试。
     * 成功时原子生成同一组激活代次、全组版本统一切换并释放占用；激活后不允许取消。
     */
    public GroupReleaseView activate(long groupId, ActivateGroupRequest request) {
        String fingerprint = idempotency.structuredFingerprint("group.activate", request.operator(),
                groupId, request.expectedVersion());
        return idempotency.execute(request.requestId(), "group.activate", fingerprint, () -> {
            GroupRelease group = lockGroup(groupId);
            rejectIfTerminal(group);
            if (group.version() != request.expectedVersion()) {
                throw ApiException.conflict("GROUP_VERSION_CONFLICT",
                        "expectedVersion 与当前版本不一致: " + group.version());
            }
            List<GroupMember> members = groupMemberRepository.findByGroup(groupId);
            boolean allPrepared = members.stream()
                    .allMatch(m -> m.status() == GroupMemberStatus.PREPARED
                            && m.prepareGeneration() == group.prepareGeneration());
            if (!allPrepared) {
                throw ApiException.unprocessableEntity("GROUP_NOT_READY", "组内成员未全部准备齐备，不能激活");
            }
            int activationGeneration = group.prepareGeneration();
            groupReleaseRepository.activate(groupId, request.expectedVersion(), activationGeneration,
                    Instant.now(clock).toString());
            groupMemberRepository.activateByGroup(groupId, activationGeneration);
            for (GroupMember member : members) {
                deviceRepository.updateCurrentVersion(member.deviceId(), group.toVersion());
            }
            groupMemberRepository.releaseOccupancyByGroup(groupId);
            return view(groupId);
        }, GroupReleaseView.class);
    }

    /**
     * 取消：仅 PREPARING 可取消，未终结成员转 CANCELLED（不计失败）并释放组占用；
     * 已取消幂等返回；激活后不允许取消。
     */
    public GroupReleaseView cancel(long groupId, CancelGroupRequest request) {
        String fingerprint = idempotency.structuredFingerprint("group.cancel", request.operator(),
                groupId, request.expectedVersion());
        return idempotency.execute(request.requestId(), "group.cancel", fingerprint, () -> {
            GroupRelease group = lockGroup(groupId);
            if (group.status() == GroupReleaseStatus.CANCELLED) {
                return view(groupId);
            }
            rejectIfTerminal(group);
            if (group.version() != request.expectedVersion()) {
                throw ApiException.conflict("GROUP_VERSION_CONFLICT",
                        "expectedVersion 与当前版本不一致: " + group.version());
            }
            groupReleaseRepository.cancel(groupId, request.expectedVersion());
            groupMemberRepository.cancelUnfinishedByGroup(groupId);
            groupMemberRepository.releaseOccupancyByGroup(groupId);
            return view(groupId);
        }, GroupReleaseView.class);
    }

    public GroupReleaseView get(long groupId) {
        findGroup(groupId);
        return view(groupId);
    }

    public GroupRelease findGroup(long groupId) {
        return groupReleaseRepository.findById(groupId)
                .orElseThrow(() -> ApiException.notFound("GROUP_NOT_FOUND", "组发布单不存在: " + groupId));
    }

    private GroupRelease lockGroup(long groupId) {
        return groupReleaseRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> ApiException.notFound("GROUP_NOT_FOUND", "组发布单不存在: " + groupId));
    }

    private void rejectIfTerminal(GroupRelease group) {
        switch (group.status()) {
            case ACTIVATED -> throw ApiException.conflict("GROUP_ALREADY_ACTIVATED", "组已激活，不允许该操作");
            case ABORTED -> throw ApiException.conflict("GROUP_ABORTED", "组已因准备失败中止");
            case CANCELLED -> throw ApiException.conflict("GROUP_CANCELLED", "组已取消");
            default -> {
            }
        }
    }

    private GroupReleaseView view(long groupId) {
        GroupRelease group = findGroup(groupId);
        List<GroupMemberView> members = groupMemberRepository.findByGroup(groupId).stream()
                .map(GroupMemberView::of).toList();
        return GroupReleaseView.of(group, members);
    }
}
