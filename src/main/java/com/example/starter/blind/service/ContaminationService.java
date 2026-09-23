package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.ContaminationVersionView;
import com.example.starter.blind.dto.ContaminationView;
import com.example.starter.blind.dto.ExposureRecordView;
import com.example.starter.blind.dto.QuarantineOrderView;
import com.example.starter.blind.repo.ContaminationRepository;
import com.example.starter.blind.repo.ContaminationRepository.EdgeRow;
import com.example.starter.blind.repo.ContaminationRepository.QuarantineRow;
import com.example.starter.blind.repo.ContaminationRepository.SubjectRow;
import com.example.starter.blind.repo.ContaminationRepository.VersionRow;
import com.example.starter.blind.repo.UnblindRequestRepository;
import com.example.starter.blind.repo.UnblindRequestRepository.UnblindRequestRow;
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
 * 揭盲泄露传播、污染闭包版本与隔离门禁核心业务。
 *
 * <p>闭包定义：以被揭盲参与者为主体，边为「操作者 — 参与者」维度的有向披露关系，
 * 闭包为全部披露边接收端操作者的集合；边去重只增不删，任何视图都不含处理代码。
 *
 * <p>并发：所有变更先锁 contamination_subject 行，披露、审核门禁、隔离确认按提交顺序串行，
 * 门禁始终基于锁定后重算的最新闭包，无法用旧闭包绕过或覆盖新边。
 */
@Service
public class ContaminationService {

