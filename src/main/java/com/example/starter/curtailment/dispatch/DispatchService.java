package com.example.starter.curtailment.dispatch;

import com.example.starter.curtailment.commitment.Commitment;
import com.example.starter.curtailment.commitment.CommitmentRepository;
import com.example.starter.curtailment.common.Check;
import com.example.starter.curtailment.error.ApiException;
import com.example.starter.curtailment.idempotency.IdempotencyExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 削减调度业务：草稿创建与分配替换、发布（承诺匹配与容量校验）、取消、查询与历史。
 * 发布与暂停通过承诺行锁串行，状态迁移使用条件更新保证并发下按提交顺序生效。
 */
@Service
public class DispatchService {

    /** 单份调度允许的站点分配条数下限。 */
    public static final int MIN_ALLOCATIONS = 1;
    /** 单份调度允许的站点分配条数上限。 */
    public static final int MAX_ALLOCATIONS = 50;

    private final DispatchRepository dispatchRepository;
    private final CommitmentRepository commitmentRepository;
    private final IdempotencyExecutor idempotency;

    public DispatchService(DispatchRepository dispatchRepository, CommitmentRepository commitmentRepository,
                           IdempotencyExecutor idempotency) {
        this.dispatchRepository = dispatchRepository;
        this.commitmentRepository = commitmentRepository;
        this.idempotency = idempotency;
    }

    @Transactional
    public DispatchResponse create(CreateDispatchRequest request) {
        String commandKey = Check.requireText(request.commandKey(), "commandKey");
        String dispatchKey = Check.requireText(request.dispatchKey(), "dispatchKey");
        String feederId = Check.requireText(request.feederId(), "feederId");
        Check.requireInterval(request.executeFrom(), request.executeTo(), "executeFrom", "executeTo");
        BigDecimal targetPower = Check.parsePower(request.targetPowerKw(), "targetPowerKw", true);
        List<Allocation> allocations = validateAllocations(request.allocations(), targetPower);

        String fingerprint = "dispatchKey=" + dispatchKey
                + "|feederId=" + feederId
                + "|executeFrom=" + request.executeFrom()
                + "|executeTo=" + request.executeTo()
                + "|targetPowerKw=" + targetPower.stripTrailingZeros().toPlainString()
                + "|allocations=" + allocationsFingerprint(allocations);

        return idempotency.execute(commandKey, "CREATE_DISPATCH", fingerprint, DispatchResponse.class, () -> {
            Instant now = Instant.now();
            Dispatch toSave = new Dispatch(0L, dispatchKey, feederId, request.executeFrom(), request.executeTo(),
                    targetPower, 1, DispatchStatus.DRAFT, null, null, now, now);
            Dispatch saved;
            try {
                saved = dispatchRepository.insert(toSave);
            } catch (DuplicateKeyException ex) {
                throw ApiException.conflict("dispatchKey 已存在");
            }
            dispatchRepository.insertAllocations(saved.id(), allocations);
            dispatchRepository.insertEvent(saved.id(), "CREATED", saved.version(), now);
            return toResponse(saved, allocations);
        });
    }

    @Transactional
    public DispatchResponse replaceAllocations(String dispatchKey, ReplaceAllocationsRequest request) {
        String commandKey = Check.requireText(request.commandKey(), "commandKey");
        if (request.expectedVersion() == null) {
            throw ApiException.badRequest("expectedVersion 不能为空");
        }
        long expectedVersion = request.expectedVersion();

        return idempotency.execute(commandKey, "REPLACE_ALLOCATIONS",
                "dispatchKey=" + dispatchKey + "|expectedVersion=" + expectedVersion
                        + "|allocations=" + requestAllocationsFingerprint(request.allocations()),
                DispatchResponse.class, () -> {
                    Dispatch dispatch = dispatchRepository.findByKey(dispatchKey)
                            .orElseThrow(() -> ApiException.notFound("调度不存在"));
                    if (dispatch.status() != DispatchStatus.DRAFT) {
                        throw ApiException.conflict("仅草稿调度可替换分配");
                    }
                    if (dispatch.version() != expectedVersion) {
                        throw ApiException.conflict("版本不匹配，期望 " + expectedVersion
                                + "，当前 " + dispatch.version());
                    }
                    List<Allocation> allocations = validateAllocations(request.allocations(),
                            dispatch.targetPowerKw());
                    Instant now = Instant.now();
                    int newVersion = dispatch.version() + 1;
                    if (dispatchRepository.bumpDraftVersion(dispatch.id(), dispatch.version(), newVersion,
                            now) == 0) {
                        throw ApiException.conflict("调度已被并发修改，请刷新后重试");
                    }
                    dispatchRepository.replaceAllocations(dispatch.id(), allocations);
                    dispatchRepository.insertEvent(dispatch.id(), "ALLOCATIONS_REPLACED", newVersion, now);
                    Dispatch updated = new Dispatch(dispatch.id(), dispatch.dispatchKey(), dispatch.feederId(),
                            dispatch.executeFrom(), dispatch.executeTo(), dispatch.targetPowerKw(), newVersion,
                            dispatch.status(), dispatch.publishedAt(), dispatch.cancelledAt(),
                            dispatch.createdAt(), now);
                    return toResponse(updated, allocations);
                });
    }

