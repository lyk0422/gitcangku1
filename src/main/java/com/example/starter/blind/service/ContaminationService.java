package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.ClosureVersionView;
import com.example.starter.blind.dto.ClosureView;
import com.example.starter.blind.dto.DisclosureView;
import com.example.starter.blind.dto.QuarantineOrderView;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.DisclosureRepository;
import com.example.starter.blind.repo.DisclosureRepository.ClosureVersionRow;
import com.example.starter.blind.repo.DisclosureRepository.EdgeRow;
import com.example.starter.blind.repo.DisclosureRepository.EventRow;
import com.example.starter.blind.repo.QuarantineRepository;
import com.example.starter.blind.repo.QuarantineRepository.QuarantineRow;
import com.example.starter.blind.repo.UnblindRequestRepository;
import com.example.starter.blind.repo.UnblindRequestRepository.UnblindRequestRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 泄露传播、污染闭包与审核隔离核心业务：
 * <ul>
 *   <li>只有已获知某参与者处理代码的操作者（已批准揭盲申请人，或闭包内下游接收人）
 *       才能以本人为来源登记披露，单次 1~20 名接收人；</li>
 *   <li>披露形成“操作者—参与者”有向边，重复边不新增；exposureKey 全局唯一；</li>
 *   <li>每次提交后按最新边重新计算闭包并追加 OPEN 版本，版本只追加不覆盖；</li>
 *   <li>新审核人若在闭包内则禁止批准；隔离确认只冻结版本快照，不删除边；</li>
 *   <li>同一参与者维度的写操作先锁分配行，按提交顺序串行，禁止旧闭包绕过门禁。</li>
 * </ul>
 * 本服务任何视图与日志均不含处理代码。
 */
@Service
public class ContaminationService {

    private static final int MAX_TARGETS = 20;

