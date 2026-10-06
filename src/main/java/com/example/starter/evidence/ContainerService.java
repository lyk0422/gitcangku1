package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.ContainerCommandRequest;
import com.example.starter.evidence.dto.ContainerCreateRequest;
import com.example.starter.evidence.dto.ContainerInspectionRequest;
import com.example.starter.evidence.dto.ContainerInspectionView;
import com.example.starter.evidence.dto.ContainerMemberRequest;
import com.example.starter.evidence.dto.ContainerTransferInitiateRequest;
import com.example.starter.evidence.dto.ContainerTransferView;
import com.example.starter.evidence.dto.ContainerTreeView;
import com.example.starter.evidence.dto.ContainerView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 封存箱核心服务。整树操作（装箱/拆箱/交接/箱封条核验）均先锁定根箱行串行化；
 * 装箱与拆箱只对同保管人、SEALED 且无借出或待交接的对象；成员变更携带根箱
 * expectedVersion 并加一；根箱交接发起冻结整树，接受时重新核验完整树后一次推进全部保管权。
 */
@Service
public class ContainerService {

    static final String OP_CONTAINER_CREATE = "CONTAINER_CREATE";
    static final String OP_CONTAINER_PACK = "CONTAINER_PACK";
    static final String OP_CONTAINER_UNPACK = "CONTAINER_UNPACK";
    static final String OP_CONTAINER_TRANSFER_INITIATE = "CONTAINER_TRANSFER_INITIATE";
    static final String OP_CONTAINER_TRANSFER_ACCEPT = "CONTAINER_TRANSFER_ACCEPT";
    static final String OP_CONTAINER_TRANSFER_CANCEL = "CONTAINER_TRANSFER_CANCEL";
    static final String OP_CONTAINER_SEAL_INSPECTION = "CONTAINER_SEAL_INSPECTION";

    /**
     * 封存箱最大嵌套层数（根箱为第 1 层）。
     */
    static final int MAX_DEPTH = 3;

    /**
     * 一个根箱整树最多容纳的证物件数。
     */
    static final int MAX_EVIDENCE_PER_ROOT = 10;

