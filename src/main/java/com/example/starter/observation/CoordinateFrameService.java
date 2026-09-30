package com.example.starter.observation;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 带版本的坐标基准转换业务服务。
 *
 * <p>规则发布与观测写入共用固定守卫行锁：两类事务在单事务内先取锁再读写，
 * 因此观测按其自身事务提交时已生效的规则版本裁决，目标 X/Y 必然来自同一条规则，
 * 不会出现半旧半新坐标。规则发布后不可变，历史观测冻结规则版本与转换结果，
 * 事后调整规则不会重新解释已有观测。
 *
 * <p>规则选择按观测 UTC 时刻匹配左闭右开区间：无规则或同时命中多条规则均返回 422 且不入库。
 * 目标坐标为原坐标加偏移并按六位小数 HALF_UP 保存；再按站点矩形 X∈[0,10]、Y∈[0,30]
 * （边界点视为站内）判定：站内容许并标记 AUTO_MERGED 参加自动合并，站外有效观测保留为
 * PENDING_REVIEW，不参加自动合并。
 *
 * <p>同一 requestId 重放返回首次坐标与状态；修改 observationId、原基准、原坐标或观测时刻一律 409；
 * 失败（含 422）整体回滚，不占用 requestId，也不产生观测行。
 */
@Service
public class CoordinateFrameService {

    private final CoordinateRuleRepository ruleRepository;
    private final CoordinateObservationRepository observationRepository;

    public CoordinateFrameService(CoordinateRuleRepository ruleRepository,
                                  CoordinateObservationRepository observationRepository) {
        this.ruleRepository = ruleRepository;
        this.observationRepository = observationRepository;
    }

    /**
     * 发布新版本转换规则（不可变）。生效终点非空时必须严格晚于起点；版本号重复返回 409。
     */
    @Transactional
    public RuleResponse publishRule(PublishRuleRequest request) {
        if (request.effectiveToUtc() != null
                && !request.effectiveToUtc().isAfter(request.effectiveFromUtc())) {
            throw ApiException.badRequest("effectiveToUtc must be after effectiveFromUtc");
        }
        // 与观测写入串行化：先取得守卫行锁再落规则。
        observationRepository.lockGuard();
        CoordinateRule rule = new CoordinateRule(
                request.ruleVersion(), request.targetFrame(),
                request.offsetX(), request.offsetY(),
                request.effectiveFromUtc(), request.effectiveToUtc());
        try {
            ruleRepository.insert(rule);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("rule version already exists: " + request.ruleVersion(), null);
        }
        return RuleResponse.of(rule);
    }

    /**
     * 写入一条坐标观测：选择提交时唯一生效的规则完成转换与站内外判定并冻结结果。
     * 无规则或命中多条规则返回 422 且不入库；requestId 同参重放首次结果，改参 409。
     */
    @Transactional
    public CoordinateObservationResponse ingest(CoordinateObservationRequest request) {
        // 与规则发布串行化：事务内持锁读取生效规则并写入观测，以提交时有效版本裁决。
        observationRepository.lockGuard();

        CoordinateObservation existing = observationRepository.findByRequestId(request.requestId()).orElse(null);
        if (existing != null) {
            if (!sameIngestParams(existing, request)) {
                throw ApiException.conflict(
                        "requestId reused with different source frame or coordinates: " + request.requestId(), null);
            }
            return CoordinateObservationResponse.of(existing);
        }

        List<CoordinateRule> rules = ruleRepository.findEffectiveAt(request.observedAtUtc());
        if (rules.isEmpty()) {
            throw ApiException.unprocessableEntity(
                    "no coordinate transformation rule effective at observed time: " + request.observedAtUtc());
        }
        if (rules.size() > 1) {
            throw ApiException.unprocessableEntity(
                    "multiple coordinate transformation rules effective at observed time: "
                            + request.observedAtUtc() + ", rule versions="
                            + rules.stream().map(rule -> String.valueOf(rule.ruleVersion())).toList());
        }
        CoordinateRule rule = rules.get(0);

        BigDecimal targetX = CoordinateFrames.convert(request.originalX(), rule.offsetX());
        BigDecimal targetY = CoordinateFrames.convert(request.originalY(), rule.offsetY());
        boolean withinSite = CoordinateFrames.withinSite(targetX, targetY);
        CoordinateObservation observation = new CoordinateObservation(
                request.observationId(), request.requestId(), request.sourceFrame(),
                request.originalX(), request.originalY(), request.observedAtUtc(),
                rule.targetFrame(), targetX, targetY, rule.ruleVersion(),
                withinSite ? ReviewStatus.AUTO_MERGED : ReviewStatus.PENDING_REVIEW,
                withinSite);

        try {
            observationRepository.insert(observation);
        } catch (DuplicateKeyException e) {
            // 并发下唯一键由数据库裁决：同 requestId 胜者按同参重放/改参 409；否则 observationId 已占用。
            CoordinateObservation winner = observationRepository.findByRequestId(request.requestId())
                    .or(() -> observationRepository.findByObservationId(request.observationId()))
                    .orElseThrow(() -> ApiException.conflict("coordinate observation write conflict", null));
            if (winner.requestId().equals(request.requestId())) {
                if (!sameIngestParams(winner, request)) {
                    throw ApiException.conflict(
                            "requestId reused with different source frame or coordinates: " + request.requestId(), null);
                }
                return CoordinateObservationResponse.of(winner);
            }
            throw ApiException.conflict(
                    "coordinate observation already exists: " + request.observationId(), null);
        }
        return CoordinateObservationResponse.of(observation);
    }

    /**
     * 按业务标识查询坐标观测（含原坐标、目标坐标、规则版本与复核状态）；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public CoordinateObservationResponse getObservation(String observationId) {
        return observationRepository.findByObservationId(observationId)
                .map(CoordinateObservationResponse::of)
                .orElseThrow(() -> ApiException.notFound(
                        "coordinate observation not found: " + observationId));
    }

    /**
     * 按版本号查询转换规则；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public RuleResponse getRule(int ruleVersion) {
        return ruleRepository.findByVersion(ruleVersion)
                .map(RuleResponse::of)
                .orElseThrow(() -> ApiException.notFound("coordinate rule not found: v" + ruleVersion));
    }

    /**
     * 判定重放请求与首次落库参数是否一致：坐标按数值比较（1.0 与 1.00 视为相同）；
     * observationId、原基准、原坐标或观测时刻任一不同即改参 409。
     */
    private boolean sameIngestParams(CoordinateObservation stored, CoordinateObservationRequest request) {
        return stored.observationId().equals(request.observationId())
                && stored.sourceFrame().equals(request.sourceFrame())
                && stored.originalX().compareTo(request.originalX()) == 0
                && stored.originalY().compareTo(request.originalY()) == 0
                && stored.observedAtUtc().equals(request.observedAtUtc());
    }
}
