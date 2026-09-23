package com.example.starter.translation.repo;

import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.RequestLogRow;
import com.example.starter.translation.domain.Rows.ReviewStageRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.domain.Rows.VoteRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final RowMapper<VoteRow> VOTE_MAPPER = (rs, n) -> new VoteRow(
            rs.getString("segment_id"), rs.getString("language"), rs.getString("stage"),
            rs.getString("reviewer"), rs.getInt("vote_version"), rs.getString("vote_key"),
            rs.getString("decision"), rs.getInt("source_version"), rs.getInt("translation_version"),
            rs.getInt("term_version"), rs.getInt("policy_version"));

    private static final String VOTE_COLUMNS = "segment_id, language, stage, reviewer, vote_version, vote_key, "
            + "decision, source_version, translation_version, term_version, policy_version";

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

    /** 插入评审策略版本主记录（不可变，主键已存在时抛冲突）。 */
    public void insertReviewPolicy(long documentId, String language, int policyVersion) {
        jdbc.update("INSERT INTO review_policy (document_id, language, policy_version) VALUES (?, ?, ?)",
                documentId, language, policyVersion);
    }

    /** 插入策略版本某阶段的法定人数。 */
    public void insertReviewPolicyStage(long documentId, String language, int policyVersion,
                                        String stage, int quorum) {
        jdbc.update("INSERT INTO review_policy_stage (document_id, language, policy_version, stage, quorum) "
                + "VALUES (?, ?, ?, ?, ?)", documentId, language, policyVersion, stage, quorum);
    }

    /** 插入策略版本某阶段的一名候选审核人。 */
    public void insertReviewPolicyReviewer(long documentId, String language, int policyVersion,
                                           String stage, String reviewer) {
        jdbc.update("INSERT INTO review_policy_reviewer (document_id, language, policy_version, stage, reviewer) "
                + "VALUES (?, ?, ?, ?, ?)", documentId, language, policyVersion, stage, reviewer);
    }

    /** 插入或覆盖某语言当前生效的策略版本指针。 */
    public void upsertReviewPolicyCurrent(long documentId, String language, int policyVersion) {
        int updated = jdbc.update(
                "UPDATE review_policy_current SET policy_version = ? WHERE document_id = ? AND language = ?",
                policyVersion, documentId, language);
        if (updated == 0) {
            jdbc.update("INSERT INTO review_policy_current (document_id, language, policy_version) "
                    + "VALUES (?, ?, ?)", documentId, language, policyVersion);
        }
    }

    /** 查询某语言当前生效的策略版本；未配置返回空。 */
    public Optional<Integer> findCurrentPolicyVersion(long documentId, String language) {
        List<Integer> rows = jdbc.query(
                "SELECT policy_version FROM review_policy_current WHERE document_id = ? AND language = ?",
                (rs, n) -> rs.getInt(1), documentId, language);
        return rows.stream().findFirst();
    }

    /** 查询文档全部语言当前生效的策略版本，键为语言码。 */
    public Map<String, Integer> listCurrentPolicyVersions(long documentId) {
        List<Map.Entry<String, Integer>> rows = jdbc.query(
                "SELECT language, policy_version FROM review_policy_current "
                        + "WHERE document_id = ? ORDER BY language",
                (rs, n) -> Map.entry(rs.getString(1), rs.getInt(2)), documentId);
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> row : rows) {
            result.put(row.getKey(), row.getValue());
        }
        return result;
    }

    /** 查询指定策略版本的全部阶段配置（含候选审核人集合），按阶段排序保证稳定输出。 */
    public List<ReviewStageRow> listReviewStages(long documentId, String language, int policyVersion) {
        List<ReviewStageRow> stages = jdbc.query(
                "SELECT stage, quorum FROM review_policy_stage "
                        + "WHERE document_id = ? AND language = ? AND policy_version = ? ORDER BY stage",
                (rs, n) -> new ReviewStageRow(rs.getString("stage"), rs.getInt("quorum"), List.of()),
                documentId, language, policyVersion);
        List<ReviewStageRow> result = new ArrayList<>();
        for (ReviewStageRow stage : stages) {
            List<String> reviewers = jdbc.queryForList(
                    "SELECT reviewer FROM review_policy_reviewer "
                            + "WHERE document_id = ? AND language = ? AND policy_version = ? AND stage = ? "
                            + "ORDER BY reviewer",
                    String.class, documentId, language, policyVersion, stage.stage());
            result.add(new ReviewStageRow(stage.stage(), stage.quorum(), reviewers));
        }
        return result;
    }

    /** 插入一票（新票或改票后的新版本票）；voteKey 唯一约束冲突时抛 DuplicateKeyException。 */
    public void insertVote(long documentId, VoteRow vote) {
        jdbc.update("INSERT INTO review_vote (document_id, segment_id, language, stage, reviewer, vote_version, "
                        + "vote_key, decision, source_version, translation_version, term_version, policy_version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                documentId, vote.segmentId(), vote.language(), vote.stage(), vote.reviewer(), vote.voteVersion(),
                vote.voteKey(), vote.decision(), vote.sourceVersion(), vote.translationVersion(),
                vote.termVersion(), vote.policyVersion());
    }

    /** 按全局唯一 voteKey 查询投票。 */
    public Optional<VoteRow> findVoteByKey(String voteKey) {
        List<VoteRow> rows = jdbc.query(
                "SELECT " + VOTE_COLUMNS + " FROM review_vote WHERE vote_key = ?", VOTE_MAPPER, voteKey);
        return rows.stream().findFirst();
    }

    /** 查询某审核人在某译文某阶段的当前票（最大票版本）；未投过返回空。 */
    public Optional<VoteRow> findLatestVote(long documentId, String segmentId, String language,
                                            String stage, String reviewer) {
        List<VoteRow> rows = jdbc.query(
                "SELECT " + VOTE_COLUMNS + " FROM review_vote "
                        + "WHERE document_id = ? AND segment_id = ? AND language = ? AND stage = ? AND reviewer = ? "
                        + "ORDER BY vote_version DESC LIMIT 1",
                VOTE_MAPPER, documentId, segmentId, language, stage, reviewer);
        return rows.stream().findFirst();
    }

    /** 查询某译文某阶段全部审核人的当前票（每人最大票版本）。 */
    public List<VoteRow> listLatestVotes(long documentId, String segmentId, String language, String stage) {
        return jdbc.query(
                "SELECT v.segment_id, v.language, v.stage, v.reviewer, v.vote_version, v.vote_key, "
                        + "v.decision, v.source_version, v.translation_version, v.term_version, v.policy_version "
                        + "FROM review_vote v "
                        + "JOIN (SELECT reviewer, MAX(vote_version) AS max_version FROM review_vote "
                        + "WHERE document_id = ? AND segment_id = ? AND language = ? AND stage = ? "
                        + "GROUP BY reviewer) latest "
                        + "ON v.reviewer = latest.reviewer AND v.vote_version = latest.max_version "
                        + "WHERE v.document_id = ? AND v.segment_id = ? AND v.language = ? AND v.stage = ? "
                        + "ORDER BY v.reviewer",
                VOTE_MAPPER, documentId, segmentId, language, stage,
                documentId, segmentId, language, stage);
    }

    /** 统计某审核人在某译文某阶段的历史票数（任意票版本），用于同一人只能投一个阶段的判定。 */
    public int countVotes(long documentId, String segmentId, String language, String stage, String reviewer) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_vote "
                        + "WHERE document_id = ? AND segment_id = ? AND language = ? AND stage = ? AND reviewer = ?",
                Integer.class, documentId, segmentId, language, stage, reviewer);
        return count == null ? 0 : count;
    }

    /** 查询文档历史票（含被改票取代的旧版本）；segmentId/language 为空表示不过滤，按段落、语言、阶段、审核人、票版本排序。 */
    public List<VoteRow> listVotes(long documentId, String segmentId, String language) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + VOTE_COLUMNS + " FROM review_vote WHERE document_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(documentId);
        if (segmentId != null) {
            sql.append(" AND segment_id = ?");
            params.add(segmentId);
        }
        if (language != null) {
            sql.append(" AND language = ?");
            params.add(language);
        }
        sql.append(" ORDER BY segment_id, language, stage, reviewer, vote_version");
        return jdbc.query(sql.toString(), VOTE_MAPPER, params.toArray());
    }

    /** 冻结发布采用的票版本集合，与发布快照同一事务写入。 */
    public void insertVoteFreeze(long documentId, int publishedVersion, VoteRow vote) {
        jdbc.update("INSERT INTO release_vote_freeze (document_id, published_version, segment_id, language, "
                        + "stage, reviewer, vote_version, decision, source_version, translation_version, "
                        + "term_version, policy_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                documentId, publishedVersion, vote.segmentId(), vote.language(), vote.stage(), vote.reviewer(),
                vote.voteVersion(), vote.decision(), vote.sourceVersion(), vote.translationVersion(),
                vote.termVersion(), vote.policyVersion());
    }

    /** 查询指定发布版本冻结的票版本集合。 */
    public List<VoteRow> listFrozenVotes(long documentId, int publishedVersion) {
        return jdbc.query(
                "SELECT segment_id, language, stage, reviewer, vote_version, NULL AS vote_key, decision, "
                        + "source_version, translation_version, term_version, policy_version "
                        + "FROM release_vote_freeze WHERE document_id = ? AND published_version = ? "
                        + "ORDER BY segment_id, language, stage, reviewer",
                VOTE_MAPPER, documentId, publishedVersion);
    }
}
