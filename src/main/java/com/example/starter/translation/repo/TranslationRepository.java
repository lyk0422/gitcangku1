package com.example.starter.translation.repo;

import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.RequestLogRow;
import com.example.starter.translation.domain.Rows.ReviewPolicyRow;
import com.example.starter.translation.domain.Rows.ReviewStage;
import com.example.starter.translation.domain.Rows.ReviewVoteRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.domain.Rows.VoteDecision;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 多语种段落发布的数据访问层，基于 JdbcTemplate 与参数化 SQL。
 * 目标语言在 document 表中以逗号分隔存储，行记录中还原为列表。
 */
@Repository
public class TranslationRepository {

    private static final RowMapper<DocumentRow> DOCUMENT_MAPPER = (rs, n) -> new DocumentRow(
            rs.getLong("document_id"),
            Arrays.stream(rs.getString("target_languages").split(",")).toList(),
            rs.getInt("draft_version"),
            rs.getInt("published_version"),
            rs.getInt("term_version"));

    private static final RowMapper<SegmentRow> SEGMENT_MAPPER = (rs, n) -> new SegmentRow(
            rs.getString("segment_id"), rs.getString("source_text"), rs.getInt("source_version"));

    private static final RowMapper<TranslationRow> TRANSLATION_MAPPER = (rs, n) -> new TranslationRow(
            rs.getString("segment_id"), rs.getString("language"), rs.getString("content"),
            rs.getString("author"), rs.getInt("source_version"), rs.getInt("translation_version"),
            rs.getInt("term_version"));

    private static final RowMapper<ApprovalRow> APPROVAL_MAPPER = (rs, n) -> new ApprovalRow(
            rs.getString("segment_id"), rs.getString("language"), rs.getString("reviewer"),
            rs.getInt("source_version"), rs.getInt("translation_version"));

    private static final RowMapper<TermRuleRow> TERM_RULE_MAPPER = (rs, n) -> new TermRuleRow(
            rs.getString("source_term"), rs.getString("language"), rs.getString("required_translation"));

    private static final RowMapper<ReviewPolicyRow> REVIEW_POLICY_MAPPER = (rs, n) -> new ReviewPolicyRow(
            rs.getLong("document_id"), rs.getString("language"), rs.getInt("policy_version"),
            splitReviewers(rs.getString("language_reviewers")), rs.getInt("language_quorum"),
            splitReviewers(rs.getString("compliance_reviewers")), rs.getInt("compliance_quorum"));

    private static final RowMapper<ReviewVoteRow> REVIEW_VOTE_MAPPER = (rs, n) -> new ReviewVoteRow(
            rs.getLong("vote_id"), rs.getString("vote_key"), rs.getLong("document_id"),
            rs.getString("segment_id"), rs.getString("language"),
            ReviewStage.valueOf(rs.getString("stage")), rs.getString("reviewer"),
            rs.getInt("source_version"), rs.getInt("translation_version"), rs.getInt("term_version"),
            rs.getInt("policy_version"), VoteDecision.valueOf(rs.getString("decision")),
            rs.getInt("vote_version"), rs.getString("request_id"));

    private static List<String> splitReviewers(String csv) {
        if (csv == null || csv.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).toList();
    }

    private final JdbcTemplate jdbc;

