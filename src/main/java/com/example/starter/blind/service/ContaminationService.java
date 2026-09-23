package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.ContaminationView;
import com.example.starter.blind.dto.DisclosureView;
import com.example.starter.blind.dto.QuarantineOrderView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.ContaminationRepository;
import com.example.starter.blind.repo.ContaminationRepository.EdgeRow;
import com.example.starter.blind.repo.ContaminationRepository.VersionRow;
import com.example.starter.blind.repo.DisclosureEventRepository;
import com.example.starter.blind.repo.DisclosureEventRepository.DisclosureEventRow;
import com.example.starter.blind.repo.QuarantineRepository;
import com.example.starter.blind.repo.QuarantineRepository.QuarantineRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 揭盲泄露传播、污染闭包版本与隔离门禁业务：
 * <ul>
 *   <li>披露源只能登记本人向 1~20 名操作者直接披露其已获知参与者的处理代码，禁止伪造他人为源；</li>
 *   <li>按“操作者—参与者”有向边计算污染闭包并保存版本，重复边不新增，新增边生成新版本 OPEN；</li>
 *   <li>已在目标参与者闭包内的操作者不得作为揭盲申请的新审核人；</li>
 *   <li>合规负责人发起隔离单冻结闭包快照，另一名不在闭包内的负责人确认后关闭该版本；</li>
 *   <li>关闭只冻结审计快照，不删除边；所有对外视图均不含处理代码。</li>
 * </ul>
 * 同一参与者的边、版本与隔离变更均以其恒定存在的分配行为行级锁锚点串行化。
 */
@Service
public class ContaminationService {

    private static final int MAX_RECEIVERS = 20;

