package com.example.starter.baggage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.example.starter.baggage.BaggageDtos.ClaimHoldDetail;
import com.example.starter.baggage.BaggageDtos.ClaimHoldEventItem;
import com.example.starter.baggage.BaggageDtos.ClaimHoldHistoryResponse;
import com.example.starter.baggage.BaggageDtos.ClaimHoldListResponse;
import com.example.starter.baggage.BaggageDtos.ClaimHoldRequest;
import com.example.starter.baggage.BaggageDtos.ClaimHoldResponse;
import com.example.starter.baggage.BaggageDtos.ClaimReleaseRequest;
import com.example.starter.baggage.BaggageDtos.ClaimReleaseResponse;
import com.example.starter.baggage.BaggageDtos.ClaimReviewRequest;
import com.example.starter.baggage.BaggageDtos.ClaimReviewResponse;

/**
 * 行李认领冻结核心：冻结登记、异人复核、二次确认解除与明细/历史/诊断查询。
 * 所有写操作经 {@link IdempotencyService} 去重，业务变更与去重记录原子提交；
 * 冻结与装载/封舱按提交顺序裁决：锁顺序与装载一致（先航段后行李），
 * 封舱先提交则冻结 409，冻结先提交则装载 409（由 BaggageService 状态检查保证）。
 * 冻结/复核/解除/清单移出写入 claim_hold_event 不可变链记录，只插入不更新；
 * 解除后不改写冻结前后航段历史，认领记录永不删除。
 */
@Service
public class ClaimHoldService {

    private static final String LEG_OPEN = "OPEN";
    private static final String BAG_CLAIM_HOLD = "CLAIM_HOLD";
    private static final String BAG_DELIVERED = "DELIVERED";
    private static final String BAG_LOST = "LOST";

    private static final String HOLD_ACTIVE = "ACTIVE";
    private static final String HOLD_REVIEWED = "REVIEWED";
    private static final String HOLD_RELEASED = "RELEASED";

    private static final String EVT_MANIFEST_REMOVED = "MANIFEST_REMOVED";
    private static final String EVT_HOLD = "HOLD";
    private static final String EVT_REVIEW = "REVIEW";
    private static final String EVT_RELEASE = "RELEASE";

    private static final String BAG_EVT_HELD = "CLAIM_HELD";
    private static final String BAG_EVT_RELEASED = "CLAIM_RELEASED";

    private static final RowMapper<HoldRow> HOLD_MAPPER = (rs, rowNum) -> new HoldRow(
            rs.getString("claim_key"), rs.getString("bag_tag"), rs.getString("status"),
            rs.getString("reason"), rs.getString("verify_digest"), rs.getString("prior_status"),
            rs.getString("removed_leg_id"), rs.getString("held_by"),
            getInstant(rs, "held_at"), rs.getString("reviewed_by"), getInstant(rs, "reviewed_at"),
            rs.getString("released_by"), getInstant(rs, "released_at"));

    private static final RowMapper<ClaimHoldEventItem> EVENT_MAPPER = (rs, rowNum) ->
            new ClaimHoldEventItem(rs.getInt("seq"), rs.getString("event_type"),
                    rs.getString("claim_key"), rs.getString("bag_tag"),
                    rs.getString("leg_id"), rs.getString("reason"), rs.getString("actor"),
                    getInstant(rs, "event_time").toString());

    private final JdbcTemplate jdbcTemplate;
    private final IdempotencyService idempotencyService;
    /** 可控时钟，默认系统 UTC 时钟，测试可替换。 */
    private Supplier<Instant> clock = Instant::now;

    public ClaimHoldService(JdbcTemplate jdbcTemplate, IdempotencyService idempotencyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.idempotencyService = idempotencyService;
    }

    /** 替换时钟（测试使用），所有登记时刻均取该时钟的 UTC 时刻。 */
    void setClock(Supplier<Instant> clock) {
        this.clock = clock;
    }