    @Transactional
    public DispatchResponse publish(String dispatchKey, CommandRequest request) {
        String commandKey = Check.requireText(request.commandKey(), "commandKey");

        return idempotency.execute(commandKey, "PUBLISH_DISPATCH", "dispatchKey=" + dispatchKey,
                DispatchResponse.class, () -> {
                    Dispatch dispatch = dispatchRepository.findByKey(dispatchKey)
                            .orElseThrow(() -> ApiException.notFound("调度不存在"));
                    if (dispatch.status() != DispatchStatus.DRAFT) {
                        throw ApiException.conflict("仅草稿调度可发布");
                    }
                    List<Allocation> allocations = new ArrayList<>(
                            dispatchRepository.findAllocations(dispatch.id()));
                    validateAllocationsSum(allocations, dispatch.targetPowerKw());
                    // 按站点排序锁定承诺，避免并发发布死锁。
                    allocations.sort(Comparator.comparing(Allocation::siteId));
                    for (Allocation allocation : allocations) {
                        List<Commitment> covering = commitmentRepository.findActiveCoveringForUpdate(
                                allocation.siteId(), dispatch.executeFrom(), dispatch.executeTo());
                        if (covering.size() != 1) {
                            throw ApiException.capacity("站点 " + allocation.siteId()
                                    + " 没有唯一有效承诺完整覆盖执行区间");
                        }
                        Commitment commitment = covering.get(0);
                        BigDecimal used = dispatchRepository.sumPublishedOverlapPower(allocation.siteId(),
                                dispatch.executeFrom(), dispatch.executeTo());
                        if (used.add(allocation.powerKw()).compareTo(commitment.maxPowerKw()) > 0) {
                            throw ApiException.capacity("站点 " + allocation.siteId()
                                    + " 容量不足：已占用 " + Check.formatPower(used) + " kW，承诺上限 "
                                    + Check.formatPower(commitment.maxPowerKw()) + " kW");
                        }
                    }
                    Instant now = Instant.now();
                    int newVersion = dispatch.version() + 1;
                    if (dispatchRepository.markPublished(dispatch.id(), newVersion, now, now) == 0) {
                        throw ApiException.conflict("调度已被并发修改，请刷新后重试");
                    }
                    dispatchRepository.insertEvent(dispatch.id(), "PUBLISHED", newVersion, now);
                    Dispatch updated = new Dispatch(dispatch.id(), dispatch.dispatchKey(), dispatch.feederId(),
                            dispatch.executeFrom(), dispatch.executeTo(), dispatch.targetPowerKw(), newVersion,
                            DispatchStatus.PUBLISHED, now, dispatch.cancelledAt(), dispatch.createdAt(), now);
                    return toResponse(updated, allocations);
                });
    }

    @Transactional
    public DispatchResponse cancel(String dispatchKey, CommandRequest request) {
        String commandKey = Check.requireText(request.commandKey(), "commandKey");

        return idempotency.execute(commandKey, "CANCEL_DISPATCH", "dispatchKey=" + dispatchKey,
                DispatchResponse.class, () -> {
                    Dispatch dispatch = dispatchRepository.findByKey(dispatchKey)
                            .orElseThrow(() -> ApiException.notFound("调度不存在"));
                    Instant now = Instant.now();
                    int newVersion = dispatch.version() + 1;
                    if (dispatchRepository.markCancelled(dispatch.id(), newVersion, now, now) == 0) {
                        throw ApiException.conflict("仅已发布调度可取消");
                    }
                    // 取消仅翻转状态：容量随状态释放，原始分配与历史事件保留。
                    dispatchRepository.insertEvent(dispatch.id(), "CANCELLED", newVersion, now);
                    List<Allocation> allocations = dispatchRepository.findAllocations(dispatch.id());
                    Dispatch updated = new Dispatch(dispatch.id(), dispatch.dispatchKey(), dispatch.feederId(),
                            dispatch.executeFrom(), dispatch.executeTo(), dispatch.targetPowerKw(), newVersion,
                            DispatchStatus.CANCELLED, dispatch.publishedAt(), now, dispatch.createdAt(), now);
                    return toResponse(updated, allocations);
                });
    }