    private final DisclosureRepository disclosureRepository;
    private final QuarantineRepository quarantineRepository;
    private final UnblindRequestRepository unblindRequestRepository;
    private final AllocationRepository allocationRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ContaminationService(DisclosureRepository disclosureRepository,
                                QuarantineRepository quarantineRepository,
                                UnblindRequestRepository unblindRequestRepository,
                                AllocationRepository allocationRepository,
                                ObjectMapper objectMapper,
                                Clock clock) {
        this.disclosureRepository = disclosureRepository;
        this.quarantineRepository = quarantineRepository;
        this.unblindRequestRepository = unblindRequestRepository;
        this.allocationRepository = allocationRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 规范化接收人列表：去空白、按首次出现顺序去重；数量限制 1~20。
     */
    public static List<String> normalizeTargets(List<String> targets) {
        if (targets == null || targets.isEmpty()) {
            throw ApiException.badRequest("targetActorIds 必须包含 1~20 名接收人");
        }
        if (targets.size() > MAX_TARGETS) {
            throw ApiException.badRequest("单次登记接收人不得超过 " + MAX_TARGETS + " 名");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String target : targets) {
            if (target == null || target.isBlank()) {
                throw ApiException.badRequest("targetActorId 不能为空");
            }
            String trimmed = target.trim();
            if (trimmed.length() > 64) {
                throw ApiException.badRequest("targetActorId 最长 64 字符");
            }
            normalized.add(trimmed);
        }
        if (normalized.isEmpty()) {
            throw ApiException.badRequest("targetActorIds 必须包含 1~20 名接收人");
        }
        return new ArrayList<>(normalized);
    }

    /**
     * 登记一次直接披露并生成闭包新版本。
     */
    @Transactional
    public DisclosureView disclose(String experimentId, String participantId,
                                   String exposureKey, List<String> rawTargets,
                                   String sourceActor, String requestId) {
        if (exposureKey == null || exposureKey.isBlank()) {
            throw ApiException.badRequest("exposureKey 不能为空");
        }
        List<String> targets = normalizeTargets(rawTargets);
        if (targets.contains(sourceActor)) {
            throw ApiException.badRequest("不得向自己登记披露");
        }

        // 参与者维度串行点：锁分配行后，边与版本读取在同一事务内完成。
        AllocationRow allocation =
                allocationRepository.lockByExperimentAndParticipant(experimentId, participantId);
        if (allocation == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }

        Set<String> closureBefore = computeClosure(experimentId, participantId);
        if (closureBefore.isEmpty()) {
            // 尚无已批准揭盲：没有任何人获知过处理代码。
            throw ApiException.forbidden("该参与者尚无已批准揭盲，不能登记披露");
        }
        if (!closureBefore.contains(sourceActor)) {
            // 来源既不是已批准揭盲申请人，也不是闭包内下游接收人：禁止伪造披露源。
            throw ApiException.forbidden("来源操作者未获知该参与者处理代码，不能登记披露");
        }

        // exposureKey 全局唯一：任何第二次使用（含同参新 requestId）均冲突；
        // 同 requestId 重放在进入本服务前由幂等层回放，不会到达此处。
        if (disclosureRepository.findEvent(exposureKey) != null) {
            throw ApiException.conflict("exposureKey 已被使用");
        }
        long now = clock.nowMillis();
        try {
            disclosureRepository.insertEvent(new EventRow(
                    exposureKey, experimentId, requestId, sourceActor, now));
        } catch (DuplicateKeyException e) {
            if (disclosureRepository.isDuplicateExposureKey(e)) {
                throw ApiException.conflict("exposureKey 已被使用");
            }
            throw e;
        }

        int newEdgeCount = 0;
        int duplicateEdgeCount = 0;
        for (String target : targets) {
            try {
                disclosureRepository.insertEdge(new EdgeRow(
                        0L, experimentId, participantId, sourceActor, target, exposureKey, now));
                newEdgeCount++;
            } catch (DuplicateKeyException e) {
                if (disclosureRepository.isDuplicateEdge(e)) {
                    // 边重复不新增、不报错；事件仍记录本次登记。
                    duplicateEdgeCount++;
                } else {
                    throw e;
                }
            }
        }

        List<EdgeRow> edges = disclosureRepository.lockEdges(experimentId, participantId);
        Set<String> closureAfter = computeClosure(experimentId, participantId, edges);
        int versionNo;
        if (newEdgeCount > 0) {
            // 仅当边集合确有新增才追加版本；全部为重复边时闭包与边均未变化，复用当前版本。
            versionNo = appendVersion(experimentId, participantId, closureAfter,
                    edges.size(), now);
        } else {
            ClosureVersionRow latest =
                    disclosureRepository.lockLatestVersion(experimentId, participantId);
            if (latest == null) {
                // 理论不可达：存在重复边意味着此前已生成版本。
                throw new IllegalStateException("重复边存在但缺少闭包版本，数据不一致");
            }
            versionNo = latest.versionNo();
        }

        return new DisclosureView(exposureKey, experimentId, participantId, sourceActor,
                targets, newEdgeCount, duplicateEdgeCount, versionNo,
                List.copyOf(closureAfter), now);
    }

    /**
     * 审核隔离门禁：拟批准人在目标参与者污染闭包内时禁止作为新审核人。
     * 必须在揭盲申请行锁之后、同一事务内调用。
     */
    public void assertReviewerNotContaminated(String experimentId, String participantId,
                                              String reviewerActor) {
        AllocationRow allocation =
                allocationRepository.lockByExperimentAndParticipant(experimentId, participantId);
        if (allocation == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        Set<String> closure = computeClosure(experimentId, participantId);
        if (closure.contains(reviewerActor)) {
            throw ApiException.forbidden("审核人已在该参与者污染闭包内，不能作为新审核人");
        }
    }

    /**
     * 揭盲批准成功后刷新闭包版本：新的已批准申请人是新的闭包根节点，
     * 若已有版本且闭包集合因新根发生变化，追加一个 OPEN 新版本
     * （原 CLOSED 快照保持冻结）；尚无任何版本时不预生成版本。
     * 调用方须持有揭盲申请行锁与参与者分配行锁。
     */
    public void refreshVersionAfterApproval(String experimentId, String participantId) {
        ClosureVersionRow latest =
                disclosureRepository.lockLatestVersion(experimentId, participantId);
        if (latest == null) {
            return;
        }
        Set<String> live = computeClosure(experimentId, participantId);
        Set<String> snapshot = new TreeSet<>(readActors(latest.actorsJson()));
        if (!live.equals(snapshot)) {
            appendVersion(experimentId, participantId, live,
                    disclosureRepository.countEdges(experimentId, participantId),
                    clock.nowMillis());
        }
    }

    /**
     * 查询当前污染闭包（不含处理代码）；无披露版本时返回已批准揭盲申请人根集合。
     */
    public ClosureView getClosure(String experimentId, String participantId) {
        mustFindParticipant(experimentId, participantId);
        ClosureVersionRow latest =
                disclosureRepository.findLatestVersion(experimentId, participantId);
        int edgeCount = disclosureRepository.countEdges(experimentId, participantId);
        if (latest == null) {
            List<String> roots = approvedApplicants(experimentId, participantId).stream()
                    .sorted().toList();
            return new ClosureView(experimentId, participantId, null, null,
                    roots, 0, null, null);
        }
        return new ClosureView(experimentId, participantId, latest.versionNo(), latest.status(),
                readActors(latest.actorsJson()), edgeCount, latest.createdAt(), latest.closedAt());
    }

    /** 查询单个闭包版本；不含处理代码。 */
    public ClosureVersionView getVersion(String experimentId, String participantId, int versionNo) {
        mustFindParticipant(experimentId, participantId);
        ClosureVersionRow row =
                disclosureRepository.findVersion(experimentId, participantId, versionNo);
        if (row == null) {
            throw ApiException.notFound("闭包版本不存在: " + versionNo);
        }
        return toVersionView(row);
    }

    /** 查询某参与者全部闭包版本（按版本号升序）。 */
    public List<ClosureVersionView> listVersions(String experimentId, String participantId) {
        mustFindParticipant(experimentId, participantId);
        return disclosureRepository.findVersions(experimentId, participantId).stream()
                .map(this::toVersionView).toList();
    }

    /**
     * 合规负责人发起隔离单：提交当前版本号与完整闭包，过期或不一致均拒绝。
     */
    @Transactional
    public QuarantineOrderView initiateQuarantine(String experimentId, String participantId,
                                                  Integer submittedVersionNo,
                                                  List<String> submittedActors,
                                                  String initiatorActor) {
        if (submittedVersionNo == null) {
            throw ApiException.badRequest("versionNo 不能为空");
        }
        List<String> submitted = normalizeActorSnapshot(submittedActors);

        AllocationRow allocation =
                allocationRepository.lockByExperimentAndParticipant(experimentId, participantId);
        if (allocation == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        ClosureVersionRow latest =
                disclosureRepository.lockLatestVersion(experimentId, participantId);
        if (latest == null) {
            throw ApiException.conflict("该参与者尚无闭包版本，无法发起隔离");
        }
        if (latest.versionNo() != submittedVersionNo) {
            throw ApiException.conflict("提交的闭包版本不是当前版本");
        }
        if ("CLOSED".equals(latest.status())) {
            // 版本冻结后须有新增披露生成新版本并重新 OPEN，才能再次发起隔离。
            throw ApiException.conflict("当前闭包版本已冻结，新增披露后重新开放");
        }
        Set<String> current = new TreeSet<>(readActors(latest.actorsJson()));
        if (!current.equals(new TreeSet<>(submitted))) {
            throw ApiException.conflict("提交的闭包与当前完整污染闭包不一致");
        }

        long now = clock.nowMillis();
        String orderId = "Q-" + UUID.randomUUID().toString().replace("-", "");
        String snapshotJson = writeJson(Map.of(
                "versionNo", latest.versionNo(),
                "actors", List.copyOf(current),
                "edgeCount", latest.edgeCount()));
        quarantineRepository.insert(new QuarantineRow(orderId, experimentId, participantId,
                latest.id(), latest.versionNo(), initiatorActor, null, "OPEN",
                snapshotJson, now, null));
        return new QuarantineOrderView(orderId, experimentId, participantId, latest.versionNo(),
                initiatorActor, null, "OPEN", List.copyOf(current), latest.edgeCount(), now, null);
    }

    /**
     * 另一名不在闭包内的合规负责人确认隔离单：冻结提交版本快照，不删除边。
     */
    @Transactional
    public QuarantineOrderView confirmQuarantine(String orderId, String confirmerActor) {
        QuarantineRow order = quarantineRepository.lockById(orderId);
        if (order == null) {
            throw ApiException.notFound("隔离单不存在: " + orderId);
        }
        if ("CONFIRMED".equals(order.status())) {
            throw ApiException.conflict("隔离单已确认");
        }
        if (order.initiatorActor().equals(confirmerActor)) {
            throw ApiException.forbidden("确认人必须是不同于发起人的另一名合规负责人");
        }

        // 锁参与者分配行，与并发披露/门禁按提交顺序串行；闭包按确认时刻最新边计算。
        AllocationRow allocation =
                allocationRepository.lockByExperimentAndParticipant(
                        order.experimentId(), order.participantId());
        if (allocation == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        Set<String> liveClosure =
                computeClosure(order.experimentId(), order.participantId());
        if (liveClosure.contains(confirmerActor)) {
            throw ApiException.forbidden("确认人已在该参与者污染闭包内，不能确认隔离单");
        }

        ClosureVersionRow version =
                disclosureRepository.lockVersionById(order.versionId());
        if (version == null) {
            throw new IllegalStateException("隔离单引用的闭包版本缺失，数据不一致");
        }
        long now = clock.nowMillis();
        int closed = disclosureRepository.markClosed(version.id(), now);
        if (closed == 0) {
            throw ApiException.conflict("提交的闭包版本已被冻结");
        }
        quarantineRepository.confirm(orderId, confirmerActor, now);

        Snapshot snapshot = readSnapshot(order.snapshotJson());
        return new QuarantineOrderView(orderId, order.experimentId(), order.participantId(),
                order.versionNo(), order.initiatorActor(), confirmerActor, "CONFIRMED",
                snapshot.actors(), snapshot.edgeCount(), order.createdAt(), now);
    }

    /** 查询单个隔离单；不含处理代码。 */
    public QuarantineOrderView getQuarantine(String orderId) {
        QuarantineRow row = quarantineRepository.findById(orderId);
        if (row == null) {
            throw ApiException.notFound("隔离单不存在: " + orderId);
        }
        return toOrderView(row);
    }

    /** 查询某参与者隔离历史（按发起时间升序）；不含处理代码。 */
    public List<QuarantineOrderView> listQuarantineHistory(String experimentId, String participantId) {
        mustFindParticipant(experimentId, participantId);
        return quarantineRepository.findByParticipant(experimentId, participantId).stream()
                .map(this::toOrderView).toList();
    }

    // ---------------- 闭包计算 ----------------

    /**
     * 计算污染闭包：以全部已批准揭盲申请人为根，沿有向边传播。
     * 调用方须已持有参与者分配行锁。
     */
    private Set<String> computeClosure(String experimentId, String participantId) {
        List<EdgeRow> edges = disclosureRepository.lockEdges(experimentId, participantId);
        return computeClosure(experimentId, participantId, edges);
    }

    /**
     * 基于给定边计算闭包（边已在事务内锁定或为刚插入的最新集合）。
     */
    private Set<String> computeClosure(String experimentId, String participantId,
                                       List<EdgeRow> edges) {
        TreeSet<String> closure = new TreeSet<>(approvedApplicants(experimentId, participantId));
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (EdgeRow edge : edges) {
            adjacency.computeIfAbsent(edge.sourceActor(), k -> new ArrayList<>()).add(edge.targetActor());
        }
        // 固定点传播：从根集合出发沿出边扩张，直到无新增。
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String actor : List.copyOf(closure)) {
                List<String> downstream = adjacency.get(actor);
                if (downstream != null) {
                    for (String target : downstream) {
                        if (closure.add(target)) {
                            changed = true;
                        }
                    }
                }
            }
        }
        return closure;
    }

    private List<String> approvedApplicants(String experimentId, String participantId) {
        return unblindRequestRepository.findApprovedByParticipant(experimentId, participantId)
                .stream().map(UnblindRequestRow::applicantActor).distinct().toList();
    }

    private int appendVersion(String experimentId, String participantId,
                              Set<String> actors, int edgeCount, long now) {
        ClosureVersionRow latest =
                disclosureRepository.lockLatestVersion(experimentId, participantId);
        int nextVersionNo = latest == null ? 1 : latest.versionNo() + 1;
        disclosureRepository.insertVersion(new ClosureVersionRow(
                0L, experimentId, participantId, nextVersionNo, "OPEN",
                writeJson(List.copyOf(actors)), edgeCount, now, null));
        return nextVersionNo;
    }

    // ---------------- 序列化与视图辅助 ----------------

    private AllocationRow mustFindParticipant(String experimentId, String participantId) {
        AllocationRow allocation =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        if (allocation == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        return allocation;
    }

    private List<String> normalizeActorSnapshot(List<String> actors) {
        if (actors == null || actors.isEmpty()) {
            throw ApiException.badRequest("actors 不能为空");
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String actor : actors) {
            if (actor == null || actor.isBlank()) {
                throw ApiException.badRequest("actors 含空操作者编号");
            }
            if (actor.trim().length() > 64) {
                throw ApiException.badRequest("actor 最长 64 字符");
            }
            sorted.add(actor.trim());
        }
        return List.copyOf(sorted);
    }

    private ClosureVersionView toVersionView(ClosureVersionRow row) {
        return new ClosureVersionView(row.experimentId(), row.participantId(), row.versionNo(),
                row.status(), readActors(row.actorsJson()), row.edgeCount(),
                row.createdAt(), row.closedAt());
    }

    private QuarantineOrderView toOrderView(QuarantineRow row) {
        Snapshot snapshot = readSnapshot(row.snapshotJson());
        return new QuarantineOrderView(row.id(), row.experimentId(), row.participantId(),
                row.versionNo(), row.initiatorActor(), row.confirmerActor(), row.status(),
                snapshot.actors(), snapshot.edgeCount(), row.createdAt(), row.confirmedAt());
    }

    private List<String> readActors(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("闭包操作者快照无法解析", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("闭包快照无法序列化", e);
        }
    }

    private Snapshot readSnapshot(String json) {
        try {
            JsonTree tree = objectMapper.readValue(json, JsonTree.class);
            return new Snapshot(tree.actors() == null ? List.of() : tree.actors(),
                    tree.edgeCount() == null ? 0 : tree.edgeCount());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("隔离快照无法解析", e);
        }
    }

    /** 隔离单快照 JSON 结构。 */
    private record JsonTree(Integer versionNo, List<String> actors, Integer edgeCount) {
    }

    private record Snapshot(List<String> actors, int edgeCount) {
    }
}
