package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.ContainerCommandRequest;
import com.example.starter.evidence.dto.ContainerCreateRequest;
import com.example.starter.evidence.dto.ContainerInspectionView;
import com.example.starter.evidence.dto.ContainerMembersRequest;
import com.example.starter.evidence.dto.ContainerSealInspectionRequest;
import com.example.starter.evidence.dto.ContainerTransferInitiateRequest;
import com.example.starter.evidence.dto.ContainerTransferView;
import com.example.starter.evidence.dto.ContainerTreeNode;
import com.example.starter.evidence.dto.ContainerView;
import com.example.starter.evidence.dto.EvidenceView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 封存箱（可嵌套容器）核心服务。规则：
 * 最多 3 层嵌套；一个根箱子树最多 10 件证物；证物与子箱仅能有一个直接父箱。
 * 装箱/拆箱仅对同保管人、SEALED 且无借出或待交接的对象；成员变更携带根箱
 * expectedVersion 并加一。根箱发起交接冻结整个子树；接收时重新核验完整树后
 * 一次推进全部保管权。所有树级写操作先锁定根箱行，业务与幂等日志同事务。
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
     * 容器最大嵌套层数（根箱为第 1 层）。
     */
    static final int MAX_DEPTH = 3;

    /**
     * 一个根箱子树内最多容纳的证物件数。
     */
    static final int MAX_EVIDENCE_PER_ROOT = 10;

    private final ContainerRepository containerRepository;
    private final ContainerMemberRepository memberRepository;
    private final ContainerTransferRepository transferRepository;
    private final ContainerSealInspectionRepository inspectionRepository;
    private final EvidenceRepository evidenceRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public ContainerService(ContainerRepository containerRepository,
                            ContainerMemberRepository memberRepository,
                            ContainerTransferRepository transferRepository,
                            ContainerSealInspectionRepository inspectionRepository,
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
     * 新建空根箱：初始 SEALED、封条 INTACT、版本 1，保管人为操作人。
     */
    @Transactional
    public StoredResponse create(String actorId, ContainerCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (containerRepository.findByKey(request.containerKey()).isPresent()) {
            throw ApiException.conflict("容器已存在: " + request.containerKey());
        }
        LocalDateTime now = LocalDateTime.now();
        containerRepository.insert(request.containerKey(), request.sealNo(), actorId, now);
        Container container = containerRepository.findByKey(request.containerKey()).orElseThrow();
        return record(request.requestId(), OP_CONTAINER_CREATE, actorId, requestHash,
                201, toView(container));
    }

    /**
     * 装箱：仅根箱当前保管人；根箱须 SEALED（待交接期间禁改成员）。
     * 成员须同保管人、SEALED 且无借出或待交接；环、重复成员、深度超限、
     * 混入不同保管人均返回 422 且整次不改变归属。成功后根箱版本加一。
     */
    @Transactional
    public StoredResponse pack(String actorId, String parentKey,
                               ContainerMembersRequest request, String requestHash) {
        Container root = lockTreeRoot(parentKey);
        requireCustodian(root, actorId);
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireTreeSealed(root);
        requireVersion(root, request.expectedVersion());
        List<String> evidenceKeys = request.evidenceKeys();
        List<String> containerKeys = request.containerKeys();
        requireMembersPresent(evidenceKeys, containerKeys);
        requireNoDuplicates(evidenceKeys, containerKeys);
        int evidenceTotal = countEvidence(collectContainerKeys(root.containerKey()));
        // 证物成员：先锁证物行再校验，保证与借出/交接/装箱并发时按事务提交顺序生效
        for (String key : evidenceKeys.stream().sorted().toList()) {
            Evidence evidence = evidenceRepository.findByKeyForUpdate(key)
                    .orElseThrow(() -> ApiException.notFound("证物不存在: " + key));
            if (memberRepository.findParentKey(MemberType.EVIDENCE, key).isPresent()) {
                throw ApiException.unprocessable("证物已在箱内，重复成员: " + key);
            }
            if (!evidence.custodianId().equals(actorId)) {
                throw ApiException.unprocessable("成员与箱保管人不一致: " + key);
            }
            requirePackable(evidence);
            evidenceTotal++;
        }
        // 子箱成员：先锁子箱行再校验；子箱必须是无父箱的根箱，整棵子树随装箱移动
        for (String key : containerKeys.stream().sorted().toList()) {
            Container member = containerRepository.findByKeyForUpdate(key)
                    .orElseThrow(() -> ApiException.notFound("容器不存在: " + key));
            if (isAncestorOf(key, parentKey)) {
                throw ApiException.unprocessable("装箱会形成环: " + key);
            }
            if (memberRepository.findParentKey(MemberType.CONTAINER, key).isPresent()) {
                throw ApiException.unprocessable("子箱已有父箱，重复成员: " + key);
            }
            if (!member.custodianId().equals(actorId)) {
                throw ApiException.unprocessable("成员与箱保管人不一致: " + key);
            }
            if (member.status() == ContainerStatus.TRANSFER_PENDING) {
                throw ApiException.conflict("子箱待交接，禁止装箱: " + key);
            }
            if (levelOf(parentKey) + heightOf(key) > MAX_DEPTH) {
                throw ApiException.unprocessable("嵌套层数超过 " + MAX_DEPTH + " 层: " + key);
            }
            evidenceTotal += countEvidence(collectContainerKeys(key));
        }
        if (evidenceTotal > MAX_EVIDENCE_PER_ROOT) {
            throw ApiException.unprocessable("根箱证物数超过 " + MAX_EVIDENCE_PER_ROOT + " 件");
        }
        LocalDateTime now = LocalDateTime.now();
        for (String key : evidenceKeys) {
            memberRepository.insert(parentKey, MemberType.EVIDENCE, key, now);
        }
        for (String key : containerKeys) {
            memberRepository.insert(parentKey, MemberType.CONTAINER, key, now);
        }
        containerRepository.incrementVersion(root.containerKey(), now);
        return record(request.requestId(), OP_CONTAINER_PACK, actorId, requestHash,
                200, tree(root.containerKey()));
    }

    /**
     * 拆箱：仅根箱当前保管人；根箱须 SEALED。成员必须属于该箱，
     * 任一成员不满足则整次 422 不改变归属。成功后根箱版本加一。
     */
    @Transactional
    public StoredResponse unpack(String actorId, String parentKey,
                                 ContainerMembersRequest request, String requestHash) {
        Container root = lockTreeRoot(parentKey);
        requireCustodian(root, actorId);
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireTreeSealed(root);
        requireVersion(root, request.expectedVersion());
        List<String> evidenceKeys = request.evidenceKeys();
        List<String> containerKeys = request.containerKeys();
        requireMembersPresent(evidenceKeys, containerKeys);
        requireNoDuplicates(evidenceKeys, containerKeys);
        for (String key : evidenceKeys) {
            requireDirectMember(parentKey, MemberType.EVIDENCE, key);
        }
        for (String key : containerKeys) {
            requireDirectMember(parentKey, MemberType.CONTAINER, key);
        }
        LocalDateTime now = LocalDateTime.now();
        for (String key : evidenceKeys) {
            memberRepository.delete(parentKey, MemberType.EVIDENCE, key);
        }
        for (String key : containerKeys) {
            memberRepository.delete(parentKey, MemberType.CONTAINER, key);
        }
        containerRepository.incrementVersion(root.containerKey(), now);
        return record(request.requestId(), OP_CONTAINER_UNPACK, actorId, requestHash,
                200, tree(root.containerKey()));
    }

    /**
     * 根箱发起整箱交接：仅根箱当前保管人；根箱 SEALED、封条 INTACT，
     * 且完整子树（全部子箱封条与全部证物封条）无异常，否则 422。
     * 成功后整个子树进入 TRANSFER_PENDING，冻结成员、封条与版本。
     */
    @Transactional
    public StoredResponse initiateTransfer(String actorId, String containerKey,
                                           ContainerTransferInitiateRequest request, String requestHash) {
        Container root = containerRepository.findByKeyForUpdate(containerKey)
                .orElseThrow(() -> ApiException.notFound("容器不存在: " + containerKey));
        if (memberRepository.findParentKey(MemberType.CONTAINER, containerKey).isPresent()) {
            throw ApiException.conflict("仅根箱可发起整箱交接: " + containerKey);
        }
        requireCustodian(root, actorId);
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (request.toCustodian().equals(actorId)) {
            throw ApiException.badRequest("接收人不能与发起保管人相同");
        }
        if (root.status() == ContainerStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("根箱已存在待接收交接: " + containerKey);
        }
        if (root.sealStatus() == SealStatus.BROKEN) {
            throw ApiException.unprocessable("箱封条异常，禁止交接: " + containerKey);
        }
        requireVersion(root, request.expectedVersion());
        List<String> treeKeys = collectContainerKeys(containerKey);
        requireTreeSealIntact(treeKeys);
        LocalDateTime now = LocalDateTime.now();
        transferRepository.insert(containerKey, actorId, request.toCustodian(), now);
        containerRepository.updateStatus(treeKeys, ContainerStatus.TRANSFER_PENDING, now);
        ContainerTransfer pending = transferRepository.findPendingByContainerKey(containerKey)
                .orElseThrow();
        return record(request.requestId(), OP_CONTAINER_TRANSFER_INITIATE, actorId, requestHash,
                200, toView(pending));
    }

    /**
     * 接受整箱交接：仅指定接收人；重新核验完整树（箱封条与成员证物封条），
     * 任一异常则 422 且整箱保管权不变；通过后一次推进子树全部保管权。
     */
    @Transactional
    public StoredResponse acceptTransfer(String actorId, String containerKey,
                                         ContainerCommandRequest request, String requestHash) {
        Container root = containerRepository.findByKeyForUpdate(containerKey)
                .orElseThrow(() -> ApiException.notFound("容器不存在: " + containerKey));
        ContainerTransfer latest = transferRepository.findLatestByContainerKey(containerKey)
                .orElseThrow(() -> ApiException.conflict("根箱不存在待接收交接: " + containerKey));
        if (!latest.toCustodian().equals(actorId)) {
            throw ApiException.conflict("操作人不是指定接收人: " + actorId);
        }
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        ContainerTransfer pending = transferRepository.findPendingByContainerKey(containerKey)
                .orElseThrow(() -> ApiException.conflict("根箱不存在待接收交接: " + containerKey));
        List<String> treeKeys = collectContainerKeys(containerKey);
        requireTreeSealIntact(treeKeys);
        LocalDateTime now = LocalDateTime.now();
        if (!transferRepository.decide(pending.id(), TransferStatus.ACCEPTED, now)) {
            throw ApiException.conflict("交接已被处理: " + containerKey);
        }
        containerRepository.updateCustody(treeKeys, actorId, ContainerStatus.SEALED, now);
        for (String evidenceKey : evidenceMembersOf(treeKeys)) {
            evidenceRepository.updateCustody(evidenceKey, actorId, EvidenceStatus.SEALED, now);
        }
        return record(request.requestId(), OP_CONTAINER_TRANSFER_ACCEPT, actorId, requestHash,
                200, tree(containerKey));
    }

    /**
     * 取消整箱交接：仅原保管人（发起人）可取消；子树解冻回到 SEALED，保管权不变。
     */
    @Transactional
    public StoredResponse cancelTransfer(String actorId, String containerKey,
                                         ContainerCommandRequest request, String requestHash) {
        Container root = containerRepository.findByKeyForUpdate(containerKey)
                .orElseThrow(() -> ApiException.notFound("容器不存在: " + containerKey));
        ContainerTransfer latest = transferRepository.findLatestByContainerKey(containerKey)
                .orElseThrow(() -> ApiException.conflict("根箱不存在待接收交接: " + containerKey));
        if (!latest.fromCustodian().equals(actorId)) {
            throw ApiException.conflict("仅原保管人可取消交接: " + actorId);
        }
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        ContainerTransfer pending = transferRepository.findPendingByContainerKey(containerKey)
                .orElseThrow(() -> ApiException.conflict("根箱不存在待接收交接: " + containerKey));
        LocalDateTime now = LocalDateTime.now();
        if (!transferRepository.decide(pending.id(), TransferStatus.CANCELLED, now)) {
            throw ApiException.conflict("交接已被处理: " + containerKey);
        }
        containerRepository.updateStatus(collectContainerKeys(containerKey),
                ContainerStatus.SEALED, now);
        return record(request.requestId(), OP_CONTAINER_TRANSFER_CANCEL, actorId, requestHash,
                200, tree(containerKey));
    }

    /**
     * 箱封条核验（异常登记）：仅当前保管人；待交接期间仍允许，与接收竞争时
     * 由根箱行锁保证只形成合法后态。失败使该箱 seal_status 进入 BROKEN（终态）。
     */
    @Transactional
    public StoredResponse inspectSeal(String actorId, String containerKey,
                                      ContainerSealInspectionRequest request, String requestHash) {
        Container root = lockTreeRoot(containerKey);
        requireCustodian(root, actorId);
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocalDateTime now = LocalDateTime.now();
        inspectionRepository.insert(containerKey, actorId, request.passed(), request.note(), now);
        Container target = containerRepository.findByKey(containerKey).orElseThrow();
        if (!request.passed() && target.sealStatus() != SealStatus.BROKEN) {
            containerRepository.updateSealStatus(containerKey, SealStatus.BROKEN, now);
        }
        List<ContainerSealInspection> inspections = inspectionRepository.findByContainerKey(containerKey);
        ContainerSealInspection created = inspections.get(inspections.size() - 1);
        return record(request.requestId(), OP_CONTAINER_SEAL_INSPECTION, actorId, requestHash,
                200, toView(created));
    }

    /**
     * 查询容器树：箱信息 + 递归子箱 + 证物成员；待交接期间仍完整列出冻结成员。
     */
    @Transactional(readOnly = true)
    public ContainerTreeNode tree(String containerKey) {
        Container container = containerRepository.findByKey(containerKey)
                .orElseThrow(() -> ApiException.notFound("容器不存在: " + containerKey));
        List<ContainerMember> members = memberRepository.findByContainerKey(containerKey);
        List<ContainerTreeNode> children = members.stream()
                .filter(member -> member.memberType() == MemberType.CONTAINER)
                .map(member -> tree(member.memberKey()))
                .toList();
        List<EvidenceView> evidenceItems = members.stream()
                .filter(member -> member.memberType() == MemberType.EVIDENCE)
                .map(member -> evidenceRepository.findByKey(member.memberKey()).orElseThrow())
                .map(this::toView)
                .toList();
        return new ContainerTreeNode(toView(container), children, evidenceItems);
    }

    /**
     * 定位并锁定目标箱所属树的根箱行；若加锁期间树结构并发变更则返回 409 要求重试。
     */
    private Container lockTreeRoot(String containerKey) {
        containerRepository.findByKey(containerKey)
                .orElseThrow(() -> ApiException.notFound("容器不存在: " + containerKey));
        String rootKey = rootKeyOf(containerKey);
        Container root = containerRepository.findByKeyForUpdate(rootKey).orElseThrow();
        if (!rootKeyOf(containerKey).equals(rootKey)) {
            throw ApiException.conflict("树结构并发变更，请重试: " + containerKey);
        }
        return root;
    }

    private String rootKeyOf(String containerKey) {
        String current = containerKey;
        while (true) {
            Optional<String> parent = memberRepository.findParentKey(MemberType.CONTAINER, current);
            if (parent.isEmpty()) {
                return current;
            }
            current = parent.get();
        }
    }

    /**
     * 收集以 rootKey 为根的整棵树全部箱业务键（广度优先，根在最前）。
     */
    private List<String> collectContainerKeys(String rootKey) {
        List<String> keys = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        keys.add(rootKey);
        queue.add(rootKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (ContainerMember member : memberRepository.findByContainerKey(current)) {
                if (member.memberType() == MemberType.CONTAINER) {
                    keys.add(member.memberKey());
                    queue.add(member.memberKey());
                }
            }
        }
        return keys;
    }

    private List<String> evidenceMembersOf(List<String> containerKeys) {
        return memberRepository.findByContainerKeys(containerKeys).stream()
                .filter(member -> member.memberType() == MemberType.EVIDENCE)
                .map(ContainerMember::memberKey)
                .toList();
    }

    private int countEvidence(List<String> containerKeys) {
        return evidenceMembersOf(containerKeys).size();
    }

    /**
     * 箱在树中的层数（根箱为第 1 层）。
     */
    private int levelOf(String containerKey) {
        int level = 1;
        String current = containerKey;
        while (true) {
            Optional<String> parent = memberRepository.findParentKey(MemberType.CONTAINER, current);
            if (parent.isEmpty()) {
                return level;
            }
            level++;
            current = parent.get();
        }
    }

    /**
     * 箱子树高度（不含证物；无子箱的箱高度为 1）。
     */
    private int heightOf(String containerKey) {
        int maxChildHeight = 0;
        for (ContainerMember member : memberRepository.findByContainerKey(containerKey)) {
            if (member.memberType() == MemberType.CONTAINER) {
                maxChildHeight = Math.max(maxChildHeight, heightOf(member.memberKey()));
            }
        }
        return maxChildHeight + 1;
    }

    /**
     * 判断 maybeAncestor 是否为 containerKey 的祖先（含自身），用于装箱环检测。
     */
    private boolean isAncestorOf(String maybeAncestor, String containerKey) {
        String current = containerKey;
        while (true) {
            if (current.equals(maybeAncestor)) {
                return true;
            }
            Optional<String> parent = memberRepository.findParentKey(MemberType.CONTAINER, current);
            if (parent.isEmpty()) {
                return false;
            }
            current = parent.get();
        }
    }

    /**
     * 核验完整树封条：全部箱封条 INTACT 且全部成员证物非 SEAL_BROKEN。
     * 证物行按键序加锁（SELECT ... FOR UPDATE），与并发异常登记按提交顺序生效。
     */
    private void requireTreeSealIntact(List<String> treeKeys) {
        for (String key : treeKeys) {
            Container container = containerRepository.findByKey(key).orElseThrow();
            if (container.sealStatus() == SealStatus.BROKEN) {
                throw ApiException.unprocessable("箱内封条异常，禁止交接: " + key);
            }
        }
        for (String evidenceKey : evidenceMembersOf(treeKeys).stream().sorted().toList()) {
            Evidence evidence = evidenceRepository.findByKeyForUpdate(evidenceKey)
                    .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
            if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
                throw ApiException.unprocessable("箱内证物封条异常，禁止交接: " + evidenceKey);
            }
        }
    }

    private void requireCustodian(Container root, String actorId) {
        if (!root.custodianId().equals(actorId)) {
            throw ApiException.conflict("操作人不是当前保管人: " + actorId);
        }
    }

    private void requireTreeSealed(Container root) {
        if (root.status() == ContainerStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("待交接期间禁止变更成员: " + root.containerKey());
        }
    }

    private void requireVersion(Container root, int expectedVersion) {
        if (root.version() != expectedVersion) {
            throw ApiException.conflict("根箱版本不匹配: 期望 " + expectedVersion
                    + "，当前 " + root.version());
        }
    }

    private void requireMembersPresent(List<String> evidenceKeys, List<String> containerKeys) {
        if (evidenceKeys.isEmpty() && containerKeys.isEmpty()) {
            throw ApiException.badRequest("成员列表不能为空");
        }
    }

    private void requireNoDuplicates(List<String> evidenceKeys, List<String> containerKeys) {
        requireUnique(evidenceKeys);
        requireUnique(containerKeys);
    }

    private void requireUnique(List<String> keys) {
        Set<String> seen = new HashSet<>();
        for (String key : keys) {
            if (!seen.add(key)) {
                throw ApiException.unprocessable("重复成员: " + key);
            }
        }
    }

    private void requireDirectMember(String parentKey, MemberType memberType, String memberKey) {
        String parent = memberRepository.findParentKey(memberType, memberKey)
                .orElseThrow(() -> ApiException.unprocessable("成员不在任何箱内: " + memberKey));
        if (!parent.equals(parentKey)) {
            throw ApiException.unprocessable("成员不属于该箱: " + memberKey);
        }
    }

    private void requirePackable(Evidence evidence) {
        switch (evidence.status()) {
            case SEALED -> {
            }
            case SEAL_BROKEN ->
                    throw ApiException.unprocessable("证物封条异常，禁止装箱: " + evidence.evidenceKey());
            case BORROWED ->
                    throw ApiException.conflict("证物借出中，禁止装箱: " + evidence.evidenceKey());
            case TRANSFER_PENDING ->
                    throw ApiException.conflict("证物待交接，禁止装箱: " + evidence.evidenceKey());
        }
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
        return new ContainerView(container.containerKey(), container.sealNo(), container.custodianId(),
                container.status(), container.sealStatus(), container.version(),
                container.createdAt(), container.updatedAt());
    }

    private ContainerTransferView toView(ContainerTransfer record) {
        return new ContainerTransferView(record.containerKey(), record.fromCustodian(),
                record.toCustodian(), record.status(), record.initiatedBy(),
                record.createdAt(), record.decidedAt());
    }

    private ContainerInspectionView toView(ContainerSealInspection inspection) {
        return new ContainerInspectionView(inspection.containerKey(), inspection.inspectorId(),
                inspection.passed(), inspection.note(), inspection.createdAt());
    }

    private EvidenceView toView(Evidence evidence) {
        return new EvidenceView(evidence.evidenceKey(), evidence.caseKey(), evidence.category(),
                evidence.sealNo(), evidence.custodianId(), evidence.status(),
                evidence.createdAt(), evidence.updatedAt());
    }
}
