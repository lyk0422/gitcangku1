package com.example.starter.deacid;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 档案脱酸业务服务：登记、入组、处理读数、封存、归还与轮次查询。
 * 同一页同一时刻只允许处于一个进行中的批次（page_occupancy 主键约束保证）；
 * 入组与封存按 requestId 幂等，同键同参重放原状态，同键异参返回 409。
 */
@Service
public class DeacidService {

    /** pH 合规下限（含），无量纲。 */
    public static final BigDecimal MIN_PH = new BigDecimal("6.5");
    /** pH 合规上限（含），无量纲。 */
    public static final BigDecimal MAX_PH = new BigDecimal("8.5");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DeacidService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 登记档案页，馆藏号唯一。 */
    @Transactional
    public Contracts.PageResponse registerPage(String collectionNo) {
        try {
            jdbc.update("INSERT INTO archive_page(collection_no) VALUES (?)", collectionNo);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "PAGE_EXISTS", "馆藏号已存在: " + collectionNo);
        }
        return new Contracts.PageResponse(findPageId(collectionNo), collectionNo);
    }

    /** 创建脱酸批次，初始状态 OPEN。 */
    @Transactional
    public Contracts.BatchResponse createBatch(String batchNo) {
        try {
            jdbc.update("INSERT INTO deacid_batch(batch_no, status) VALUES (?, 'OPEN')", batchNo);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "BATCH_EXISTS", "批次编号已存在: " + batchNo);
        }
        Long id = jdbc.queryForObject("SELECT id FROM deacid_batch WHERE batch_no = ?", Long.class, batchNo);
        return new Contracts.BatchResponse(id, batchNo, "OPEN");
    }

    /** 档案页入组：幂等，占用唯一约束阻止并发双入组。 */
    @Transactional
    public Contracts.EnrollResponse enroll(long batchId, String requestId, String collectionNo) {
        Contracts.EnrollResponse replay = findEnrollReplay(batchId, requestId, collectionNo);
        if (replay != null) {
            return replay;
        }
        Map<String, Object> batch = lockBatch(batchId);
        requireOpen(batch);
        long pageId = findPageId(collectionNo);
        try {
            jdbc.update("INSERT INTO batch_page(batch_id, page_id, request_id, status) VALUES (?,?,?,'ACTIVE')",
                    batchId, pageId, requestId);
        } catch (DuplicateKeyException e) {
            Contracts.EnrollResponse concurrent = findEnrollReplay(batchId, requestId, collectionNo);
            if (concurrent != null) {
                return concurrent;
            }
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT",
                    "requestId 已被不同参数使用: " + requestId);
        }
        Long batchPageId = jdbc.queryForObject(
                "SELECT id FROM batch_page WHERE request_id = ?", Long.class, requestId);
        try {
            jdbc.update("INSERT INTO page_occupancy(page_id, batch_id, batch_page_id) VALUES (?,?,?)",
                    pageId, batchId, batchPageId);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "PAGE_OCCUPIED",
                    "档案页已处于进行中的处理批次: " + collectionNo);
        }
        return new Contracts.EnrollResponse(batchPageId, batchId, pageId, "ACTIVE", false);
    }

    /** 追加处理轮次读数，只增不改；已封存批次只读。 */
    @Transactional
    public Contracts.TreatmentResponse appendTreatment(long batchId, long pageId,
                                                       Contracts.TreatmentRequest request) {
        Map<String, Object> batch = lockBatch(batchId);
        requireOpen(batch);
        Long batchPageId = findActiveBatchPage(batchId, pageId);
        Integer maxRound = jdbc.queryForObject(
                "SELECT MAX(round_no) FROM treatment_record WHERE batch_page_id = ?",
                Integer.class, batchPageId);
        int roundNo = maxRound == null ? 1 : maxRound + 1;
        jdbc.update("INSERT INTO treatment_record(batch_page_id, round_no, ph_before, ph_after, reagent_batch_no)"
                        + " VALUES (?,?,?,?,?)",
                batchPageId, roundNo, request.phBefore(), request.phAfter(), request.reagentBatchNo());
        Long recordId = jdbc.queryForObject(
                "SELECT id FROM treatment_record WHERE batch_page_id = ? AND round_no = ?",
                Long.class, batchPageId, roundNo);
        return new Contracts.TreatmentResponse(recordId, roundNo);
    }
    /** 归还档案页并释放处理占用；已封存批次只读。 */
    @Transactional
    public Contracts.ReleaseResponse release(long batchId, long pageId) {
        Map<String, Object> batch = lockBatch(batchId);
        requireOpen(batch);
        Long batchPageId = findActiveBatchPage(batchId, pageId);
        jdbc.update("UPDATE batch_page SET status = 'RELEASED', released_at = ? WHERE id = ?",
                LocalDateTime.now(), batchPageId);
        jdbc.update("DELETE FROM page_occupancy WHERE batch_page_id = ?", batchPageId);
        return new Contracts.ReleaseResponse(batchPageId, "RELEASED");
    }

    /** 封存批次：整批校验，失败列出问题页并记录退回理由，不封存合格子集。 */
    @Transactional
    public SealOutcome seal(long batchId, String requestId) {
        Map<String, Object> batch = lockBatch(batchId);
        List<Map<String, Object>> stored = jdbc.queryForList(
                "SELECT batch_id, outcome, problems_json FROM seal_request WHERE request_id = ?", requestId);
        if (!stored.isEmpty()) {
            Map<String, Object> row = stored.get(0);
            if (((Number) row.get("batch_id")).longValue() != batchId) {
                throw new ApiException(HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT",
                        "requestId 已被其他批次使用: " + requestId);
            }
            if ("SEALED".equals(row.get("outcome"))) {
                return new SealOutcome.Sealed(new Contracts.SealResponse(batchId, "SEALED", true));
            }
            return new SealOutcome.Rejected(readProblems((String) row.get("problems_json")), true);
        }
        requireOpen(batch);
        List<Map<String, Object>> pages = jdbc.queryForList(
                "SELECT bp.id AS batch_page_id, ap.collection_no AS collection_no"
                        + " FROM batch_page bp JOIN archive_page ap ON ap.id = bp.page_id"
                        + " WHERE bp.batch_id = ? AND bp.status = 'ACTIVE' ORDER BY bp.id", batchId);
        List<Contracts.ProblemPage> problems = new ArrayList<>();
        if (pages.isEmpty()) {
            problems.add(new Contracts.ProblemPage(null, List.of("NO_ACTIVE_PAGES")));
        }
        for (Map<String, Object> page : pages) {
            List<String> reasons = new ArrayList<>();
            List<Map<String, Object>> records = jdbc.queryForList(
                    "SELECT ph_before, ph_after FROM treatment_record"
                            + " WHERE batch_page_id = ? ORDER BY round_no DESC LIMIT 1",
                    page.get("batch_page_id"));
            if (records.isEmpty()) {
                reasons.add("MISSING_READINGS");
            } else {
                BigDecimal before = (BigDecimal) records.get(0).get("ph_before");
                BigDecimal after = (BigDecimal) records.get(0).get("ph_after");
                if (outOfRange(before) || outOfRange(after)) {
                    reasons.add("PH_OUT_OF_RANGE");
                }
                if (after.compareTo(before) < 0) {
                    reasons.add("PH_DECREASED");
                }
            }
            if (!reasons.isEmpty()) {
                problems.add(new Contracts.ProblemPage((String) page.get("collection_no"), reasons));
            }
        }
        if (!problems.isEmpty()) {
            jdbc.update("INSERT INTO seal_request(request_id, batch_id, outcome, problems_json)"
                            + " VALUES (?,?, 'REJECTED', ?)",
                    requestId, batchId, writeProblems(problems));
            return new SealOutcome.Rejected(problems, false);
        }
        jdbc.update("UPDATE deacid_batch SET status = 'SEALED', sealed_at = ? WHERE id = ?",
                LocalDateTime.now(), batchId);
        jdbc.update("INSERT INTO seal_request(request_id, batch_id, outcome, problems_json)"
                + " VALUES (?,?, 'SEALED', NULL)", requestId, batchId);
        return new SealOutcome.Sealed(new Contracts.SealResponse(batchId, "SEALED", false));
    }

    /** 查询档案页处理轮次、批次状态、pH 前后值与药剂来源。 */
    @Transactional(readOnly = true)
    public Contracts.PageTreatmentsResponse pageTreatments(String collectionNo) {
        long pageId = findPageId(collectionNo);
        List<Contracts.TreatmentRoundView> rounds = jdbc.query(
                "SELECT tr.round_no, b.id, b.batch_no, b.status, tr.ph_before, tr.ph_after,"
                        + " tr.reagent_batch_no, tr.created_at"
                        + " FROM treatment_record tr"
                        + " JOIN batch_page bp ON bp.id = tr.batch_page_id"
                        + " JOIN deacid_batch b ON b.id = bp.batch_id"
                        + " WHERE bp.page_id = ? ORDER BY tr.created_at, tr.id",
                (rs, i) -> new Contracts.TreatmentRoundView(rs.getInt(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getString(7),
                        rs.getTimestamp(8).toLocalDateTime()),
                pageId);
        return new Contracts.PageTreatmentsResponse(collectionNo, rounds);
    }
    private Map<String, Object> lockBatch(long batchId) {
        try {
            return jdbc.queryForMap(
                    "SELECT id, batch_no, status FROM deacid_batch WHERE id = ? FOR UPDATE", batchId);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "BATCH_NOT_FOUND", "批次不存在: " + batchId);
        }
    }

    private void requireOpen(Map<String, Object> batch) {
        if (!"OPEN".equals(batch.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT, "BATCH_NOT_OPEN",
                    "批次已封存，只读: " + batch.get("batch_no"));
        }
    }

    private long findPageId(String collectionNo) {
        try {
            return jdbc.queryForObject("SELECT id FROM archive_page WHERE collection_no = ?",
                    Long.class, collectionNo);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PAGE_NOT_FOUND", "档案页不存在: " + collectionNo);
        }
    }

    private Long findActiveBatchPage(long batchId, long pageId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM batch_page WHERE batch_id = ? AND page_id = ? AND status = 'ACTIVE'",
                    Long.class, batchId, pageId);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PAGE_NOT_IN_BATCH",
                    "档案页不在该批次的进行中列表: batch=" + batchId + ", page=" + pageId);
        }
    }

    private Contracts.EnrollResponse findEnrollReplay(long batchId, String requestId, String collectionNo) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT bp.id, bp.batch_id, bp.page_id, bp.status, ap.collection_no"
                        + " FROM batch_page bp JOIN archive_page ap ON ap.id = bp.page_id"
                        + " WHERE bp.request_id = ?", requestId);
        if (existing.isEmpty()) {
            return null;
        }
        Map<String, Object> row = existing.get(0);
        if (((Number) row.get("batch_id")).longValue() == batchId
                && collectionNo.equals(row.get("collection_no"))) {
            return new Contracts.EnrollResponse(((Number) row.get("id")).longValue(), batchId,
                    ((Number) row.get("page_id")).longValue(), (String) row.get("status"), true);
        }
        throw new ApiException(HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT",
                "requestId 已被不同参数使用: " + requestId);
    }

    private boolean outOfRange(BigDecimal ph) {
        return ph.compareTo(MIN_PH) < 0 || ph.compareTo(MAX_PH) > 0;
    }

    private String writeProblems(List<Contracts.ProblemPage> problems) {
        try {
            return objectMapper.writeValueAsString(problems);
        } catch (Exception e) {
            throw new IllegalStateException("问题页清单序列化失败", e);
        }
    }

    private List<Contracts.ProblemPage> readProblems(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("问题页清单反序列化失败", e);
        }
    }

    /** 封存结果：成功或质检退回（含问题页清单）。 */
    public sealed interface SealOutcome {

        /** 封存成功。 */
        record Sealed(Contracts.SealResponse response) implements SealOutcome {
        }

        /** 质检退回，problems 为问题页清单。 */
        record Rejected(List<Contracts.ProblemPage> problems, boolean replayed) implements SealOutcome {
        }
    }
}