    private final ContaminationRepository contaminationRepository;
    private final UnblindRequestRepository unblindRequestRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ContaminationService(ContaminationRepository contaminationRepository,
                                UnblindRequestRepository unblindRequestRepository,
                                ObjectMapper objectMapper, Clock clock) {
        this.contaminationRepository = contaminationRepository;
        this.unblindRequestRepository = unblindRequestRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 揭盲批准门禁：拟批准人若已在目标参与者当前污染闭包中，不得作为该申请的新审核人。
     * 主体尚不存在（该参与者从未揭盲）时闭包为空，门禁放行。加入调用方事务。
     */
    @Transactional
    public void assertReviewerNotInClosure(String experimentId, String participantId,
                                           String reviewerActor) {
        SubjectRow subject =
                contaminationRepository.lockSubject(experimentId, participantId);
        if (subject != null && closureOf(subject.id()).contains(reviewerActor)) {
            throw ApiException.forbidden("审核人已在目标参与者的污染闭包中，不得作为新审核人");
        }
    }

    /**
     * 揭盲批准成功后登记持密种子边：批准即意味着申请人获知处理代码。
     * 首次批准创建主体与版本1；已有主体时为新申请人补种子边，重复不新增。
     * 加入调用方既有事务（批准与种子原子提交、整体回滚）。
     */
    @Transactional
    public void seedOnApproval(UnblindRequestRow approved, String reviewerActor) {
        long now = clock.nowMillis();
        SubjectRow subject =
                contaminationRepository.lockSubject(approved.experimentId(), approved.participantId());
        if (subject == null) {
            contaminationRepository.insertSubject(approved.experimentId(), approved.participantId(), 1);
            subject = contaminationRepository.lockSubject(approved.experimentId(),
                    approved.participantId());
            addEdgeIfAbsent(subject.id(), ContaminationRepository.SEED_SOURCE,
                    approved.applicantActor(), "SEED", reviewerActor, now);
            contaminationRepository.insertVersion(new VersionRow(0L, subject.id(), 1, "OPEN",
                    toJson(closureOf(subject.id())),
                    contaminationRepository.listEdges(subject.id()).size(), now, null, null));
            return;
        }
        VersionRow current =
                contaminationRepository.lockCurrentVersion(subject.id(), subject.currentVersion());
        if (addEdgeIfAbsent(subject.id(), ContaminationRepository.SEED_SOURCE,
                approved.applicantActor(), "SEED", reviewerActor, now)) {
            refreshAfterNewEdges(subject, current, now);
        }
    }

    /**
     * 持凭据登记调用者本人发起的直接披露。
     * <ul>
     *   <li>exposureKey 唯一确定已批准揭盲与目标参与者，且仅颁发给申请人本人；</li>
     *   <li>边的披露源强制为当前操作者，禁止伪造其他披露源；</li>
     *   <li>当前操作者必须已在闭包内（已获知处理代码），不得登记未获知的参与者；</li>
     *   <li>接收人去重后 1～20 名，向自己登记视为非法；重复边不新增。</li>
     * </ul>
     */
    @Transactional
    public ExposureRecordView record(String exposureKey, List<String> rawRecipients, String actorId) {
        if (exposureKey == null || exposureKey.isBlank()) {
            throw ApiException.badRequest("exposureKey 不能为空");
        }
        UnblindRequestRow approved =
                unblindRequestRepository.findApprovedByExposureKey(exposureKey);
        if (approved == null) {
            // 持密凭据无效或申请未批准：不暴露资源是否存在。
            throw ApiException.forbidden("exposureKey 无效或对应揭盲尚未批准");
        }
        if (!approved.applicantActor().equals(actorId)) {
            // 凭据只属于申请人本人，不能冒用他人凭据或以他人为披露源登记。
            throw ApiException.forbidden("仅揭盲申请人本人可使用其 exposureKey 登记自己发起的披露");
        }
        List<String> recipients = distinctRecipients(rawRecipients);
        return recordForSubject(approved.experimentId(), approved.participantId(),
                recipients, actorId);
    }

    /**
     * 接收人继续登记自己向更下游操作者的直接披露。
     * 不校验 exposureKey（接收人不持有），但当前操作者必须已在该参与者闭包内，
     * 从数据库闭包强制证明其已获知，禁止登记未获知的参与者或伪造披露源。
     */
    @Transactional
    public ExposureRecordView recordDownstream(String experimentId, String participantId,
                                               List<String> rawRecipients, String actorId) {
        List<String> recipients = distinctRecipients(rawRecipients);
        return recordForSubject(experimentId, participantId, recipients, actorId);
    }

    /**
     * 在锁定主体后，以当前操作者为披露源并入去重直接披露边并刷新版本；
     * 当前操作者不在闭包内即拒绝（未获知不得登记）。
     */
    private ExposureRecordView recordForSubject(String experimentId, String participantId,
                                                List<String> recipients, String actorId) {
        long now = clock.nowMillis();
        SubjectRow subject = contaminationRepository.lockSubject(experimentId, participantId);
        if (subject == null) {
            throw ApiException.notFound("该参与者尚无污染记录");
        }
        Set<String> closure = closureOf(subject.id());
        if (!closure.contains(actorId)) {
            // 不得登记自己未获知处理代码的参与者。
            throw ApiException.forbidden("你尚未获知该参与者的处理代码，不得登记披露");
        }
        VersionRow current =
                contaminationRepository.lockCurrentVersion(subject.id(), subject.currentVersion());
        int added = 0;
        for (String recipient : recipients) {
            if (recipient.equals(actorId)) {
                // 向自己登记披露没有传播意义，视为非法参数。
                throw ApiException.badRequest("不能把自己登记为披露接收人");
            }
            if (addEdgeIfAbsent(subject.id(), actorId, recipient, "DIRECT", actorId, now)) {
                added++;
            }
        }
        if (added > 0) {
            refreshAfterNewEdges(subject, current, now);
        }
        SubjectRow reloaded = contaminationRepository.lockSubject(experimentId, participantId);
        VersionRow latest =
                contaminationRepository.lockCurrentVersion(reloaded.id(), reloaded.currentVersion());
        return new ExposureRecordView(experimentId, participantId, actorId,
                added, latest.versionNo(), latest.edgeCount(), fromJson(latest.closure()));
    }

    // ---------------- 隔离单 ----------------

    /**
     * 合规负责人发起隔离单：提交目标参与者当前完整污染闭包与开放版本。
     * 题干只要求确认人是「另一名不在闭包内的负责人」，发起不做闭包成员限制；
     * 同一主体同一时间至多一个待确认隔离单（唯一稀疏占位兜底）。
     */
    @Transactional
    public QuarantineOrderView initiateQuarantine(String experimentId, String participantId,
                                                  String initiatorActor) {
        SubjectRow subject = lockExistingSubject(experimentId, participantId);
        Set<String> closure = closureOf(subject.id());
        VersionRow current =
                contaminationRepository.lockCurrentVersion(subject.id(), subject.currentVersion());
        if (!"OPEN".equals(current.status())) {
            throw ApiException.conflict("当前污染闭包版本已隔离关闭，等待新披露生成新版本后再发起");
        }
        if (contaminationRepository.findOpenQuarantineBySubject(subject.id()) != null) {
            throw ApiException.conflict("该参与者已存在待确认的隔离单");
        }
        String orderId = "QO-" + UUID.randomUUID().toString().replace("-", "");
        long now = clock.nowMillis();
        QuarantineRow row = new QuarantineRow(orderId, experimentId, participantId, subject.id(),
                current.versionNo(), current.id(), toJson(closure), initiatorActor, null,
                "OPEN", now, null);
        try {
            contaminationRepository.insertQuarantine(row);
        } catch (DuplicateKeyException e) {
            // 并发发起：唯一待确认占位兜底。
            throw ApiException.conflict("该参与者已存在待确认的隔离单");
        }
        return toQuarantineView(row, new ArrayList<>(closure));
    }

    /**
     * 另一名不在闭包内的合规负责人确认隔离：锁定主体后以最新闭包重新判定，
     * 禁止用旧闭包绕过；确认后冻结版本审计快照，不删除边。
     */
    @Transactional
    public QuarantineOrderView confirmQuarantine(String orderId, String confirmerActor) {
        QuarantineRow order = contaminationRepository.lockQuarantineById(orderId);
        if (order == null) {
            throw ApiException.notFound("隔离单不存在: " + orderId);
        }
        if ("CONFIRMED".equals(order.status())) {
            throw ApiException.conflict("隔离单已确认");
        }
        if (order.initiatorActor().equals(confirmerActor)) {
            throw ApiException.forbidden("必须由发起之外的另一名合规负责人确认隔离");
        }
        // 锁定主体，与新披露按提交顺序串行；以锁定后的最新闭包判定确认人。
        SubjectRow subject =
                contaminationRepository.lockSubject(order.experimentId(), order.participantId());
        Set<String> latestClosure = closureOf(subject.id());
        if (latestClosure.contains(confirmerActor)) {
            throw ApiException.forbidden("确认人已进入目标参与者的污染闭包，不能确认隔离");
        }
        long now = clock.nowMillis();
        int frozen = contaminationRepository.freezeVersion(order.versionId(), order.id(), now);
        if (frozen == 0) {
            throw ApiException.conflict("目标版本已关闭，不能重复隔离");
        }
        int confirmed = contaminationRepository.confirmQuarantine(order.id(), confirmerActor, now);
        if (confirmed == 0) {
            // 极端并发下被其他确认抢先；随事务回滚本次冻结，交给重试。
            throw ApiException.conflict("隔离单状态已变化，请刷新后重试");
        }
        return new QuarantineOrderView(order.id(), order.experimentId(), order.participantId(),
                order.versionNo(), fromJson(order.closureSnapshot()), order.initiatorActor(),
                confirmerActor, "CONFIRMED", order.createdAt(), now);
    }

    // ---------------- 查询（不含处理代码） ----------------

    /** 查询当前开放污染闭包与版本。 */
    public ContaminationView getContamination(String experimentId, String participantId) {
        SubjectRow subject = findExistingSubject(experimentId, participantId);
        VersionRow current =
                contaminationRepository.findVersionById(resolveCurrentVersionId(subject));
        return new ContaminationView(experimentId, participantId, current.versionNo(),
                current.status(), current.edgeCount(), fromJson(current.closure()));
    }

    /** 查询某参与者全部闭包版本（含已冻结审计快照），按版本升序。 */
    public List<ContaminationVersionView> listVersions(String experimentId, String participantId) {
        SubjectRow subject = findExistingSubject(experimentId, participantId);
        List<ContaminationVersionView> views = new ArrayList<>();
        for (VersionRow version : contaminationRepository.listVersionsBySubject(subject.id())) {
            views.add(new ContaminationVersionView(version.versionNo(), version.status(),
                    version.edgeCount(), fromJson(version.closure()), version.createdAt(),
                    version.frozenAt(), version.quarantineId()));
        }
        return views;
    }

    /** 查询某参与者隔离历史，按发起时间升序；从未隔离返回空列表。 */
    public List<QuarantineOrderView> listQuarantineOrders(String experimentId, String participantId) {
        SubjectRow subject = findExistingSubject(experimentId, participantId);
        List<QuarantineOrderView> views = new ArrayList<>();
        for (QuarantineRow order : contaminationRepository.listQuarantineBySubject(subject.id())) {
            views.add(toQuarantineView(order, fromJson(order.closureSnapshot())));
        }
        return views;
    }

    // ---------------- 内部辅助 ----------------

    private SubjectRow findExistingSubject(String experimentId, String participantId) {
        SubjectRow subject =
                contaminationRepository.findSubject(experimentId, participantId);
        if (subject == null) {
            throw ApiException.notFound("该参与者尚无污染记录");
        }
        return subject;
    }

    private SubjectRow lockExistingSubject(String experimentId, String participantId) {
        SubjectRow subject =
                contaminationRepository.lockSubject(experimentId, participantId);
        if (subject == null) {
            throw ApiException.notFound("该参与者尚无污染记录");
        }
        return subject;
    }

    private long resolveCurrentVersionId(SubjectRow subject) {
        List<VersionRow> versions =
                contaminationRepository.listVersionsBySubject(subject.id());
        return versions.stream()
                .filter(v -> v.versionNo() == subject.currentVersion())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("当前版本缺失，数据不一致"))
                .id();
    }

