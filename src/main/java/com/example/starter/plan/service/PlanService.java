package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.Platform;
import com.example.starter.plan.model.PlatformSpan;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.PlatformRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.ConsistUpdateRequest;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PlatformRiskView;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RescheduleChainItem;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 铁路走廊日计划核心业务：草稿创建/整体替换、发布、取消、原子改签与查询。
 *
 * <p>并发与幂等约定：写操作按 (操作类型, requestKey) 幂等，同键同参重放返回首次成功结果，
 * 同键不同参返回 409；发布与改签经全局发布锁串行化，同一计划的更新/发布/取消/改签经行锁按事务提交顺序生效；
 * 仅成功结果写入幂等记录，失败（含 422 时隙冲突）不缓存、可修正后重试。
 */
@Service
public class PlanService {

    /** 运营日解释时区。 */
    public static final ZoneId OPERATION_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String OP_CREATE = "CREATE";
    private static final String OP_UPDATE = "UPDATE";
    private static final String OP_PUBLISH = "PUBLISH";
    private static final String OP_CANCEL = "CANCEL";
    private static final String OP_RESCHEDULE = "RESCHEDULE";
    private static final String OP_CONSIST = "CONSIST";

    private final PlanRepository planRepo;
    private final PlatformRepository platformRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public PlanService(PlanRepository planRepo, PlatformRepository platformRepo,
                       IdempotencyRepository idemRepo,
                       ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.platformRepo = platformRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建草稿计划（版本 1，状态 DRAFT）。
     */
    public PlanResponse createDraft(CreatePlanRequest req) {
        validateOccupancyParams(req.occupancies());
        validateWithinOperationDay(req.occupancies(), req.opDate());
        String hash = hashCreate(req);
        Optional<PlanResponse> replay = replayIfPresent(OP_CREATE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                // 并发下同键请求可能已提交：事务内先按幂等记录裁决（同参重放/异参 409）
                Optional<PlanResponse> replayInTx =
                        replayIfPresent(OP_CREATE, req.requestKey(), hash);
                if (replayInTx.isPresent()) {
                    return replayInTx.get();
                }
                if (planRepo.findByKey(req.scheduleKey()).isPresent()) {
                    throw conflict("SCHEDULE_KEY_EXISTS", "scheduleKey 已存在: " + req.scheduleKey());
                }
                long now = System.currentTimeMillis();
                long planId = planRepo.insertPlan(req.scheduleKey(), req.opDate(), PlanStatus.DRAFT, now);
                planRepo.insertOccupancies(planId, toOccupancies(planId, req.occupancies()));
                PlanResponse response = loadPlan(req.scheduleKey());
                idemRepo.insert(OP_CREATE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_CREATE, req.requestKey(), hash);
        }
    }

    /**
     * 整体替换草稿占用清单，版本加一；仅 DRAFT 可改，expectedVersion 必须匹配。
     */
    public PlanResponse replaceOccupancies(String scheduleKey, UpdateOccupanciesRequest req) {
        validateOccupancyParams(req.occupancies());
        String hash = hashUpdate(scheduleKey, req);
        Optional<PlanResponse> replay = replayIfPresent(OP_UPDATE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                if (plan.status() != PlanStatus.DRAFT) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅草稿可修改占用，当前状态: " + plan.status());
                }
                if (req.expectedVersion() != plan.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion() + " 与当前版本 " + plan.version() + " 不一致");
                }
                validateWithinOperationDay(req.occupancies(), plan.opDate());
                long now = System.currentTimeMillis();
                planRepo.replaceOccupancies(plan.id(), toOccupancies(plan.id(), req.occupancies()));
                planRepo.updateVersionAndStatus(plan.id(), plan.version() + 1, PlanStatus.DRAFT, now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_UPDATE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_UPDATE, req.requestKey(), hash);
        }
    }

    /**
     * 发布计划：全局发布锁内原子校验本计划列车重叠与跨计划区段重叠，
     * 任一冲突则整张计划保持草稿并抛出 422（携带冲突区段与计划）。
     */
    public PlanResponse publish(String scheduleKey, String requestKey) {
        return publish(scheduleKey, requestKey, null);
    }

    /**
     * 发布计划（携带操作者，计入幂等指纹）。除时隙冲突外，已登记编组的计划
     * 还须通过站台联合复核：编组长度不得超过每个停靠站台有效长度，
     * 且不得与其他已发布计划同站台窗口重叠，否则 422 并保持草稿。
     */
    public PlanResponse publish(String scheduleKey, String requestKey, String operator) {
        String hash = hashAction(OP_PUBLISH, scheduleKey, operator);
        Optional<PlanResponse> replay = replayIfPresent(OP_PUBLISH, requestKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                if (plan.status() != PlanStatus.DRAFT) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅草稿可发布，当前状态: " + plan.status());
                }
                List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(scheduleKey, occupancies));
                conflicts.addAll(findSectionConflicts(plan, occupancies, List.of(plan.id())));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "存在时隙冲突，计划保持草稿", conflicts);
                }
                List<Map<String, Object>> platformConflicts =
                        findPlatformConflicts(plan, occupancies, List.of(plan.id()));
                if (!platformConflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLATFORM_CONFLICT",
                            "编组与站台联合复核未通过，计划保持草稿", platformConflicts);
                }
                long now = System.currentTimeMillis();
                planRepo.updateStatus(plan.id(), PlanStatus.PUBLISHED, now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_PUBLISH, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_PUBLISH, requestKey, hash);
        }
    }

    /**
     * 取消已发布计划：时隙立即释放，历史计划与占用保留不改写。
     */
    public PlanResponse cancel(String scheduleKey, String requestKey) {
        return cancel(scheduleKey, requestKey, null);
    }

    /**
     * 取消已发布计划（携带操作者，计入幂等指纹）；未解除的站台风险随取消一并解除。
     */
    public PlanResponse cancel(String scheduleKey, String requestKey, String operator) {
        String hash = hashAction(OP_CANCEL, scheduleKey, operator);
        Optional<PlanResponse> replay = replayIfPresent(OP_CANCEL, requestKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                if (plan.status() != PlanStatus.PUBLISHED) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅已发布计划可取消，当前状态: " + plan.status());
                }
                long now = System.currentTimeMillis();
                planRepo.updateStatus(plan.id(), PlanStatus.CANCELLED, now);
                platformRepo.resolveOpenRisks(plan.id(), now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_CANCEL, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_CANCEL, requestKey, hash);
        }
    }

    /**
     * 登记或变更计划编组：车厢按编号去重并规范排序，站台代码去重排序且必须已存在；
     * 编组长度必须为正。携带 expectedVersion 乐观校验，成功版本加一。
     *
     * <p>已取消计划不可改（409）；无风险的已发布计划不可改（409）；被标记
     * PLATFORM_RISK 的已发布计划仅允许消除风险的变更——结果编组不得超过每个
     * 停靠站台的当前有效长度（即只能缩短编组或替换为合格站台），否则 422 且风险保留；
     * 变更合规后解除其全部未解除风险。草稿计划在发布时才做长度复核，此处仅校验站台存在。
     */
    public PlanResponse updateConsist(String scheduleKey, ConsistUpdateRequest req) {
        List<String> cars = normalizeCars(req.cars());
        List<String> platformCodes = normalizePlatforms(req.platformCodes());
        try {
            return tx.execute(status -> {
                // 与发布/改签/站台下调同一把全局锁，按事务提交顺序裁决，避免编组读取站台长度偏斜
                planRepo.acquirePublishLock();
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
                String hash = hashConsist(scheduleKey, req, cars, platformCodes, occupancies);
                Optional<PlanResponse> replay = replayIfPresent(OP_CONSIST, req.requestKey(), hash);
                if (replay.isPresent()) {
                    return replay.get();
                }
                if (plan.status() == PlanStatus.CANCELLED) {
                    throw conflict("PLAN_STATE_CONFLICT", "已取消计划不可变更编组");
                }
                boolean openRisk = platformRepo.hasOpenRisk(plan.id());
                if (plan.status() == PlanStatus.PUBLISHED && !openRisk) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅草稿或站台风险计划可变更编组，当前状态: " + plan.status());
                }
                if (req.expectedVersion() != plan.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion() + " 与当前版本 "
                                    + plan.version() + " 不一致");
                }
                for (String code : platformCodes) {
                    if (platformRepo.findByCode(code).isEmpty()) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "PLATFORM_NOT_FOUND",
                                "站台不存在: " + code);
                    }
                }
                if (openRisk) {
                    // 风险持续门禁：变更结果必须对每个停靠站台当前有效长度合规
                    List<Map<String, Object>> violations =
                            findLengthViolations(scheduleKey, req.consistLength(), platformCodes);
                    if (!violations.isEmpty()) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "PLATFORM_CONFLICT",
                                "风险计划仅允许缩短编组或替换为合格站台", violations);
                    }
                }
                long now = System.currentTimeMillis();
                planRepo.replaceConsist(plan.id(), req.consistLength(), cars, platformCodes,
                        plan.version() + 1, now);
                if (openRisk) {
                    platformRepo.resolveOpenRisks(plan.id(), now);
                }
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_CONSIST, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            // 并发同键：重算指纹（含当前占用时段）后按幂等记录裁决
            DayPlan plan = planRepo.findByKey(scheduleKey)
                    .orElseThrow(() -> notFound(scheduleKey));
            String hash = hashConsist(scheduleKey, req, cars, platformCodes,
                    planRepo.findOccupancies(plan.id()));
            return replayIfPresent(OP_CONSIST, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("IDEMPOTENT_KEY_REUSED",
                            "requestKey 已用于其他参数: " + req.requestKey()));
        }
    }

    /**
     * 查询计划的站台风险快照（含已解除），计划不存在返回 404。
     */
    public List<PlatformRiskView> getPlanRisks(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        return platformRepo.findRisksByPlan(plan.id()).stream()
                .map(r -> new PlatformRiskView(r.platformCode(), r.previousLength(), r.newLength(),
                        r.consistLength(), r.markedAt(), r.resolvedAt()))
                .toList();
    }

    /**
     * 原子改签：全局发布锁内校验新草稿（列车内部重叠与跨计划区段冲突，仅排除旧计划占用），
     * 通过后同一事务取消旧计划、发布新计划并追加不可变前后继关联。
     * 任一步失败整体回滚：旧计划仍发布、新计划仍草稿，版本、占用与关联均不改变。
     */
    public RescheduleResponse reschedule(String oldScheduleKey, RescheduleRequest req) {
        if (oldScheduleKey.equals(req.newScheduleKey())) {
            throw badRequest("新旧计划必须不同: " + oldScheduleKey);
        }
        String hash = hashReschedule(oldScheduleKey, req);
        Optional<RescheduleResponse> replay =
                replayIfPresent(OP_RESCHEDULE, req.requestKey(), hash, RescheduleResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                DayPlan oldPlan = planRepo.findByKeyForUpdate(oldScheduleKey)
                        .orElseThrow(() -> notFound(oldScheduleKey));
                DayPlan newPlan = planRepo.findByKeyForUpdate(req.newScheduleKey())
                        .orElseThrow(() -> notFound(req.newScheduleKey()));
                if (oldPlan.status() != PlanStatus.PUBLISHED) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅已发布计划可改签，旧计划当前状态: " + oldPlan.status());
                }
                if (newPlan.status() != PlanStatus.DRAFT) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "改签新计划必须为草稿，当前状态: " + newPlan.status());
                }
                if (req.expectedOldVersion() != oldPlan.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedOldVersion=" + req.expectedOldVersion()
                                    + " 与旧计划当前版本 " + oldPlan.version() + " 不一致");
                }
                if (req.expectedNewVersion() != newPlan.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedNewVersion=" + req.expectedNewVersion()
                                    + " 与新计划当前版本 " + newPlan.version() + " 不一致");
                }
                if (!oldPlan.opDate().equals(newPlan.opDate())) {
                    throw conflict("OP_DATE_MISMATCH",
                            "新旧计划运营日必须相同: 旧=" + oldPlan.opDate() + " 新=" + newPlan.opDate());
                }
                if (planRepo.findLinkByPredecessor(oldPlan.id()).isPresent()) {
                    throw conflict("LINK_CONFLICT", "旧计划已存在直接后继: " + oldScheduleKey);
                }
                if (planRepo.findLinkBySuccessor(newPlan.id()).isPresent()) {
                    throw conflict("LINK_CONFLICT", "新计划已存在直接前驱: " + req.newScheduleKey());
                }
                List<Occupancy> occupancies = planRepo.findOccupancies(newPlan.id());
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(req.newScheduleKey(), occupancies));
                // 仅排除旧计划与自身占用，第三方已发布计划照常参与冲突裁决
                conflicts.addAll(findSectionConflicts(newPlan, occupancies,
                        List.of(newPlan.id(), oldPlan.id())));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "新草稿存在时隙冲突，改签未生效", conflicts);
                }
                // 按最终状态复核新计划编组与站台（旧计划占用随取消释放，不再参与裁决）；
                // 任一超长或同站台重叠即 422，整批不写入
                List<Map<String, Object>> platformConflicts =
                        findPlatformConflicts(newPlan, occupancies,
                                List.of(newPlan.id(), oldPlan.id()));
                if (!platformConflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLATFORM_CONFLICT",
                            "编组与站台联合复核未通过，改签整批未写入", platformConflicts);
                }
                long now = System.currentTimeMillis();
                planRepo.updateStatus(oldPlan.id(), PlanStatus.CANCELLED, now);
                planRepo.updateStatus(newPlan.id(), PlanStatus.PUBLISHED, now);
                planRepo.insertRescheduleLink(oldPlan.id(), newPlan.id(), now);
                RescheduleResponse response = new RescheduleResponse(
                        loadPlan(oldScheduleKey), loadPlan(req.newScheduleKey()));
                idemRepo.insert(OP_RESCHEDULE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_RESCHEDULE, req.requestKey(), hash, RescheduleResponse.class)
                    .orElseThrow(() -> conflict("LINK_CONFLICT", "改签前后继关联冲突"));
        }
    }

    /**
     * 查询包含指定计划在内的完整有序改签链（从最前驱到最后继）；
     * 无改签历史的计划链中仅含自身。计划不存在返回 404。
     */
    public RescheduleChainResponse getRescheduleChain(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<DayPlan> predecessors = new ArrayList<>();
        DayPlan cursor = plan;
        while (true) {
            Optional<RescheduleLink> link = planRepo.findLinkBySuccessor(cursor.id());
            if (link.isEmpty()) {
                break;
            }
            cursor = planRepo.findById(link.get().predecessorPlanId())
                    .orElseThrow(() -> new IllegalStateException("改签链前驱缺失: " + link.get()));
            predecessors.add(cursor);
        }
        Collections.reverse(predecessors);
        List<DayPlan> chain = new ArrayList<>(predecessors);
        chain.add(plan);
        cursor = plan;
        while (true) {
            Optional<RescheduleLink> link = planRepo.findLinkByPredecessor(cursor.id());
            if (link.isEmpty()) {
                break;
            }
            cursor = planRepo.findById(link.get().successorPlanId())
                    .orElseThrow(() -> new IllegalStateException("改签链后继缺失: " + link.get()));
            chain.add(cursor);
        }
        List<RescheduleChainItem> items = chain.stream()
                .map(p -> new RescheduleChainItem(p.scheduleKey(), p.opDate(), p.version(),
                        p.status().name()))
                .toList();
        return new RescheduleChainResponse(scheduleKey, items);
    }

    /**
     * 按计划业务键查询明细（含历史占用），不存在返回 404。
     */
    public PlanResponse getPlan(String scheduleKey) {
        if (planRepo.findByKey(scheduleKey).isEmpty()) {
            throw notFound(scheduleKey);
        }
        return loadPlan(scheduleKey);
    }

    /**
     * 查询指定运营日与区段上当前已发布的生效时隙。
     */
    public List<PublishedSlotView> getPublishedSlots(LocalDate opDate, String sectionId) {
        return planRepo.findPublishedSlots(opDate, List.of(sectionId), List.of(-1L)).stream()
                .map(s -> new PublishedSlotView(s.scheduleKey(), s.trainNo(), s.sectionId(),
                        s.startUtc(), s.endUtc()))
                .toList();
    }

    // ---------- 内部实现 ----------

    private PlanResponse loadPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<OccupancyView> views = planRepo.findOccupancies(plan.id()).stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                .toList();
        List<String> cars = planRepo.findCarNos(plan.id());
        List<String> platformCodes = planRepo.findPlatformCodes(plan.id());
        boolean platformRisk = platformRepo.hasOpenRisk(plan.id());
        return new PlanResponse(plan.scheduleKey(), plan.opDate(), plan.version(),
                plan.status().name(), views, plan.consistLength(), cars, platformCodes,
                platformRisk);
    }

    private List<Occupancy> toOccupancies(long planId, List<OccupancyRequest> requests) {
        List<Occupancy> result = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            OccupancyRequest r = requests.get(i);
            result.add(new Occupancy(0L, planId, i, r.trainNo(), r.sectionId(), r.startUtc(), r.endUtc()));
        }
        return result;
    }

    /**
     * 参数级校验：结束必须晚于开始，且起止落在同一 Asia/Shanghai 日历日内。
     */
    private void validateOccupancyParams(List<OccupancyRequest> occupancies) {
        for (int i = 0; i < occupancies.size(); i++) {
            OccupancyRequest o = occupancies.get(i);
            if (!o.endUtc().isAfter(o.startUtc())) {
                throw badRequest("第 " + i + " 条占用结束时刻必须晚于开始时刻");
            }
            LocalDate startDay = o.startUtc().atZone(OPERATION_ZONE).toLocalDate();
            LocalDate endDay = o.endUtc().minusNanos(1).atZone(OPERATION_ZONE).toLocalDate();
            if (!startDay.equals(endDay)) {
                throw badRequest("第 " + i + " 条占用必须落在同一运营日（Asia/Shanghai）内");
            }
        }
    }

    /**
     * 占用必须落在计划运营日内：[start, end) 完全包含于运营日 [dayStart, dayEnd)。
     */
    private void validateWithinOperationDay(List<OccupancyRequest> occupancies, LocalDate opDate) {
        Instant dayStart = opDate.atStartOfDay(OPERATION_ZONE).toInstant();
        Instant dayEnd = opDate.plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
        for (int i = 0; i < occupancies.size(); i++) {
            OccupancyRequest o = occupancies.get(i);
            if (o.startUtc().isBefore(dayStart) || o.endUtc().isAfter(dayEnd)) {
                throw badRequest("第 " + i + " 条占用不在运营日 " + opDate + "（Asia/Shanghai）内");
            }
        }
    }

    /**
     * 本计划内同一列车的重叠占用检测（左闭右开，相邻合法）。
     */
    private List<Map<String, Object>> findTrainOverlaps(String scheduleKey, List<Occupancy> occupancies) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Map<String, List<Occupancy>> byTrain = new LinkedHashMap<>();
        for (Occupancy o : occupancies) {
            byTrain.computeIfAbsent(o.trainNo(), k -> new ArrayList<>()).add(o);
        }
        byTrain.forEach((trainNo, list) -> {
            List<Occupancy> sorted = list.stream()
                    .sorted(Comparator.comparing(Occupancy::startUtc)).toList();
            for (int i = 1; i < sorted.size(); i++) {
                Occupancy prev = sorted.get(i - 1);
                Occupancy cur = sorted.get(i);
                if (cur.startUtc().isBefore(prev.endUtc())) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "TRAIN_OVERLAP");
                    detail.put("scheduleKey", scheduleKey);
                    detail.put("trainNo", trainNo);
                    detail.put("sectionId", cur.sectionId());
                    detail.put("startUtc", cur.startUtc().toString());
                    detail.put("endUtc", cur.endUtc().toString());
                    conflicts.add(detail);
                }
            }
        });
        return conflicts;
    }

    /**
     * 与其他已发布计划在同日期、同区段上的重叠检测（左闭右开，相邻合法）。
     *
     * @param excludePlanIds 检测时排除的计划 id（发布为自身；改签为新旧两个计划）
     */
    private List<Map<String, Object>> findSectionConflicts(DayPlan plan, List<Occupancy> occupancies,
                                                           List<Long> excludePlanIds) {
        List<String> sectionIds = occupancies.stream()
                .map(Occupancy::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
        List<PublishedSlot> published = planRepo.findPublishedSlots(plan.opDate(), sectionIds,
                excludePlanIds);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Occupancy o : occupancies) {
            for (PublishedSlot slot : published) {
                if (!o.sectionId().equals(slot.sectionId())) {
                    continue;
                }
                boolean overlap = o.startUtc().isBefore(slot.endUtc())
                        && slot.startUtc().isBefore(o.endUtc());
                if (overlap) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "SECTION_CONFLICT");
                    detail.put("sectionId", o.sectionId());
                    detail.put("scheduleKey", plan.scheduleKey());
                    detail.put("conflictingScheduleKey", slot.scheduleKey());
                    detail.put("trainNo", o.trainNo());
                    detail.put("startUtc", o.startUtc().toString());
                    detail.put("endUtc", o.endUtc().toString());
                    conflicts.add(detail);
                }
            }
        }
        return conflicts;
    }

    /**
     * 编组与站台联合复核：仅对已登记编组的计划生效。编组长度不得超过每个停靠站台的
     * 当前有效长度；计划占用整体跨度不得与其他已发布计划（排除 excludePlanIds）
     * 在同一站台的占用窗口重叠（左闭右开，相邻合法）。
     *
     * @param excludePlanIds 检测时排除的计划 id（发布为自身；改签为新旧两个计划）
     */
    private List<Map<String, Object>> findPlatformConflicts(DayPlan plan,
                                                            List<Occupancy> occupancies,
                                                            List<Long> excludePlanIds) {
        if (plan.consistLength() == null) {
            return List.of();
        }
        List<String> platformCodes = planRepo.findPlatformCodes(plan.id());
        List<Map<String, Object>> conflicts = new ArrayList<>(
                findLengthViolations(plan.scheduleKey(), plan.consistLength(), platformCodes));
        if (platformCodes.isEmpty() || occupancies.isEmpty()) {
            return conflicts;
        }
        List<PlatformSpan> others = platformRepo.findPublishedPlatformSpans(plan.opDate(),
                platformCodes, excludePlanIds);
        String span = spanOf(occupancies);
        long start = Long.parseLong(span.substring(0, span.indexOf('/')));
        long end = Long.parseLong(span.substring(span.indexOf('/') + 1));
        Instant startUtc = Instant.ofEpochMilli(start);
        Instant endUtc = Instant.ofEpochMilli(end);
        for (PlatformSpan other : others) {
            boolean overlap = startUtc.isBefore(other.endUtc())
                    && other.startUtc().isBefore(endUtc);
            if (overlap) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "PLATFORM_OVERLAP");
                detail.put("platformCode", other.platformCode());
                detail.put("scheduleKey", plan.scheduleKey());
                detail.put("conflictingScheduleKey", other.scheduleKey());
                detail.put("startUtc", startUtc.toString());
                detail.put("endUtc", endUtc.toString());
                conflicts.add(detail);
            }
        }
        return conflicts;
    }

    /**
     * 编组长度对每个停靠站台当前有效长度的超限明细。
     */
    private List<Map<String, Object>> findLengthViolations(String scheduleKey, int consistLength,
                                                           List<String> platformCodes) {
        List<Map<String, Object>> violations = new ArrayList<>();
        for (String code : platformCodes) {
            Optional<Platform> platform = platformRepo.findByCode(code);
            if (platform.isPresent() && consistLength > platform.get().effectiveLength()) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "PLATFORM_LENGTH_EXCEEDED");
                detail.put("platformCode", code);
                detail.put("scheduleKey", scheduleKey);
                detail.put("consistLength", consistLength);
                detail.put("platformLength", platform.get().effectiveLength());
                violations.add(detail);
            }
        }
        return violations;
    }

    /**
     * 计划占用整体跨度："startMillis/endMillis"（左闭右开）。
     */
    private String spanOf(List<Occupancy> occupancies) {
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        for (Occupancy o : occupancies) {
            start = Math.min(start, o.startUtc().toEpochMilli());
            end = Math.max(end, o.endUtc().toEpochMilli());
        }
        return start + "/" + end;
    }

    /**
     * 车厢编号规范化：去空白、去重，纯数字按数值排序，其余按字典序（数字优先）。
     */
    private List<String> normalizeCars(List<String> cars) {
        return cars.stream()
                .map(String::trim)
                .distinct()
                .sorted((a, b) -> {
                    boolean aNumeric = a.chars().allMatch(Character::isDigit);
                    boolean bNumeric = b.chars().allMatch(Character::isDigit);
                    if (aNumeric && bNumeric) {
                        int byValue = Long.compare(Long.parseLong(a), Long.parseLong(b));
                        return byValue != 0 ? byValue : a.compareTo(b);
                    }
                    if (aNumeric != bNumeric) {
                        return aNumeric ? -1 : 1;
                    }
                    return a.compareTo(b);
                })
                .toList();
    }

    /**
     * 站台代码规范化：去空白、去重、按字典序排序。
     */
    private List<String> normalizePlatforms(List<String> platformCodes) {
        return platformCodes.stream()
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private Optional<PlanResponse> replayIfPresent(String opType, String requestKey, String hash) {
        return replayIfPresent(opType, requestKey, hash, PlanResponse.class);
    }

    /**
     * 幂等重放（泛型）：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private <T> Optional<T> replayIfPresent(String opType, String requestKey, String hash,
                                            Class<T> type) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson(), type);
        });
    }

    /**
     * 并发下唯一键冲突后的裁决：若为同键重放返回首次结果，否则说明 scheduleKey 冲突。
     */
    private PlanResponse resolveDuplicate(String opType, String requestKey, String hash) {
        return replayIfPresent(opType, requestKey, hash)
                .orElseThrow(() -> conflict("SCHEDULE_KEY_EXISTS", "scheduleKey 已存在"));
    }

    private String hashCreate(CreatePlanRequest req) {
        StringBuilder sb = new StringBuilder(OP_CREATE).append('\n')
                .append(req.scheduleKey()).append('\n').append(req.opDate());
        appendOccupancies(sb, req.occupancies());
        return sha256(sb.toString());
    }

    private String hashUpdate(String scheduleKey, UpdateOccupanciesRequest req) {
        StringBuilder sb = new StringBuilder(OP_UPDATE).append('\n')
                .append(scheduleKey).append('\n').append(req.expectedVersion());
        appendOccupancies(sb, req.occupancies());
        return sha256(sb.toString());
    }

    private String hashAction(String opType, String scheduleKey, String operator) {
        return sha256(opType + '\n' + scheduleKey + '\n' + (operator == null ? "" : operator));
    }

    private String hashReschedule(String oldScheduleKey, RescheduleRequest req) {
        return sha256(OP_RESCHEDULE + '\n' + oldScheduleKey + '\n' + req.newScheduleKey()
                + '\n' + req.expectedOldVersion() + '\n' + req.expectedNewVersion()
                + '\n' + (req.operator() == null ? "" : req.operator()));
    }

    /**
     * 编组变更幂等指纹：操作者、计划业务键、计划版本、编组长度、规范化车厢、
     * 规范化站台与计划当前占用时段（整体跨度）。
     */
    private String hashConsist(String scheduleKey, ConsistUpdateRequest req, List<String> cars,
                               List<String> platformCodes, List<Occupancy> occupancies) {
        StringBuilder sb = new StringBuilder(OP_CONSIST).append('\n')
                .append(req.operator()).append('\n')
                .append(scheduleKey).append('\n')
                .append(req.expectedVersion()).append('\n')
                .append(req.consistLength()).append('\n')
                .append(String.join(",", cars)).append('\n')
                .append(String.join(",", platformCodes)).append('\n')
                .append(spanOf(occupancies));
        return sha256(sb.toString());
    }

    private void appendOccupancies(StringBuilder sb, List<OccupancyRequest> occupancies) {
        for (OccupancyRequest o : occupancies) {
            sb.append('\n').append(o.trainNo()).append('|').append(o.sectionId()).append('|')
                    .append(o.startUtc().toEpochMilli()).append('|').append(o.endUtc().toEpochMilli());
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private ApiException notFound(String scheduleKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                "计划不存在: " + scheduleKey);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