    /**
     * 登记认领冻结：未到达最终目的地的行李进入 CLAIM_HOLD。
     * 若行李位于 OPEN 航段清单，同一事务内先移出清单（航段版本递增）再写冻结链记录；
     * 已装载在 SEALED 航段、已交付或已丢失行李返回 409；同一行李同时只能一个生效冻结。
     */
    public ClaimHoldResponse hold(String bagTag, ClaimHoldRequest request) {
        HoldPayload payload = new HoldPayload(bagTag, request.claimKey(), request.verifyDigest(),
                request.reason(), request.operator());
        try {
            return idempotencyService.execute(request.requestId(), "CLAIM_HOLD", 201,
                    payload, ClaimHoldResponse.class, () -> doHold(bagTag, request));
        } catch (DuplicateKeyException duplicate) {
            // claim_key 唯一索引兜底：并发同号冻结单只有一个能提交
            throw ApiException.conflict("认领冻结单号已存在: " + request.claimKey());
        }
    }

    /**
     * 复核：须由与冻结登记人不同的客服提交，核验摘要不匹配返回 422（不占 requestId 键）。
     */
    public ClaimReviewResponse review(String claimKey, ClaimReviewRequest request) {
        ReviewPayload payload = new ReviewPayload(claimKey, request.verifyDigest(), request.operator());
        return idempotencyService.execute(request.requestId(), "CLAIM_REVIEW", 200,
                payload, ClaimReviewResponse.class, () -> doReview(claimKey, request));
    }

    /**
     * 解除确认（第二次确认）：须由复核客服本人提交，原子恢复行李为冻结前可交接状态。
     * 冻结期间被拒绝的装载请求不会自动恢复，调用方须重提并按当前状态校验。
     */
    public ClaimReleaseResponse release(String claimKey, ClaimReleaseRequest request) {
        ReleasePayload payload = new ReleasePayload(claimKey, request.operator());
        return idempotencyService.execute(request.requestId(), "CLAIM_RELEASE", 200,
                payload, ClaimReleaseResponse.class, () -> doRelease(claimKey, request));
    }