    private final ContaminationRepository contaminationRepository;
    private final DisclosureEventRepository disclosureEventRepository;
    private final QuarantineRepository quarantineRepository;
    private final AllocationRepository allocationRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ContaminationService(ContaminationRepository contaminationRepository,
                                DisclosureEventRepository disclosureEventRepository,
                                QuarantineRepository quarantineRepository,
                                AllocationRepository allocationRepository,
                                ObjectMapper objectMapper,
                                Clock clock) {
        this.contaminationRepository = contaminationRepository;
        this.disclosureEventRepository = disclosureEventRepository;
        this.quarantineRepository = quarantineRepository;
        this.allocationRepository = allocationRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 登记一次披露：sourceActor 把 participants 的处理代码直接披露给 receivers。
     * 多参与者在同一事务内处理，任一参与者校验失败整体回滚；重复边不新增。
     */
    @Transactional
    public DisclosureView registerDisclosure(String experimentId, String exposureKey,
                                             List<String> receivers, List<String> participants,
                                             String sourceActor) {
        if (exposureKey == null || exposureKey.isBlank()) {
            throw ApiException.badRequest("exposureKey 不能为空");
        }
        if (exposureKey.length() > 64) {
            throw ApiException.badRequest("exposureKey 最长 64 字符");
        }
        if (disclosureEventRepository.findByExposureKey(exposureKey) != null) {
            // exposureKey 全局唯一；重复登记拒绝，且不随 X-Request-Id 重放。
            throw ApiException.conflict("exposureKey 已用于其他披露登记");
        }
        List<String> receiverList = normalize(receivers, "receiverActors");
        List<String> participantList = normalize(participants, "participantIds");
        if (receiverList.isEmpty() || receiverList.size() > MAX_RECEIVERS) {
            throw ApiException.badRequest("接收操作者去重后须为 1~20 名");
        }
        if (receiverList.contains(sourceActor)) {
            throw ApiException.badRequest("不得向登记人本人登记披露");
        }
        if (participantList.isEmpty()) {
            throw ApiException.badRequest("participantIds 至少包含一个参与者");
        }

        long now = clock.nowMillis();
        int totalNewEdges = 0;
        // 按参与者编号排序加锁，避免跨参与者多事务交叉加锁导致死锁。
        for (String participantId : participantList) {
            AllocationRow allocation =
                    allocationRepository.lockByExperimentAndParticipant(experimentId, participantId);
            if (allocation == null) {
                throw ApiException.notFound("参与者尚未在该实验登记: " + participantId);
            }
            List<String> knownActors =
                    contaminationRepository.lockActorsByParticipant(experimentId, participantId);
            if (!knownActors.contains(sourceActor)) {
                // 不得登记未获知的参与者；污染记录也不扩大处理代码查询权限。
                throw ApiException.forbidden("登记人未获知该参与者的处理代码，不得登记披露: "
                        + participantId);
            }
            Set<String> closure = new TreeSet<>(knownActors);
            int newEdges = 0;
            for (String receiver : receiverList) {
                if (closure.add(receiver)) {
                    contaminationRepository.insertEdge(new EdgeRow(0L, experimentId, participantId,
                            receiver, sourceActor, exposureKey, now));
                    newEdges++;
                }
            }
            if (newEdges > 0) {
                appendVersion(experimentId, participantId, closure, now);
            }
            totalNewEdges += newEdges;
        }

        try {
            disclosureEventRepository.insert(new DisclosureEventRow(exposureKey, experimentId,
                    sourceActor, participantList.size(), receiverList.size(), totalNewEdges, now));
        } catch (DuplicateKeyException e) {
            // 并发使用同一 exposureKey：唯一约束兜底，整单回滚。
            throw ApiException.conflict("exposureKey 已用于其他披露登记");
        }
        return new DisclosureView(exposureKey, experimentId, sourceActor, receiverList,
                participantList, totalNewEdges, now);
    }

    /**
     * 揭盲批准后为申请人写入种子污染边并生成首个 OPEN 版本；种子边已存在时不新增（幂等）。
     */
    @Transactional
    public void seedApprovedApplicant(String experimentId, String participantId,
                                      String applicantActor) {
        AllocationRow allocation =
                allocationRepository.lockByExperimentAndParticipant(experimentId, participantId);
        if (allocation == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        long now = clock.nowMillis();
        Set<String> closure = new TreeSet<>(
                contaminationRepository.lockActorsByParticipant(experimentId, participantId));
        if (closure.add(applicantActor)) {
            // 种子边：来源标记为申请人本人，不关联任何 exposureKey。
            contaminationRepository.insertEdge(new EdgeRow(0L, experimentId, participantId,
                    applicantActor, applicantActor, null, now));
            appendVersion(experimentId, participantId, closure, now);
        }
    }

    /**
     * 审核隔离门禁：审核人不得在目标参与者当前污染闭包内；在闭包内返回 403。
     * 调用方须处于写事务内，本方法锁定分配行与边集，避免并发新边绕过门禁。
     */
    public void assertReviewerNotContaminated(String experimentId, String participantId,
                                              String reviewerActor) {
        AllocationRow allocation =
                allocationRepository.lockByExperimentAndParticipant(experimentId, participantId);
        if (allocation == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        List<String> actors =
                contaminationRepository.lockActorsByParticipant(experimentId, participantId);
        if (actors.contains(reviewerActor)) {
            throw ApiException.forbidden("审核人已在该参与者的污染闭包内，不得作为新审核人");
        }
    }

    /** 查询某参与者当前最新污染闭包（不含处理代码）。 */
    public ContaminationView getClosure(String experimentId, String participantId) {
        requireParticipant(experimentId, participantId);
        VersionRow latest = contaminationRepository.findLatestVersion(experimentId, participantId);
        if (latest == null) {
            throw ApiException.notFound("该参与者暂无污染闭包记录");
        }
        return toView(latest);
    }

    /** 查询某参与者全部闭包版本，按版本号升序。 */
    public List<ContaminationView> getVersions(String experimentId, String participantId) {
        requireParticipant(experimentId, participantId);
        List<ContaminationView> views = new ArrayList<>();
        for (VersionRow row : contaminationRepository.findVersions(experimentId, participantId)) {
            views.add(toView(row));
        }
        return views;
    }

    /**
     * 发起隔离单：提交当前完整闭包及版本；服务端在锁内重算并比对，
     * 闭包内容或版本已变化（旧闭包）时 409，防止用旧闭包绕过门禁。
     */
    @Transactional
    public QuarantineOrderView createQuarantine(String experimentId, String participantId,
                                                int submittedVersion, List<String> submittedActors,
                                                String initiatorActor) {
        if (submittedVersion < 1) {
            throw ApiException.badRequest("version 必须为正整数");
        }
        Set<String> submitted = new TreeSet<>(normalize(submittedActors, "actors"));
        if (submitted.isEmpty()) {
            throw ApiException.badRequest("actors 不能为空");
        }
        AllocationRow allocation =
                allocationRepository.lockByExperimentAndParticipant(experimentId, participantId);
        if (allocation == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        // 与披露登记保持一致的加锁顺序：分配行 → 边集 → 版本行，避免交叉死锁。
        Set<String> current = new TreeSet<>(
                contaminationRepository.lockActorsByParticipant(experimentId, participantId));
        VersionRow latest = contaminationRepository.lockLatestVersion(experimentId, participantId);
        if (latest == null) {
            throw ApiException.notFound("该参与者暂无污染闭包记录");
        }
        if (latest.version() != submittedVersion) {
            throw ApiException.conflict("提交的闭包版本已过期，请基于当前版本重新发起");
        }
        if (!"OPEN".equals(latest.status())) {
            throw ApiException.conflict("当前闭包版本已被隔离冻结，暂无新版本可发起隔离");
        }
        if (!current.equals(submitted)) {
            throw ApiException.conflict("提交的污染闭包与当前闭包不一致，可能已有新披露");
        }

        long now = clock.nowMillis();
        String orderId = "QO-" + UUID.randomUUID().toString().replace("-", "");
        String actorsJson = toJson(new ArrayList<>(current));
        try {
            quarantineRepository.insertOpen(new QuarantineRow(orderId, experimentId, participantId,
                    submittedVersion, "OPEN", initiatorActor, null, actorsJson, now, null,
                    experimentId + "|" + participantId));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("该参与者已存在待确认隔离单");
        }
        return new QuarantineOrderView(orderId, experimentId, participantId, submittedVersion,
                "OPEN", initiatorActor, null, new ArrayList<>(current), now, null);
    }

    /**
     * 确认隔离单：确认人必须是不同于发起人的另一名 COMPLIANCE，且不在冻结闭包内；
     * 确认后关闭对应版本快照，不删除边。
     */
    @Transactional
    public QuarantineOrderView confirmQuarantine(String orderId, String confirmerActor) {
        QuarantineRow order = quarantineRepository.lockById(orderId);
        if (order == null) {
            throw ApiException.notFound("隔离单不存在: " + orderId);
        }
        if ("CLOSED".equals(order.status())) {
            throw ApiException.conflict("隔离单已确认关闭");
        }
        if (order.initiatorActor().equals(confirmerActor)) {
            throw ApiException.forbidden("确认人必须是不同于发起人的另一名合规负责人");
        }
        List<String> frozenActors = fromJson(order.closureActorsJson());
        if (frozenActors.contains(confirmerActor)) {
            throw ApiException.forbidden("确认人在污染闭包内，不得确认该隔离单");
        }
        // 以分配行为锚点串行化，并锁定对应版本行后关闭，避免与并发披露互相覆盖。
        AllocationRow allocation = allocationRepository.lockByExperimentAndParticipant(
                order.experimentId(), order.participantId());
        if (allocation == null) {
            throw new IllegalStateException("隔离单对应分配缺失，数据不一致");
        }
        VersionRow version = contaminationRepository.lockLatestVersion(
                order.experimentId(), order.participantId());
        VersionRow target = findVersionByNumber(version, order);
        int closed = contaminationRepository.markVersionClosed(target.id());
        if (closed == 0) {
            // 并发已关闭该版本：隔离单仍 OPEN 属异常竞争，按冲突处理由调用方重试。
            throw ApiException.conflict("对应闭包版本状态已变化，请刷新后重试");
        }
        long now = clock.nowMillis();
        int updated = quarantineRepository.confirm(orderId, confirmerActor, now);
        if (updated == 0) {
            throw ApiException.conflict("隔离单状态已变化，请刷新后重试");
        }
        return new QuarantineOrderView(order.id(), order.experimentId(), order.participantId(),
                order.version(), "CLOSED", order.initiatorActor(), confirmerActor,
                frozenActors, order.createdAt(), now);
    }

    /** 查询某参与者隔离历史，按发起时间升序；不含处理代码。 */
    public List<QuarantineOrderView> getQuarantineHistory(String experimentId, String participantId) {
        requireParticipant(experimentId, participantId);
        List<QuarantineOrderView> views = new ArrayList<>();
        for (QuarantineRow row
                : quarantineRepository.findByParticipant(experimentId, participantId)) {
            views.add(new QuarantineOrderView(row.id(), row.experimentId(), row.participantId(),
                    row.version(), row.status(), row.initiatorActor(), row.confirmerActor(),
                    fromJson(row.closureActorsJson()), row.createdAt(), row.confirmedAt()));
        }
        return views;
    }

    /** 门禁判断：actorId 是否已在该参与者当前污染闭包内；无闭包返回 false。 */
    public boolean isInClosure(String experimentId, String participantId, String actorId) {
        return contaminationRepository.findActorsByParticipant(experimentId, participantId)
                .contains(actorId);
    }

    // ---------------- 内部辅助 ----------------

    /** 追加新版本：版本号 = 上一版本 + 1；无历史时从 1 开始，状态恒为 OPEN。 */
    private void appendVersion(String experimentId, String participantId,
                               Set<String> closureActors, long now) {
        VersionRow latest = contaminationRepository.lockLatestVersion(experimentId, participantId);
        int nextVersion = latest == null ? 1 : latest.version() + 1;
        contaminationRepository.insertVersion(new VersionRow(0L, experimentId, participantId,
                nextVersion, "OPEN", toJson(new ArrayList<>(closureActors)), now));
    }

    private VersionRow findVersionByNumber(VersionRow latest, QuarantineRow order) {
        if (latest != null && latest.version() == order.version()) {
            return latest;
        }
        List<VersionRow> versions = contaminationRepository.findVersions(
                order.experimentId(), order.participantId());
        for (VersionRow row : versions) {
            if (row.version() == order.version()) {
                return contaminationRepository.lockVersionById(row.id());
            }
        }
        throw new IllegalStateException("隔离单引用的闭包版本缺失，数据不一致");
    }

    private void requireParticipant(String experimentId, String participantId) {
        AllocationRow allocation =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        if (allocation == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
    }

    private ContaminationView toView(VersionRow row) {
        return new ContaminationView(row.experimentId(), row.participantId(), row.version(),
                row.status(), fromJson(row.actorsJson()), row.createdAt());
    }

    /** 去空白、去重并按字典序排序；出现空元素按请求参数错误处理。 */
    private static List<String> normalize(List<String> values, String field) {
        if (values == null) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw ApiException.badRequest(field + " 包含空元素");
            }
            String trimmed = value.trim();
            if (trimmed.length() > 64) {
                throw ApiException.badRequest(field + " 元素长度不能超过 64 个字符");
            }
            distinct.add(trimmed);
        }
        return new ArrayList<>(new TreeSet<>(distinct));
    }

    private String toJson(List<String> actors) {
        try {
            return objectMapper.writeValueAsString(actors);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("闭包操作者序列化失败", e);
        }
    }

    private List<String> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("闭包快照反序列化失败", e);
        }
    }
}