    private final ContainerRepository containerRepository;
    private final ContainerMemberRepository memberRepository;
    private final ContainerTransferRepository transferRepository;
    private final ContainerInspectionRepository inspectionRepository;
    private final EvidenceRepository evidenceRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public ContainerService(ContainerRepository containerRepository,
                            ContainerMemberRepository memberRepository,
                            ContainerTransferRepository transferRepository,
                            ContainerInspectionRepository inspectionRepository,
                            EvidenceRepository evidenceRepository,
                            CommandLogRepository commandLogRepository,
                            ObjectMapper objectMapper) {
        this.containerRepository = containerRepository;
        this.memberRepository = memberRepository;
        this.transferRepository = transferRepository;
        this.inspectionRepository = inspectionRepository;
        this.evidenceRepository = evidenceRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 新建封存箱：初始为根箱，SEALED，版本 1，保管人为操作人。containerKey 全局唯一。
     */
    @Transactional
    public StoredResponse create(String actorId, ContainerCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (containerRepository.findByKey(request.containerKey()).isPresent()) {
            throw ApiException.conflict("封存箱已存在: " + request.containerKey());
        }
        LocalDateTime now = LocalDateTime.now();
        containerRepository.insert(request.containerKey(), request.sealNo(), actorId, now);
        Container container = containerRepository.findByKey(request.containerKey()).orElseThrow();
        return record(request.requestId(), OP_CONTAINER_CREATE, actorId, requestHash,
                201, toView(container));
    }

    /**
     * 装箱：把证物或子箱挂入目标箱。整次校验失败（环、重复成员、深度超限、
     * 混入不同保管人返回 422）不改变任何归属；成功后根箱版本加一。
     */
    @Transactional
    public StoredResponse pack(String actorId, String containerKey,
                               ContainerMemberRequest request, String requestHash) {
        Container target = containerRepository.findByKey(containerKey)
                .orElseThrow(() -> ApiException.notFound("封存箱不存在: " + containerKey));
        String memberType = requireMemberType(request.memberType());
        // 锁定集合：目标树根箱 + 成员子箱自身行；按键排序避免并发装箱互相死锁。
        Set<String> lockKeys = new LinkedHashSet<>();
        lockKeys.add(target.rootKey());
        if (ContainerMember.TYPE_BOX.equals(memberType)) {
            lockKeys.add(request.memberKey());
        }
        lockKeys.stream().sorted().forEach(key -> containerRepository.findByKeyForUpdate(key)
                .orElseThrow(() -> ApiException.notFound("封存箱不存在: " + key)));
        Container lockedTarget = containerRepository.findByKey(containerKey).orElseThrow();
        if (!lockedTarget.rootKey().equals(target.rootKey())) {
            throw ApiException.conflict("封存箱树结构已并发变化，请重试: " + containerKey);
        }
        Container root = containerRepository.findByKey(lockedTarget.rootKey()).orElseThrow();
        // 当前权限先于幂等重放
        requireCustodian(root, actorId);
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireSealedForMembership(lockedTarget);
        requireSealedForMembership(root);
        LocalDateTime now = LocalDateTime.now();
        List<Container> rootTree = containerRepository.findByRootKey(root.containerKey());
        List<String> rootTreeKeys = rootTree.stream().map(Container::containerKey).toList();
        int extraEvidence;
        if (ContainerMember.TYPE_EVIDENCE.equals(memberType)) {
            Evidence evidence = evidenceRepository.findByKeyForUpdate(request.memberKey())
                    .orElseThrow(() -> ApiException.notFound("证物不存在: " + request.memberKey()));
            requireNoMember(ContainerMember.TYPE_EVIDENCE, request.memberKey());
            requireSameCustodian(evidence.custodianId(), root);
            requireEvidenceSealed(evidence);
            extraEvidence = 1;
        } else {
            Container box = containerRepository.findByKey(request.memberKey()).orElseThrow();
            requireNoMember(ContainerMember.TYPE_BOX, request.memberKey());
            if (box.containerKey().equals(root.containerKey())) {
                throw ApiException.unprocessable("禁止把根箱装入自身子树（环）: " + request.memberKey());
            }
            requireSameCustodian(box.custodianId(), root);
            requireSealedForMembership(box);
            List<Container> boxTree = containerRepository.findByRootKey(box.containerKey());
            List<String> boxTreeKeys = boxTree.stream().map(Container::containerKey).toList();
            for (Container node : boxTree) {
                requireSameCustodian(node.custodianId(), root);
                requireSealedForMembership(node);
            }
            List<String> boxEvidenceKeys = evidenceMemberKeys(boxTreeKeys);
            for (Evidence memberEvidence : evidenceRepository.findByKeysForUpdate(boxEvidenceKeys)) {
                requireSameCustodian(memberEvidence.custodianId(), root);
                requireEvidenceSealed(memberEvidence);
            }
            if (depthOf(lockedTarget) + heightOf(box.containerKey()) > MAX_DEPTH) {
                throw ApiException.unprocessable("装箱后嵌套层级超过 " + MAX_DEPTH + " 层");
            }
            extraEvidence = boxEvidenceKeys.size();
        }
        if (memberRepository.countEvidenceMembers(rootTreeKeys) + extraEvidence
                > MAX_EVIDENCE_PER_ROOT) {
            throw ApiException.unprocessable("一个根箱最多容纳 " + MAX_EVIDENCE_PER_ROOT + " 件证物");
        }
        if (!containerRepository.incrementVersion(root.containerKey(),
                request.expectedVersion(), now)) {
            throw ApiException.conflict("根箱版本不匹配，期望版本: " + request.expectedVersion());
        }
        memberRepository.insert(containerKey, memberType, request.memberKey(), now);
        if (ContainerMember.TYPE_BOX.equals(memberType)) {
            containerRepository.updateParent(request.memberKey(), containerKey, now);
            List<String> boxTreeKeys = containerRepository.findByRootKey(request.memberKey())
                    .stream().map(Container::containerKey).toList();
            containerRepository.updateRootKey(boxTreeKeys, root.containerKey(), now);
        }
        Container updated = containerRepository.findByKey(root.containerKey()).orElseThrow();
        return record(request.requestId(), OP_CONTAINER_PACK, actorId, requestHash,
                200, toView(updated));
    }

    /**
     * 拆箱：把证物或子箱从直接父箱取出。待交接期间禁改成员；成功后根箱版本加一，
     * 子箱拆出后成为独立根箱（其子树 root_key 同步指向自身）。
     */
    @Transactional
    public StoredResponse unpack(String actorId, String containerKey,
                                 ContainerMemberRequest request, String requestHash) {
        Container parent = containerRepository.findByKey(containerKey)
                .orElseThrow(() -> ApiException.notFound("封存箱不存在: " + containerKey));
        String memberType = requireMemberType(request.memberType());
        Container root = containerRepository.findByKeyForUpdate(parent.rootKey())
                .orElseThrow(() -> ApiException.notFound("封存箱不存在: " + parent.rootKey()));
        Container lockedParent = containerRepository.findByKey(containerKey).orElseThrow();
        if (!lockedParent.rootKey().equals(root.containerKey())) {
            throw ApiException.conflict("封存箱树结构已并发变化，请重试: " + containerKey);
        }
        // 当前权限先于幂等重放
        requireCustodian(root, actorId);
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireSealedForMembership(lockedParent);
        requireSealedForMembership(root);
        ContainerMember member = memberRepository.findByMember(memberType, request.memberKey())
                .orElseThrow(() -> ApiException.conflict("对象不在任何封存箱中: " + request.memberKey()));
        if (!member.containerKey().equals(containerKey)) {
            throw ApiException.conflict("对象不是该箱的直接成员: " + request.memberKey());
        }
        LocalDateTime now = LocalDateTime.now();
        if (ContainerMember.TYPE_EVIDENCE.equals(memberType)) {
            Evidence evidence = evidenceRepository.findByKeyForUpdate(request.memberKey())
                    .orElseThrow(() -> ApiException.notFound("证物不存在: " + request.memberKey()));
            requireEvidenceSealed(evidence);
        } else {
            Container box = containerRepository.findByKeyForUpdate(request.memberKey())
                    .orElseThrow(() -> ApiException.notFound("封存箱不存在: " + request.memberKey()));
            requireSealedForMembership(box);
        }
        if (!containerRepository.incrementVersion(root.containerKey(),
                request.expectedVersion(), now)) {
            throw ApiException.conflict("根箱版本不匹配，期望版本: " + request.expectedVersion());
        }
        memberRepository.delete(containerKey, memberType, request.memberKey());
        if (ContainerMember.TYPE_BOX.equals(memberType)) {
            List<String> subtreeKeys = collectSubtreeKeys(request.memberKey());
            containerRepository.updateParent(request.memberKey(), null, now);
            containerRepository.updateRootKey(subtreeKeys, request.memberKey(), now);
        }
        Container updated = containerRepository.findByKey(root.containerKey()).orElseThrow();
        return record(request.requestId(), OP_CONTAINER_UNPACK, actorId, requestHash,
                200, toView(updated));
    }

    /**
     * 根箱发起交接：仅当前保管人，整树（含成员证物）封条必须全部正常；
     * 成功后整树箱进入 TRANSFER_PENDING，封条与版本冻结。
     */
    @Transactional
    public StoredResponse initiateTransfer(String actorId, String containerKey,
                                           ContainerTransferInitiateRequest request,
                                           String requestHash) {
        Container root = lockRootContainer(containerKey);
        // 当前权限先于幂等重放
        requireCustodian(root, actorId);
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (request.toCustodian().equals(actorId)) {
            throw ApiException.badRequest("接收人不能与发起保管人相同");
        }
        if (root.status() == ContainerStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("根箱封条已异常，禁止交接: " + containerKey);
        }
        if (root.status() == ContainerStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("根箱已存在待接收交接: " + containerKey);
        }
        List<Container> tree = containerRepository.findByRootKey(containerKey);
        for (Container node : tree) {
            if (node.status() == ContainerStatus.SEAL_BROKEN) {
                throw ApiException.unprocessable("树内箱封条异常，根箱不能交接: " + node.containerKey());
            }
        }
        List<String> evidenceKeys = evidenceMemberKeys(
                tree.stream().map(Container::containerKey).toList());
        List<Evidence> members = evidenceRepository.findByKeysForUpdate(evidenceKeys);
        for (Evidence evidence : members) {
            if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
                throw ApiException.unprocessable("树内证物封条异常，根箱不能交接: "
                        + evidence.evidenceKey());
            }
            if (evidence.status() != EvidenceStatus.SEALED) {
                throw ApiException.conflict("树内证物存在借出或待交接，禁止整箱交接: "
                        + evidence.evidenceKey());
            }
        }
        LocalDateTime now = LocalDateTime.now();
        transferRepository.insert(containerKey, actorId, request.toCustodian(), now);
        containerRepository.freezeTree(containerKey, now);
        ContainerTransfer pending = transferRepository.findPendingByContainerKey(containerKey)
                .orElseThrow();
        return record(request.requestId(), OP_CONTAINER_TRANSFER_INITIATE, actorId, requestHash,
                200, toView(pending));
    }

