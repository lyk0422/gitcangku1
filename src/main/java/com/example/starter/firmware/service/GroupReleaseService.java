package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CreateGroupReleaseRequest;
import com.example.starter.firmware.api.GroupActivateRequest;
import com.example.starter.firmware.api.GroupPrepareRequest;
import com.example.starter.firmware.api.GroupReleaseView;
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
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 固定设备组预备屏障：区别于单设备普通升级。全部当前代次成员 PREPARED 才允许激活；
 * 激活在组行锁内原子完成（生成全组一致的激活代次并切换全组版本），只模拟本地元数据，
 * 不承诺真实设备同时联网切换。准备、取消与激活竞争经由组行锁串行，只产生一个完整终态。
 */
@Service
public class GroupReleaseService {

    private final GroupReleaseRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final DeviceRepository deviceRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public GroupReleaseService(GroupReleaseRepository groupRepository,
                               GroupMemberRepository memberRepository,
                               DeviceRepository deviceRepository,
                               IdempotencyService idempotency, Clock clock) {
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
        this.deviceRepository = deviceRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 新建组发布：2~8 台同型号已登记设备；设备不能同时属于另一活动组（唯一索引兜底）。
     */
    public GroupReleaseView create(CreateGroupReleaseRequest request) {
        List<String> deviceIds = new ArrayList<>(new TreeSet<>(request.deviceIds()));
        if (deviceIds.size() != request.deviceIds().size()) {
            throw ApiException.badRequest("DUPLICATE_DEVICE", "成员设备重复");
        }
        List<String> parts = new ArrayList<>(List.of("group.create", request.operator(), request.model(),
                request.toVersion()));
        parts.addAll(deviceIds);
        String fingerprint = StructuredFingerprint.of(parts.toArray(new String[0]));
        return idempotency.execute(request.requestId(), "group.create", fingerprint, () -> {
            for (String deviceId : deviceIds) {
                Device device = deviceRepository.findById(deviceId)
                        .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
                if (!device.model().equals(request.model())) {
                    throw ApiException.badRequest("MODEL_MISMATCH",
                            "设备型号与组型号不一致: " + deviceId);
                }
            }
            long groupId = groupRepository.insert(request.model(), request.toVersion());
            try {
                for (String deviceId : deviceIds) {
                    memberRepository.insert(groupId, deviceId, 1);
                }
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_IN_ACTIVE_GROUP", "设备已属于另一活动组发布");
            }
            return view(groupId);
        }, GroupReleaseView.class);
    }

    /**
     * 准备回执：PREPARED 仅暂存不改设备当前版本；FAILED 整组 ABORTED 并记录失败成员，
     * 已暂存成员也不切版本。回执代次必须等于组当前代次，旧代次回执 409 拒绝。
     */
    public GroupReleaseView prepare(long groupId, String deviceId, GroupPrepareRequest request) {
        String fingerprint = StructuredFingerprint.of("group.prepare", request.operator(),
                String.valueOf(groupId), deviceId, String.valueOf(request.expectedGeneration()),
                request.result().name());
        return idempotency.execute(request.requestId(), "group.prepare", fingerprint, () -> {
            GroupRelease group = lockGroup(groupId);
            GroupMember member = memberRepository.findByGroupAndDevice(groupId, deviceId)
                    .orElseThrow(() -> ApiException.notFound("MEMBER_NOT_FOUND",
                            "设备不在组内: " + deviceId));
            if (member.generation() != request.expectedGeneration()) {
                throw ApiException.conflict("STALE_GENERATION",
                        "回执代次 " + request.expectedGeneration() + " 与当前组代次 " + member.generation() + " 不一致");
            }
            if (group.status() != GroupReleaseStatus.PREPARING) {
                throw ApiException.conflict("GROUP_NOT_PREPARING",
                        "组状态为 " + group.status() + "，不再受理准备回执");
            }
            switch (member.status()) {
                case PENDING -> {
                    if (request.result() == PrepareResult.PREPARED) {
                        memberRepository.markPrepared(member.id(), Instant.now(clock).toString());
                    } else {
                        memberRepository.markFailed(member.id());
                        groupRepository.abort(groupId, deviceId);
                        memberRepository.releaseByGroup(groupId);
                    }
                }
                case PREPARED -> {
                    if (request.result() == PrepareResult.FAILED) {
                        throw ApiException.conflict("PREPARE_RESULT_CONFLICT",
                                "成员已暂存，不能改为失败: " + deviceId);
                    }
                }
                case FAILED -> throw ApiException.conflict("PREPARE_RESULT_CONFLICT",
                        "成员已失败终结: " + deviceId);
            }
            return view(groupId);
        }, GroupReleaseView.class);
    }

    /**
     * 激活：全部当前代次成员暂存成功才允许；未齐备 422 且不占请求键，齐备后可同键重试。
     * 激活原子生成同一组激活代次、按成员数统一记成功并切换全组版本；激活后不允许取消。
     */
    public GroupReleaseView activate(long groupId, GroupActivateRequest request) {
        String fingerprint = StructuredFingerprint.of("group.activate", request.operator(),
                String.valueOf(groupId), String.valueOf(request.expectedGeneration()));
        return idempotency.execute(request.requestId(), "group.activate", fingerprint, () -> {
            GroupRelease group = lockGroup(groupId);
            if (group.status() == GroupReleaseStatus.ACTIVATED) {
                throw ApiException.conflict("GROUP_ALREADY_ACTIVATED", "组已激活，不能重复激活");
            }
            if (group.status() != GroupReleaseStatus.PREPARING) {
                throw ApiException.conflict("GROUP_NOT_PREPARING",
                        "组状态为 " + group.status() + "，不能激活");
            }
            if (group.generation() != request.expectedGeneration()) {
                throw ApiException.conflict("STALE_GENERATION",
                        "期望代次 " + request.expectedGeneration() + " 与当前组代次 " + group.generation() + " 不一致");
            }
            List<GroupMember> members = memberRepository.findByGroup(groupId);
            long prepared = members.stream().filter(m -> m.status() == GroupMemberStatus.PREPARED).count();
            if (prepared != members.size()) {
                throw ApiException.unprocessable("GROUP_NOT_READY",
                        "存在未暂存成员 " + (members.size() - prepared) + " 台，不能激活");
            }
            groupRepository.activate(groupId, group.generation(), members.size());
            for (GroupMember member : members) {
                deviceRepository.updateCurrentVersion(member.deviceId(), group.toVersion());
            }
            memberRepository.releaseByGroup(groupId);
            return view(groupId);
        }, GroupReleaseView.class);
    }

    /**
     * 取消：仅 PREPARING 可取消并释放组占用；激活后 409；已终结组幂等返回当前状态。
     */
    public GroupReleaseView cancel(long groupId, String requestId, String operator) {
        String fingerprint = StructuredFingerprint.of("group.cancel", operator, String.valueOf(groupId));
        return idempotency.execute(requestId, "group.cancel", fingerprint, () -> {
            GroupRelease group = lockGroup(groupId);
            if (group.status() == GroupReleaseStatus.ACTIVATED) {
                throw ApiException.conflict("GROUP_ALREADY_ACTIVATED", "组已激活，不允许取消");
            }
            if (group.status() == GroupReleaseStatus.PREPARING) {
                groupRepository.cancel(groupId);
                memberRepository.releaseByGroup(groupId);
            }
            return view(groupId);
        }, GroupReleaseView.class);
    }

    public GroupReleaseView get(long groupId) {
        return view(groupId);
    }

    private GroupRelease lockGroup(long groupId) {
        return groupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> ApiException.notFound("GROUP_NOT_FOUND", "组发布不存在: " + groupId));
    }

    private GroupReleaseView view(long groupId) {
        GroupRelease group = groupRepository.findById(groupId)
                .orElseThrow(() -> ApiException.notFound("GROUP_NOT_FOUND", "组发布不存在: " + groupId));
        return GroupReleaseView.of(group, memberRepository.findByGroup(groupId));
    }
}
