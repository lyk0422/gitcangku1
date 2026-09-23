package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 证物封条历史表访问。记录每件证物用过的每个封条号，(evidence_key, seal_no) 唯一。
 * 用于校验重新封存的新封条不得与任一历史封条相同，确认时追加新封条。
 */
@Repository
public class SealHistoryRepository {

    private final JdbcTemplate jdbc;

    public SealHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一个该证物使用过的封条号；重复（同证物同封条号）将触发唯一约束冲突。
     *
     * @param source 封条来源：INTAKE 入库初始封条 / RESEAL 重新封存新封条
     */
    public void insert(String evidenceKey, String sealNo, String source, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO seal_history (evidence_key, seal_no, source, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                evidenceKey, sealNo, source, now);
    }

    /**
     * 判断封条号是否已被该证物使用（含初始封条与历次重新封存新封条）。
     */
    public boolean existsSeal(String evidenceKey, String sealNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM seal_history WHERE evidence_key = ? AND seal_no = ?",
                Integer.class, evidenceKey, sealNo);
        return count != null && count > 0;
    }

    /**
     * 按启用顺序查询该证物全部历史封条号。
     */
    public List<String> findSealsByEvidenceKey(String evidenceKey) {
        return jdbc.queryForList(
                "SELECT seal_no FROM seal_history WHERE evidence_key = ? ORDER BY id",
                String.class, evidenceKey);
    }
}