    @Transactional(readOnly = true)
    public DispatchResponse get(String dispatchKey) {
        Dispatch dispatch = dispatchRepository.findByKey(dispatchKey)
                .orElseThrow(() -> ApiException.notFound("调度不存在"));
        return toResponse(dispatch, dispatchRepository.findAllocations(dispatch.id()));
    }

    @Transactional(readOnly = true)
    public List<DispatchResponse> listPublished(String feederId) {
        String filter = feederId == null || feederId.isBlank() ? null : feederId.trim();
        return dispatchRepository.findPublished(filter).stream()
                .map(dispatch -> toResponse(dispatch, dispatchRepository.findAllocations(dispatch.id())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DispatchEventView> history(String dispatchKey) {
        Dispatch dispatch = dispatchRepository.findByKey(dispatchKey)
                .orElseThrow(() -> ApiException.notFound("调度不存在"));
        return dispatchRepository.findEvents(dispatch.id()).stream()
                .map(event -> new DispatchEventView(event.eventType(), event.version(), event.createdAt()))
                .toList();
    }

    /** 校验分配结构：1～50 条、站点唯一、功率大于零且最多 3 位小数、精确求和等于目标功率。 */
    private List<Allocation> validateAllocations(List<AllocationRequest> requests, BigDecimal targetPower) {
        if (requests == null || requests.size() < MIN_ALLOCATIONS || requests.size() > MAX_ALLOCATIONS) {
            throw ApiException.badRequest("站点分配条数须在 " + MIN_ALLOCATIONS + "～" + MAX_ALLOCATIONS + " 之间");
        }
        Set<String> sites = new HashSet<>();
        List<Allocation> allocations = new ArrayList<>(requests.size());
        for (AllocationRequest item : requests) {
            if (item == null) {
                throw ApiException.badRequest("站点分配不能包含空项");
            }
            String siteId = Check.requireText(item.siteId(), "allocations.siteId");
            if (!sites.add(siteId)) {
                throw ApiException.badRequest("同一站点至多一条分配：" + siteId);
            }
            BigDecimal power = Check.parsePower(item.powerKw(), "allocations.powerKw", true);
            allocations.add(new Allocation(0L, 0L, siteId, power));
        }
        validateAllocationsSum(allocations, targetPower);
        return allocations;
    }

    private void validateAllocationsSum(List<Allocation> allocations, BigDecimal targetPower) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Allocation allocation : allocations) {
            sum = sum.add(allocation.powerKw());
        }
        if (sum.compareTo(targetPower) != 0) {
            throw ApiException.badRequest("分配功率之和 " + Check.formatPower(sum)
                    + " 必须精确等于目标功率 " + Check.formatPower(targetPower));
        }
    }

    private String allocationsFingerprint(List<Allocation> allocations) {
        return allocations.stream()
                .map(a -> a.siteId() + ":" + a.powerKw().stripTrailingZeros().toPlainString())
                .sorted()
                .reduce("", (left, right) -> left + ";" + right);
    }

    private String requestAllocationsFingerprint(List<AllocationRequest> requests) {
        if (requests == null) {
            return "null";
        }
        return requests.stream()
                .map(item -> (item == null ? "null" : item.siteId() + ":" + item.powerKw()))
                .sorted()
                .reduce("", (left, right) -> left + ";" + right);
    }

    private DispatchResponse toResponse(Dispatch dispatch, List<Allocation> allocations) {
        List<AllocationView> views = allocations.stream()
                .map(a -> new AllocationView(a.siteId(), Check.formatPower(a.powerKw())))
                .toList();
        return new DispatchResponse(dispatch.dispatchKey(), dispatch.feederId(), dispatch.executeFrom(),
                dispatch.executeTo(), Check.formatPower(dispatch.targetPowerKw()), dispatch.version(),
                dispatch.status().name(), views, dispatch.publishedAt(), dispatch.cancelledAt(),
                dispatch.createdAt());
    }
}