    /**
     * 接受根箱交接：仅指定接收人；重新核验完整树（保管人一致、封条全部正常）后
     * 一次推进整树箱与证物的保管权；任一成员异常则整箱 422，不改变任何归属。
     */
    @Transactional
    public StoredResponse acceptTransfer(String actorId, String containerKey,
                                         ContainerCommandRequest request, String requestHash) {
        Container root = lockRootContainer(containerKey);
        ContainerTransfer latest = transferRepository.findLatestByContainerKey(containerKey)
                .orElseThrow(() -> ApiException.conflict("根箱不存在交接记录: " + containerKey));
        // 当前权限先于幂等重放
        if (!latest.toCustodian().equals(actorId)) {
            throw ApiException.conflict("操作人不是指定接收人: " + actorId);
        }
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (latest.status() != TransferStatus.PENDING
                || root.status() != ContainerStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("根箱不存在待接收交接: " + containerKey);
        }
        List<Container> tree = containerRepository.findByRootKey(containerKey);
        for (Container node : tree) {
            if (node.status() == ContainerStatus.SEAL_BROKEN) {
                throw ApiException.unprocessable("树内箱封条异常，整箱接收失败: " + node.containerKey());
            }
            if (node.status() != ContainerStatus.TRANSFER_PENDING
                    || !node.custodianId().equals(latest.fromCustodian())) {
                throw ApiException.conflict("树结构或归属已变化，整箱接收失败: " + node.containerKey());
            }
        }
        List<String> evidenceKeys = evidenceMemberKeys(
                tree.stream().map(Container::containerKey).toList());
        List<Evidence> members = evidenceRepository.findByKeysForUpdate(evidenceKeys);
        for (Evidence evidence : members) {
            if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
                throw ApiException.unprocessable("树内证物封条异常，整箱接收失败: "
                        + evidence.evidenceKey());
            }
            if (evidence.status() != EvidenceStatus.SEALED
                    || !evidence.custodianId().equals(latest.fromCustodian())) {
                throw ApiException.conflict("树内证物状态或归属已变化，整箱接收失败: "
                        + evidence.evidenceKey());
            }
        }
        LocalDateTime now = LocalDateTime.now();
        if (!transferRepository.decide(latest.id(), TransferStatus.ACCEPTED, now)) {
            throw ApiException.conflict("交接已被处理: " + containerKey);
        }
        containerRepository.acceptTree(containerKey, actorId, now);
        evidenceRepository.updateCustodianForKeys(evidenceKeys, actorId, now);
        Container updated = containerRepository.findByKey(containerKey).orElseThrow();
        return record(request.requestId(), OP_CONTAINER_TRANSFER_ACCEPT, actorId, requestHash,
                200, toView(updated));
    }

    /**
     * 取消根箱交接：仅原保管人（发起人）可取消；整树箱回到 SEALED，
     * 待接收期间被打坏的箱保持 SEAL_BROKEN，保管人不改变。
     */
    @Transactional
    public StoredResponse cancelTransfer(String actorId, String containerKey,
                                         ContainerCommandRequest request, String requestHash) {
        Container root = lockRootContainer(containerKey);
        ContainerTransfer latest = transferRepository.findLatestByContainerKey(containerKey)
                .orElseThrow(() -> ApiException.conflict("根箱不存在交接记录: " + containerKey));
        // 当前权限先于幂等重放
        if (!latest.fromCustodian().equals(actorId)) {
            throw ApiException.conflict("仅原保管人可取消交接: " + actorId);
        }
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (latest.status() != TransferStatus.PENDING
                || root.status() != ContainerStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("根箱不存在待接收交接: " + containerKey);
        }
        LocalDateTime now = LocalDateTime.now();
        if (!transferRepository.decide(latest.id(), TransferStatus.CANCELLED, now)) {
            throw ApiException.conflict("交接已被处理: " + containerKey);
        }
        containerRepository.unfreezeTree(containerKey, now);
        Container updated = containerRepository.findByKey(containerKey).orElseThrow();
        return record(request.requestId(), OP_CONTAINER_TRANSFER_CANCEL, actorId, requestHash,
                200, toView(updated));
    }

    /**
     * 箱封条核验：仅当前保管人；待交接期间仍允许原保管人检查成员箱封条。
     * 通过仅追加不可变记录；失败使该箱进入 SEAL_BROKEN（终态）并使根箱不能交接。
     */
    @Transactional
    public StoredResponse inspectSeal(String actorId, String containerKey,
                                      ContainerInspectionRequest request, String requestHash) {
        Container container = containerRepository.findByKey(containerKey)
                .orElseThrow(() -> ApiException.notFound("封存箱不存在: " + containerKey));
        containerRepository.findByKeyForUpdate(container.rootKey())
                .orElseThrow(() -> ApiException.notFound("封存箱不存在: " + container.rootKey()));
        Container locked = containerRepository.findByKey(containerKey).orElseThrow();
        if (!locked.rootKey().equals(container.rootKey())) {
            throw ApiException.conflict("封存箱树结构已并发变化，请重试: " + containerKey);
        }
        // 当前权限先于幂等重放
        requireCustodian(locked, actorId);
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocalDateTime now = LocalDateTime.now();
        inspectionRepository.insert(containerKey, actorId, request.passed(), request.note(), now);
        if (!request.passed() && locked.status() != ContainerStatus.SEAL_BROKEN) {
            containerRepository.updateStatus(containerKey, ContainerStatus.SEAL_BROKEN, now);
        }
        List<ContainerInspection> inspections = inspectionRepository.findByContainerKey(containerKey);
        ContainerInspection created = inspections.get(inspections.size() - 1);
        return record(request.requestId(), OP_CONTAINER_SEAL_INSPECTION, actorId, requestHash,
                200, toView(created));
    }

    /**
     * 查询自请求箱向下的完整子树；待接收冻结期间仍列出全部成员（证物与子箱）。
     */
    @Transactional(readOnly = true)
    public ContainerTreeView tree(String containerKey) {
        Container container = containerRepository.findByKey(containerKey)
                .orElseThrow(() -> ApiException.notFound("封存箱不存在: " + containerKey));
        return buildTree(container, depthOf(container));
    }

    private ContainerTreeView buildTree(Container container, int depth) {
        List<ContainerTreeView.Member> members = new ArrayList<>();
        for (ContainerMember member : memberRepository.findByContainerKey(container.containerKey())) {
            if (ContainerMember.TYPE_EVIDENCE.equals(member.memberType())) {
                Evidence evidence = evidenceRepository.findByKey(member.memberKey())
                        .orElseThrow(() -> ApiException.notFound("证物不存在: " + member.memberKey()));
                members.add(new ContainerTreeView.Member(member.memberType(), member.memberKey(),
                        evidence.custodianId(), evidence.status().name(), null));
            } else {
                Container child = containerRepository.findByKey(member.memberKey())
                        .orElseThrow(() -> ApiException.notFound("封存箱不存在: " + member.memberKey()));
                members.add(new ContainerTreeView.Member(member.memberType(), member.memberKey(),
                        child.custodianId(), child.status().name(),
                        buildTree(child, depth + 1)));
            }
        }
        return new ContainerTreeView(container.containerKey(), container.sealNo(),
                container.custodianId(), container.status().name(), container.version(),
                depth, members);
    }

    /**
     * 锁定并返回根箱；仍在箱内的子箱不允许发起或决定交接。
     */
    private Container lockRootContainer(String containerKey) {
        Container container = containerRepository.findByKey(containerKey)
                .orElseThrow(() -> ApiException.notFound("封存箱不存在: " + containerKey));
        if (container.parentKey() != null) {
            throw ApiException.conflict("仅根箱可发起或决定交接: " + containerKey);
        }
        return containerRepository.findByKeyForUpdate(containerKey)
                .orElseThrow(() -> ApiException.notFound("封存箱不存在: " + containerKey));
    }

    private String requireMemberType(String memberType) {
        if (!ContainerMember.TYPE_EVIDENCE.equals(memberType)
                && !ContainerMember.TYPE_BOX.equals(memberType)) {
            throw ApiException.badRequest("成员类型非法，仅支持 EVIDENCE/BOX: " + memberType);
        }
        return memberType;
    }

    private void requireCustodian(Container container, String actorId) {
        if (!container.custodianId().equals(actorId)) {
            throw ApiException.conflict("操作人不是当前保管人: " + actorId);
        }
    }

    private void requireSameCustodian(String custodianId, Container root) {
        if (!custodianId.equals(root.custodianId())) {
            throw ApiException.unprocessable("混入不同保管人的对象，禁止装箱: " + custodianId);
        }
    }

    /**
     * 装箱/拆箱对象必须为 SEALED：封条异常 422，待交接（冻结）409。
     */
    private void requireSealedForMembership(Container container) {
        if (container.status() == ContainerStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("封条已异常，禁止装箱或拆箱: " + container.containerKey());
        }
        if (container.status() == ContainerStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("待交接期间禁止变更成员: " + container.containerKey());
        }
    }

    private void requireEvidenceSealed(Evidence evidence) {
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("证物封条已异常，禁止装箱或拆箱: "
                    + evidence.evidenceKey());
        }
        if (evidence.status() != EvidenceStatus.SEALED) {
            throw ApiException.conflict("证物存在借出或待交接，禁止装箱或拆箱: "
                    + evidence.evidenceKey());
        }
    }

    private void requireNoMember(String memberType, String memberKey) {
        if (memberRepository.findByMember(memberType, memberKey).isPresent()) {
            throw ApiException.unprocessable("对象已装入其他封存箱（重复成员）: " + memberKey);
        }
    }

    /**
     * 当前箱层级：根箱为 1，沿 parent_key 上溯计数。
     */
    private int depthOf(Container container) {
        int depth = 1;
        Container current = container;
        while (current.parentKey() != null) {
            current = containerRepository.findByKey(current.parentKey()).orElseThrow();
            depth++;
        }
        return depth;
    }

    /**
     * 子树高度：箱自身为 1，逐层向下取最大子箱高度加一。
     */
    private int heightOf(String containerKey) {
        int height = 1;
        for (ContainerMember member : memberRepository.findByContainerKey(containerKey)) {
            if (ContainerMember.TYPE_BOX.equals(member.memberType())) {
                height = Math.max(height, 1 + heightOf(member.memberKey()));
            }
        }
        return height;
    }

    /**
     * 收集子树全部箱键（含自身），按层遍历。
     */
    private List<String> collectSubtreeKeys(String containerKey) {
        List<String> keys = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(containerKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            keys.add(current);
            for (ContainerMember member : memberRepository.findByContainerKey(current)) {
                if (ContainerMember.TYPE_BOX.equals(member.memberType())) {
                    queue.add(member.memberKey());
                }
            }
        }
        return keys;
    }

    /**
     * 查询一组箱内的全部证物成员键。
     */
    private List<String> evidenceMemberKeys(List<String> containerKeys) {
        return memberRepository.findByContainerKeys(containerKeys).stream()
                .filter(member -> ContainerMember.TYPE_EVIDENCE.equals(member.memberType()))
                .map(ContainerMember::memberKey)
                .toList();
    }

    private Optional<StoredResponse> checkReplay(String requestId, String requestHash) {
        return commandLogRepository.findByKey(requestId).map(log -> {
            if (!log.requestHash().equals(requestHash)) {
                throw ApiException.conflict("幂等键已被不同参数使用: " + requestId);
            }
            return new StoredResponse(log.responseStatus(), log.responseBody());
        });
    }

    private StoredResponse record(String requestId, String operation, String actorId,
                                  String requestHash, int status, Object body) {
        String json = toJson(body);
        commandLogRepository.insert(requestId, actorId, operation, requestHash, status, json,
                LocalDateTime.now());
        return new StoredResponse(status, json);
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private ContainerView toView(Container container) {
        return new ContainerView(container.containerKey(), container.sealNo(),
                container.custodianId(), container.status(), container.version(),
                container.parentKey(), container.rootKey(), container.createdAt(),
                container.updatedAt());
    }

    private ContainerTransferView toView(ContainerTransfer transfer) {
        return new ContainerTransferView(transfer.containerKey(), transfer.fromCustodian(),
                transfer.toCustodian(), transfer.status(), transfer.initiatedBy(),
                transfer.createdAt(), transfer.decidedAt());
    }

    private ContainerInspectionView toView(ContainerInspection inspection) {
        return new ContainerInspectionView(inspection.containerKey(), inspection.inspectorId(),
                inspection.passed(), inspection.note(), inspection.createdAt());
    }
}