    public TranslationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入文档并返回自增 ID，初始草稿版本 1、发布版本 0、术语版本 0。 */
    public long insertDocument(List<String> targetLanguages) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO document (target_languages, draft_version, published_version, term_version) "
                            + "VALUES (?, 1, 0, 0)",
                    new String[]{"document_id"});
            ps.setString(1, String.join(",", targetLanguages));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("文档创建后未返回自增主键");
        }
        return key.longValue();
    }

    /** 按 ID 查询文档并加行级写锁（FOR UPDATE），用于串行化同一文档的写操作。 */
    public Optional<DocumentRow> findDocumentForUpdate(long documentId) {
        List<DocumentRow> rows = jdbc.query(
                "SELECT document_id, target_languages, draft_version, published_version, term_version "
                        + "FROM document WHERE document_id = ? FOR UPDATE",
                DOCUMENT_MAPPER, documentId);
        return rows.stream().findFirst();
    }

    /** 只读查询文档（不加锁），用于快照查询。 */
    public Optional<DocumentRow> findDocument(long documentId) {
        List<DocumentRow> rows = jdbc.query(
                "SELECT document_id, target_languages, draft_version, published_version, term_version "
                        + "FROM document WHERE document_id = ?",
                DOCUMENT_MAPPER, documentId);
        return rows.stream().findFirst();
    }

    public void updateDraftVersion(long documentId, int draftVersion) {
        jdbc.update("UPDATE document SET draft_version = ? WHERE document_id = ?", draftVersion, documentId);
    }

    public void updatePublishedVersion(long documentId, int publishedVersion) {
        jdbc.update("UPDATE document SET published_version = ? WHERE document_id = ?", publishedVersion, documentId);
    }

    public void updateTermVersion(long documentId, int termVersion) {
        jdbc.update("UPDATE document SET term_version = ? WHERE document_id = ?", termVersion, documentId);
    }

    public void insertSegment(long documentId, String segmentId, String sourceText) {
        jdbc.update("INSERT INTO segment (document_id, segment_id, source_text, source_version) VALUES (?, ?, ?, 1)",
                documentId, segmentId, sourceText);
    }

    public Optional<SegmentRow> findSegment(long documentId, String segmentId) {
        List<SegmentRow> rows = jdbc.query(
                "SELECT segment_id, source_text, source_version FROM segment "
                        + "WHERE document_id = ? AND segment_id = ?",
                SEGMENT_MAPPER, documentId, segmentId);
        return rows.stream().findFirst();
    }

    public List<SegmentRow> listSegments(long documentId) {
        return jdbc.query(
                "SELECT segment_id, source_text, source_version FROM segment "
                        + "WHERE document_id = ? ORDER BY segment_id",
                SEGMENT_MAPPER, documentId);
    }

    public void updateSegmentSource(long documentId, String segmentId, String sourceText, int sourceVersion) {
        jdbc.update("UPDATE segment SET source_text = ?, source_version = ? "
                        + "WHERE document_id = ? AND segment_id = ?",
                sourceText, sourceVersion, documentId, segmentId);
    }

    public Optional<TranslationRow> findTranslation(long documentId, String segmentId, String language) {
        List<TranslationRow> rows = jdbc.query(
                "SELECT segment_id, language, content, author, source_version, translation_version, term_version "
                        + "FROM translation WHERE document_id = ? AND segment_id = ? AND language = ?",
                TRANSLATION_MAPPER, documentId, segmentId, language);
        return rows.stream().findFirst();
    }

    public List<TranslationRow> listTranslations(long documentId) {
        return jdbc.query(
                "SELECT segment_id, language, content, author, source_version, translation_version, term_version "
                        + "FROM translation WHERE document_id = ? ORDER BY segment_id, language",
                TRANSLATION_MAPPER, documentId);
    }

    /** 插入或覆盖译文（按主键段落+语言唯一）。 */
    public void upsertTranslation(long documentId, TranslationRow row) {
        int updated = jdbc.update(
                "UPDATE translation SET content = ?, author = ?, source_version = ?, translation_version = ?, "
                        + "term_version = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE document_id = ? AND segment_id = ? AND language = ?",
                row.content(), row.author(), row.sourceVersion(), row.translationVersion(), row.termVersion(),
                documentId, row.segmentId(), row.language());
        if (updated == 0) {
            jdbc.update("INSERT INTO translation (document_id, segment_id, language, content, author, "
                            + "source_version, translation_version, term_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    documentId, row.segmentId(), row.language(), row.content(), row.author(),
                    row.sourceVersion(), row.translationVersion(), row.termVersion());
        }
    }

    public Optional<ApprovalRow> findApproval(long documentId, String segmentId, String language) {
        List<ApprovalRow> rows = jdbc.query(
                "SELECT segment_id, language, reviewer, source_version, translation_version "
                        + "FROM approval WHERE document_id = ? AND segment_id = ? AND language = ?",
                APPROVAL_MAPPER, documentId, segmentId, language);
        return rows.stream().findFirst();
    }

    public List<ApprovalRow> listApprovals(long documentId) {
        return jdbc.query(
                "SELECT segment_id, language, reviewer, source_version, translation_version "
                        + "FROM approval WHERE document_id = ? ORDER BY segment_id, language",
                APPROVAL_MAPPER, documentId);
    }

    /** 插入或覆盖批准（按主键段落+语言唯一）。 */
    public void upsertApproval(long documentId, ApprovalRow row) {
        int updated = jdbc.update(
                "UPDATE approval SET reviewer = ?, source_version = ?, translation_version = ?, "
                        + "approved_at = CURRENT_TIMESTAMP "
                        + "WHERE document_id = ? AND segment_id = ? AND language = ?",
                row.reviewer(), row.sourceVersion(), row.translationVersion(),
                documentId, row.segmentId(), row.language());
        if (updated == 0) {
            jdbc.update("INSERT INTO approval (document_id, segment_id, language, reviewer, "
                            + "source_version, translation_version) VALUES (?, ?, ?, ?, ?, ?)",
                    documentId, row.segmentId(), row.language(), row.reviewer(),
                    row.sourceVersion(), row.translationVersion());
        }
    }

    public void insertSnapshot(long documentId, int publishedVersion, String snapshotJson) {
        jdbc.update("INSERT INTO release_snapshot (document_id, published_version, snapshot_json) VALUES (?, ?, ?)",
                documentId, publishedVersion, snapshotJson);
    }

    /** 插入术语版本主记录（不可变，主键已存在时抛冲突）。 */
    public void insertTermVersion(long documentId, int termVersion) {
        jdbc.update("INSERT INTO term_version (document_id, term_version) VALUES (?, ?)",
                documentId, termVersion);
    }

    /** 插入一条术语规则，归属指定术语版本。 */
    public void insertTermRule(long documentId, int termVersion, TermRuleRow rule) {
        jdbc.update("INSERT INTO term_rule (document_id, term_version, source_term, language, "
                        + "required_translation) VALUES (?, ?, ?, ?, ?)",
                documentId, termVersion, rule.sourceTerm(), rule.language(), rule.requiredTranslation());
    }

    /** 判断指定术语版本是否存在。 */
    public boolean termVersionExists(long documentId, int termVersion) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_version WHERE document_id = ? AND term_version = ?",
                Integer.class, documentId, termVersion);
        return count != null && count > 0;
    }

    /** 查询指定术语版本的全部规则，按 sourceTerm、语言排序保证稳定输出。 */
    public List<TermRuleRow> listTermRules(long documentId, int termVersion) {
        return jdbc.query(
                "SELECT source_term, language, required_translation FROM term_rule "
                        + "WHERE document_id = ? AND term_version = ? ORDER BY source_term, language",
                TERM_RULE_MAPPER, documentId, termVersion);
    }

    public Optional<String> findSnapshot(long documentId, int publishedVersion) {
        List<String> rows = jdbc.query(
                "SELECT snapshot_json FROM release_snapshot WHERE document_id = ? AND published_version = ?",
                (rs, n) -> rs.getString(1), documentId, publishedVersion);
        return rows.stream().findFirst();
    }

    public Optional<RequestLogRow> findRequestLog(String requestId) {
        List<RequestLogRow> rows = jdbc.query(
                "SELECT request_id, request_hash, response_status, response_body FROM request_log WHERE request_id = ?",
                (rs, n) -> new RequestLogRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)),
                requestId);
        return rows.stream().findFirst();
    }

    public void insertRequestLog(String requestId, String requestHash, int responseStatus, String responseBody) {
        jdbc.update("INSERT INTO request_log (request_id, request_hash, response_status, response_body) "
                + "VALUES (?, ?, ?, ?)", requestId, requestHash, responseStatus, responseBody);
    }

    /** 插入一个不可变策略版本。 */
    public void insertReviewPolicy(ReviewPolicyRow policy) {
        jdbc.update("INSERT INTO review_policy (document_id, language, policy_version, language_reviewers, "
                        + "language_quorum, compliance_reviewers, compliance_quorum) VALUES (?, ?, ?, ?, ?, ?, ?)",
                policy.documentId(), policy.language(), policy.policyVersion(),
                String.join(",", policy.languageReviewers()), policy.languageQuorum(),
                String.join(",", policy.complianceReviewers()), policy.complianceQuorum());
    }

    /** 激活（插入或更新）某语言当前策略版本指针。 */
    public void upsertActiveReviewPolicy(long documentId, String language, int policyVersion) {
        int updated = jdbc.update(
                "UPDATE review_policy_active SET policy_version = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE document_id = ? AND language = ?",
                policyVersion, documentId, language);
        if (updated == 0) {
            jdbc.update("INSERT INTO review_policy_active (document_id, language, policy_version) "
                    + "VALUES (?, ?, ?)", documentId, language, policyVersion);
        }
    }

    /** 查询某语言当前激活策略版本；无策略返回空。 */
    public Optional<Integer> findActivePolicyVersion(long documentId, String language) {
        List<Integer> rows = jdbc.query(
                "SELECT policy_version FROM review_policy_active WHERE document_id = ? AND language = ?",
                (rs, n) -> rs.getInt(1), documentId, language);
        return rows.stream().findFirst();
    }

    /** 查询指定的不可变策略版本；不存在返回空。 */
    public Optional<ReviewPolicyRow> findReviewPolicy(long documentId, String language, int policyVersion) {
        List<ReviewPolicyRow> rows = jdbc.query(
                "SELECT document_id, language, policy_version, language_reviewers, language_quorum, "
                        + "compliance_reviewers, compliance_quorum FROM review_policy "
                        + "WHERE document_id = ? AND language = ? AND policy_version = ?",
                REVIEW_POLICY_MAPPER, documentId, language, policyVersion);
        return rows.stream().findFirst();
    }

    /** 插入一票并返回自增主键。 */
    public long insertReviewVote(ReviewVoteRow vote) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO review_vote (vote_key, document_id, segment_id, language, stage, reviewer, "
                            + "source_version, translation_version, term_version, policy_version, decision, "
                            + "vote_version, request_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"vote_id"});
            ps.setString(1, vote.voteKey());
            ps.setLong(2, vote.documentId());
            ps.setString(3, vote.segmentId());
            ps.setString(4, vote.language());
            ps.setString(5, vote.stage().name());
            ps.setString(6, vote.reviewer());
            ps.setInt(7, vote.sourceVersion());
            ps.setInt(8, vote.translationVersion());
            ps.setInt(9, vote.termVersion());
            ps.setInt(10, vote.policyVersion());
            ps.setString(11, vote.decision().name());
            ps.setInt(12, vote.voteVersion());
            ps.setString(13, vote.requestId());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("投票后未返回自增主键");
        }
        return key.longValue();
    }

    /** 判断 vote_key 是否已被占用。 */
    public boolean reviewVoteKeyExists(String voteKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_vote WHERE vote_key = ?", Integer.class, voteKey);
        return count != null && count > 0;
    }

    /** 查询某段落-语言的全部历史票（按阶段、审核人、票版本排序），含已不计入法定人数的旧票。 */
    public List<ReviewVoteRow> listReviewVotes(long documentId, String segmentId, String language) {
        return jdbc.query(
                "SELECT vote_id, vote_key, document_id, segment_id, language, stage, reviewer, source_version, "
                        + "translation_version, term_version, policy_version, decision, vote_version, request_id "
                        + "FROM review_vote WHERE document_id = ? AND segment_id = ? AND language = ? "
                        + "ORDER BY stage, reviewer, vote_version",
                REVIEW_VOTE_MAPPER, documentId, segmentId, language);
    }

    /** 查询文档内全部历史票，供评审矩阵与发布统一计票。 */
    public List<ReviewVoteRow> listAllReviewVotes(long documentId) {
        return jdbc.query(
                "SELECT vote_id, vote_key, document_id, segment_id, language, stage, reviewer, source_version, "
                        + "translation_version, term_version, policy_version, decision, vote_version, request_id "
                        + "FROM review_vote WHERE document_id = ? "
                        + "ORDER BY segment_id, language, stage, reviewer, vote_version",
                REVIEW_VOTE_MAPPER, documentId);
    }

    /** 发布时批量冻结采用的票版本集合，与快照同事务写入。 */
    public void insertReleaseVoteFreeze(long documentId, int publishedVersion, List<ReviewVoteRow> adoptedVotes) {
        for (ReviewVoteRow vote : adoptedVotes) {
            jdbc.update("INSERT INTO release_vote_freeze (document_id, published_version, segment_id, language, "
                            + "stage, reviewer, vote_id, vote_version, decision, source_version, translation_version, "
                            + "term_version, policy_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    documentId, publishedVersion, vote.segmentId(), vote.language(), vote.stage().name(),
                    vote.reviewer(), vote.voteId(), vote.voteVersion(), vote.decision().name(),
                    vote.sourceVersion(), vote.translationVersion(), vote.termVersion(), vote.policyVersion());
        }
    }

    /** 统计某发布版本冻结的票数。 */
    public int countReleaseVoteFreeze(long documentId, int publishedVersion) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_vote_freeze WHERE document_id = ? AND published_version = ?",
                Integer.class, documentId, publishedVersion);
        return count == null ? 0 : count;
    }
}