    private boolean addEdgeIfAbsent(long subjectId, String sourceActor, String targetActor,
                                    String edgeKind, String recordedBy, long now) {
        if (contaminationRepository.findEdge(subjectId, sourceActor, targetActor) != null) {
            return false;
        }
        contaminationRepository.insertEdge(new EdgeRow(0L, subjectId, sourceActor, targetActor,
                edgeKind, now));
        EdgeRow edge = contaminationRepository.findEdge(subjectId, sourceActor, targetActor);
        contaminationRepository.insertExposureRecord(subjectId, edge.id(), sourceActor, targetActor,
                recordedBy, now);
        return true;
    }

    private Set<String> closureOf(long subjectId) {
        Set<String> closure = new TreeSet<>();
        for (EdgeRow edge : contaminationRepository.listEdges(subjectId)) {
            closure.add(edge.targetActor());
        }
        return closure;
    }

    /**
     * 新边并入后刷新版本：当前版本仍开放且未被待确认隔离单占为快照时原地更新；
     * 否则（版本已冻结，或其已被待确认隔离单引用）另开新版本并重新 OPEN。
     */
    private void refreshAfterNewEdges(SubjectRow subject, VersionRow current, long now) {
        List<EdgeRow> allEdges = contaminationRepository.listEdges(subject.id());
        String closureJson = toJson(closureOf(subject.id()));
        QuarantineRow pendingOrder =
                contaminationRepository.findOpenQuarantineBySubject(subject.id());
        boolean pinnedByPendingOrder =
                pendingOrder != null && pendingOrder.versionId() == current.id();
        if ("OPEN".equals(current.status()) && !pinnedByPendingOrder) {
            contaminationRepository.updateOpenSnapshot(current.id(), allEdges.size(), closureJson);
            return;
        }
        int newVersionNo = subject.currentVersion() + 1;
        contaminationRepository.insertVersion(new VersionRow(0L, subject.id(), newVersionNo,
                "OPEN", closureJson, allEdges.size(), now, null, null));
        contaminationRepository.updateSubjectVersion(subject.id(), newVersionNo);
    }