    /** 行李当前生效冻结单明细查询（ACTIVE/REVIEWED），无生效冻结返回 404。 */
    public ClaimHoldDetail getActiveHold(String bagTag) {
        List<HoldRow> rows = jdbcTemplate.query(holdSelect(false)
                        + " AND bag_tag = ? AND status IN (?, ?)",
                HOLD_MAPPER, bagTag, HOLD_ACTIVE, HOLD_REVIEWED);
        if (rows.isEmpty()) {
            if (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bag WHERE bag_tag = ?",
                    Integer.class, bagTag) == 0) {
                throw ApiException.notFound("行李不存在: " + bagTag);
            }
            throw ApiException.notFound("行李 " + bagTag + " 当前无生效的认领冻结");
        }
        return toDetail(rows.get(0));
    }

    /** 按冻结单号查询明细（含已解除），不存在返回 404。 */
    public ClaimHoldDetail getHold(String claimKey) {
        HoldRow row = findHold(claimKey);
        if (row == null) {
            throw ApiException.notFound("认领冻结单不存在: " + claimKey);
        }
        return toDetail(row);
    }

    /** 行李冻结链历史查询：全部冻结单的不可变链记录，按插入顺序（id 升序）稳定排序。 */
    public ClaimHoldHistoryResponse getHoldHistory(String bagTag) {
        if (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bag WHERE bag_tag = ?",
                Integer.class, bagTag) == 0) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        List<ClaimHoldEventItem> events = jdbcTemplate.query(
                "SELECT seq, event_type, claim_key, bag_tag, leg_id, reason, actor, event_time"
                        + " FROM claim_hold_event WHERE bag_tag = ? ORDER BY id",
                EVENT_MAPPER, bagTag);
        return new ClaimHoldHistoryResponse(bagTag, events);
    }

    /** 冻结单诊断清单：可按状态过滤，按冻结时刻与单号稳定排序；读取不改变状态。 */
    public ClaimHoldListResponse listHolds(String status) {
        List<HoldRow> rows;
        if (status == null || status.isBlank()) {
            rows = jdbcTemplate.query(holdSelect(false) + " ORDER BY held_at, claim_key", HOLD_MAPPER);
        } else {
            rows = jdbcTemplate.query(holdSelect(false) + " AND status = ? ORDER BY held_at, claim_key",
                    HOLD_MAPPER, status);
        }
        return new ClaimHoldListResponse(rows.stream().map(this::toDetail).toList());
    }

    private ClaimHoldResponse doHold(String bagTag, ClaimHoldRequest request) {
        // 锁顺序与装载一致（先航段后行李）：先无锁读取定位航段，再按序加锁，行锁内重新校验
        BagRow snapshot = findBag(bagTag);
        if (snapshot == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        if (snapshot.loadedLegId() != null) {
            lockLeg(snapshot.loadedLegId());
        }
        BagRow bag = lockBag(bagTag);
        String loadedLegId = bag.loadedLegId();
        if (loadedLegId != null && !loadedLegId.equals(snapshot.loadedLegId())) {
            lockLeg(loadedLegId);
        }
        if (BAG_CLAIM_HOLD.equals(bag.status())) {
            throw ApiException.conflict("行李 " + bagTag + " 已存在生效的认领冻结，同一行李同时只能一个");
        }
        if (BAG_DELIVERED.equals(bag.status())) {
            throw ApiException.conflict("行李 " + bagTag + " 已最终交付，不可冻结");
        }
        if (BAG_LOST.equals(bag.status())) {
            throw ApiException.conflict("行李 " + bagTag + " 已丢失，不可冻结");
        }
        if (findHold(request.claimKey()) != null) {
            throw ApiException.conflict("认领冻结单号已存在: " + request.claimKey());
        }
        OffsetDateTime heldAt = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        int seq = 0;
        if (loadedLegId != null) {
            LegRow leg = findLeg(loadedLegId);
            if (!LEG_OPEN.equals(leg.status())) {
                throw ApiException.conflict(
                        "行李 " + bagTag + " 所在航段 " + loadedLegId + " 状态为 " + leg.status()
                                + "，已封舱或已到达，不可冻结");
            }
            jdbcTemplate.update("DELETE FROM load_record WHERE bag_tag = ?", bagTag);
            jdbcTemplate.update("UPDATE leg SET version = version + 1 WHERE leg_id = ?", loadedLegId);
            insertChainEvent(request.claimKey(), seq++, EVT_MANIFEST_REMOVED, bagTag,
                    loadedLegId, null, request.operator(), heldAt);
        }
        jdbcTemplate.update(
                "INSERT INTO claim_hold (claim_key, bag_tag, status, reason, verify_digest,"
                        + " prior_status, removed_leg_id, held_by, held_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                request.claimKey(), bagTag, HOLD_ACTIVE, request.reason(), request.verifyDigest(),
                bag.status(), loadedLegId, request.operator(), heldAt);
        jdbcTemplate.update(
                "UPDATE bag SET status = ?, loaded_leg_id = NULL WHERE bag_tag = ?",
                BAG_CLAIM_HOLD, bagTag);
        insertChainEvent(request.claimKey(), seq, EVT_HOLD, bagTag,
                null, request.reason(), request.operator(), heldAt);
        insertBagEvent(bagTag, BAG_EVT_HELD, loadedLegId, bag.currentLocation(), heldAt);
        return new ClaimHoldResponse(request.claimKey(), bagTag, HOLD_ACTIVE, BAG_CLAIM_HOLD,
                bag.status(), loadedLegId, request.reason(), request.operator(),
                heldAt.toInstant().toString());
    }

    private ClaimReviewResponse doReview(String claimKey, ClaimReviewRequest request) {
        HoldRow hold = lockHold(claimKey);
        if (hold == null) {
            throw ApiException.notFound("认领冻结单不存在: " + claimKey);
        }
        if (HOLD_RELEASED.equals(hold.status())) {
            throw ApiException.conflict("认领冻结单 " + claimKey + " 已解除，不可复核");
        }
        if (HOLD_REVIEWED.equals(hold.status())) {
            throw ApiException.conflict("认领冻结单 " + claimKey + " 已复核，不可重复复核");
        }
        if (hold.heldBy().equals(request.operator())) {
            throw ApiException.conflict("复核须由与冻结登记人不同的客服执行: " + request.operator());
        }
        if (!hold.verifyDigest().equals(request.verifyDigest())) {
            throw ApiException.unprocessable(
                    "乘客核验摘要不匹配: 提交 " + request.verifyDigest() + "，登记 " + hold.verifyDigest());
        }
        OffsetDateTime reviewedAt = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        jdbcTemplate.update(
                "UPDATE claim_hold SET status = ?, reviewed_by = ?, reviewed_at = ? WHERE claim_key = ?",
                HOLD_REVIEWED, request.operator(), reviewedAt, claimKey);
        insertChainEvent(claimKey, nextSeq(claimKey), EVT_REVIEW, hold.bagTag(),
                null, null, request.operator(), reviewedAt);
        return new ClaimReviewResponse(claimKey, hold.bagTag(), HOLD_REVIEWED,
                request.operator(), reviewedAt.toInstant().toString());
    }

    private ClaimReleaseResponse doRelease(String claimKey, ClaimReleaseRequest request) {
        HoldRow hold = lockHold(claimKey);
        if (hold == null) {
            throw ApiException.notFound("认领冻结单不存在: " + claimKey);
        }
        if (HOLD_RELEASED.equals(hold.status())) {
            throw ApiException.conflict("认领冻结单 " + claimKey + " 已解除，不可重复解除");
        }
        if (!HOLD_REVIEWED.equals(hold.status())) {
            throw ApiException.conflict("认领冻结单 " + claimKey + " 未复核，须先由不同客服复核再确认解除");
        }
        if (!hold.reviewedBy().equals(request.operator())) {
            throw ApiException.conflict(
                    "解除确认须由复核客服本人提交: 复核人 " + hold.reviewedBy()
                            + "，提交人 " + request.operator());
        }
        BagRow bag = lockBag(hold.bagTag());
        if (bag == null || !BAG_CLAIM_HOLD.equals(bag.status())) {
            throw ApiException.conflict(
                    "行李 " + hold.bagTag() + " 状态已变化，无法按冻结单 " + claimKey + " 解除");
        }
        OffsetDateTime releasedAt = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        jdbcTemplate.update(
                "UPDATE claim_hold SET status = ?, released_by = ?, released_at = ? WHERE claim_key = ?",
                HOLD_RELEASED, request.operator(), releasedAt, claimKey);
        // 仅恢复冻结前状态，不恢复旧装载关系：冻结期间的装载请求须重提并按当前状态校验
        jdbcTemplate.update("UPDATE bag SET status = ? WHERE bag_tag = ?",
                hold.priorStatus(), hold.bagTag());
        insertChainEvent(claimKey, nextSeq(claimKey), EVT_RELEASE, hold.bagTag(),
                null, null, request.operator(), releasedAt);
        insertBagEvent(hold.bagTag(), BAG_EVT_RELEASED, null, bag.currentLocation(), releasedAt);
        return new ClaimReleaseResponse(claimKey, hold.bagTag(), HOLD_RELEASED, hold.priorStatus(),
                request.operator(), releasedAt.toInstant().toString());
    }

    private ClaimHoldDetail toDetail(HoldRow row) {
        return new ClaimHoldDetail(row.claimKey(), row.bagTag(), row.status(), row.priorStatus(),
                row.removedLegId(), row.reason(), row.heldBy(),
                row.heldAt() == null ? null : row.heldAt().toString(),
                row.reviewedBy(), row.reviewedAt() == null ? null : row.reviewedAt().toString(),
                row.releasedBy(), row.releasedAt() == null ? null : row.releasedAt().toString());
    }

    private static String holdSelect(boolean forUpdate) {
        return "SELECT claim_key, bag_tag, status, reason, verify_digest, prior_status,"
                + " removed_leg_id, held_by, held_at, reviewed_by, reviewed_at,"
                + " released_by, released_at FROM claim_hold WHERE 1 = 1"
                + (forUpdate ? " AND claim_key = ? FOR UPDATE" : "");
    }

    private HoldRow findHold(String claimKey) {
        List<HoldRow> rows = jdbcTemplate.query(
                holdSelect(false) + " AND claim_key = ?", HOLD_MAPPER, claimKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private HoldRow lockHold(String claimKey) {
        List<HoldRow> rows = jdbcTemplate.query(holdSelect(true), HOLD_MAPPER, claimKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BagRow findBag(String bagTag) {
        List<BagRow> rows = jdbcTemplate.query(bagSelect(false), BAG_ROW_MAPPER, bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BagRow lockBag(String bagTag) {
        List<BagRow> rows = jdbcTemplate.query(bagSelect(true), BAG_ROW_MAPPER, bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static final RowMapper<BagRow> BAG_ROW_MAPPER = (rs, rowNum) -> new BagRow(
            rs.getString("bag_tag"), rs.getString("current_location"),
            rs.getString("status"), rs.getString("loaded_leg_id"));

    private static String bagSelect(boolean forUpdate) {
        return "SELECT bag_tag, current_location, status, loaded_leg_id FROM bag WHERE bag_tag = ?"
                + (forUpdate ? " FOR UPDATE" : "");
    }

    private LegRow findLeg(String legId) {
        List<LegRow> rows = jdbcTemplate.query(
                "SELECT leg_id, status FROM leg WHERE leg_id = ?", LEG_ROW_MAPPER, legId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void lockLeg(String legId) {
        List<LegRow> rows = jdbcTemplate.query(
                "SELECT leg_id, status FROM leg WHERE leg_id = ? FOR UPDATE", LEG_ROW_MAPPER, legId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
    }

    private static final RowMapper<LegRow> LEG_ROW_MAPPER = (rs, rowNum) ->
            new LegRow(rs.getString("leg_id"), rs.getString("status"));

    private int nextSeq(String claimKey) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq) FROM claim_hold_event WHERE claim_key = ?", Integer.class, claimKey);
        return maxSeq == null ? 0 : maxSeq + 1;
    }

    /** 追加不可变链记录：只插入不更新，(claim_key, seq) 唯一。 */
    private void insertChainEvent(String claimKey, int seq, String eventType, String bagTag,
                                  String legId, String reason, String actor, OffsetDateTime eventTime) {
        jdbcTemplate.update(
                "INSERT INTO claim_hold_event (claim_key, seq, event_type, bag_tag, leg_id,"
                        + " reason, actor, event_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                claimKey, seq, eventType, bagTag, legId, reason, actor, eventTime);
    }

    /** 同步写入行李轨迹事件，保持轨迹查询完整。 */
    private void insertBagEvent(String bagTag, String eventType, String legId, String location,
                                OffsetDateTime eventTime) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq) FROM bag_event WHERE bag_tag = ?", Integer.class, bagTag);
        int nextSeq = maxSeq == null ? 0 : maxSeq + 1;
        jdbcTemplate.update(
                "INSERT INTO bag_event (bag_tag, seq, event_type, leg_id, location, event_time)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                bagTag, nextSeq, eventType, legId, location, eventTime);
    }

    private static Instant getInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record BagRow(String bagTag, String currentLocation, String status, String loadedLegId) {
    }

    private record LegRow(String legId, String status) {
    }

    private record HoldRow(String claimKey, String bagTag, String status, String reason,
                           String verifyDigest, String priorStatus, String removedLegId,
                           String heldBy, Instant heldAt, String reviewedBy, Instant reviewedAt,
                           String releasedBy, Instant releasedAt) {
    }

    /** 冻结登记幂等摘要参数。 */
    private record HoldPayload(String bagTag, String claimKey, String verifyDigest,
                               String reason, String operator) {
    }

    /** 复核幂等摘要参数。 */
    private record ReviewPayload(String claimKey, String verifyDigest, String operator) {
    }

    /** 解除确认幂等摘要参数。 */
    private record ReleasePayload(String claimKey, String operator) {
    }
}