    private QuarantineOrderView toQuarantineView(QuarantineRow row, List<String> closure) {
        return new QuarantineOrderView(row.id(), row.experimentId(), row.participantId(),
                row.versionNo(), closure, row.initiatorActor(), row.confirmerActor(), row.status(),
                row.createdAt(), row.confirmedAt());
    }

    private String toJson(Set<String> closure) {
        try {
            return objectMapper.writeValueAsString(new ArrayList<>(closure));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("闭包无法序列化", e);
        }
    }

    private List<String> fromJson(String closureJson) {
        try {
            return objectMapper.readValue(closureJson, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("闭包快照无法反序列化", e);
        }
    }

    /** 去重（保留确定性）、去空白、自披露非法、数量 1～20 的接收人规整；集合顺序不参与幂等判定。 */
    private List<String> distinctRecipients(List<String> rawRecipients) {
        if (rawRecipients == null || rawRecipients.isEmpty()) {
            throw ApiException.badRequest("至少登记 1 名接收操作者");
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String recipient : rawRecipients) {
            if (recipient == null || recipient.isBlank()) {
                throw ApiException.badRequest("接收操作者编号不能为空");
            }
            String trimmed = recipient.trim();
            if (trimmed.length() > 64) {
                throw ApiException.badRequest("接收操作者编号长度不能超过 64 个字符");
            }
            distinct.add(trimmed);
        }
        if (distinct.size() > 20) {
            throw ApiException.badRequest("单次登记的接收操作者不能超过 20 名");
        }
        return new ArrayList<>(distinct);
    }
}
