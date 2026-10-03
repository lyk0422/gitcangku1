package com.example.starter.cue.service;

import com.example.starter.cue.domain.CueItem;
import com.example.starter.cue.domain.CueVersion;
import com.example.starter.cue.repo.CueVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 提示点编排：每次修改产生新版本；冻结前整体校验全部提示点，
 * 任何无效时长或重复序号都使整个版本不能冻结，而不是只丢弃坏提示点。
 */
@Service
public class CueVersionService {

    private final CueVersionRepository versions;
    private final Clock clock;

    public CueVersionService(CueVersionRepository versions, Clock cueClock) {
        this.versions = versions;
        this.clock = cueClock;
    }

    /**
     * 新建草稿版本并写入全部提示点。返回新版本 ID。
     */
    @Transactional
    public long createVersion(String production, List<CueItem> cues) {
        if (production == null || production.isBlank()) {
            throw BusinessException.badRequest("剧目名称不能为空");
        }
        if (cues == null || cues.isEmpty()) {
            throw BusinessException.badRequest("提示点列表不能为空");
        }
        long versionId = versions.insertVersion(production, LocalDateTime.now(clock));
        versions.insertItems(versionId, cues);
        return versionId;
    }

    /**
     * 冻结版本：整体校验全部提示点，任一无效时长或重复序号都拒绝冻结并返回全部原因。
     */
    @Transactional
    public CueVersion freezeVersion(long versionId) {
        CueVersion version = versions.findVersion(versionId)
                .orElseThrow(() -> BusinessException.notFound("版本不存在: " + versionId));
        if (CueVersion.STATUS_FROZEN.equals(version.status())) {
            return version;
        }
        List<CueItem> items = versions.findItems(versionId);
        List<String> violations = validateAll(items);
        if (!violations.isEmpty()) {
            throw BusinessException.badRequest("版本不能冻结: " + String.join("; ", violations));
        }
        versions.markFrozen(versionId);
        return versions.findVersion(versionId).orElseThrow();
    }

    /**
     * 整体校验提示点列表，返回全部违规描述（空列表表示通过）。
     * 供冻结与场次重新绑定共用。
     */
    public List<String> validateAll(List<CueItem> items) {
        List<String> violations = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (CueItem item : items) {
            if (!seen.add(item.seq())) {
                violations.add("序号 " + item.seq() + " 重复");
            }
            if (item.seq() < 1) {
                violations.add("序号 " + item.seq() + " 必须从 1 开始递增");
            }
            if (item.durationSeconds() < CueItem.MIN_DURATION_SECONDS
                    || item.durationSeconds() > CueItem.MAX_DURATION_SECONDS) {
                violations.add("序号 " + item.seq() + " 持续秒数 " + item.durationSeconds()
                        + " 超出 1 至 600 范围");
            }
            if (item.cueType() == null || item.cueType().isBlank()) {
                violations.add("序号 " + item.seq() + " 缺少提示点类型");
            }
            if (item.triggerPhrase() == null || item.triggerPhrase().isBlank()) {
                violations.add("序号 " + item.seq() + " 缺少触发口令");
            }
        }
        return violations;
    }

    /**
     * 比较同剧目两个版本的提示点差异：新增、移除、修改与未变数量。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> diff(long fromVersionId, long toVersionId) {
        CueVersion from = versions.findVersion(fromVersionId)
                .orElseThrow(() -> BusinessException.notFound("版本不存在: " + fromVersionId));
        CueVersion to = versions.findVersion(toVersionId)
                .orElseThrow(() -> BusinessException.notFound("版本不存在: " + toVersionId));
        Map<Integer, CueItem> fromBySeq = new HashMap<>();
        for (CueItem item : versions.findItems(from.id())) {
            fromBySeq.put(item.seq(), item);
        }
        List<CueItem> added = new ArrayList<>();
        List<CueItem> removed = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        int unchanged = 0;
        Map<Integer, CueItem> toBySeq = new HashMap<>();
        for (CueItem item : versions.findItems(to.id())) {
            toBySeq.put(item.seq(), item);
        }
        for (CueItem item : toBySeq.values().stream().sorted((a, b) -> a.seq() - b.seq()).toList()) {
            CueItem old = fromBySeq.get(item.seq());
            if (old == null) {
                added.add(item);
            } else if (old.cueType().equals(item.cueType())
                    && old.triggerPhrase().equals(item.triggerPhrase())
                    && old.durationSeconds() == item.durationSeconds()) {
                unchanged++;
            } else {
                changed.add(Map.of("seq", item.seq(), "from", old, "to", item));
            }
        }
        for (CueItem item : fromBySeq.values()) {
            if (!toBySeq.containsKey(item.seq())) {
                removed.add(item);
            }
        }
        removed.sort((a, b) -> a.seq() - b.seq());
        Map<String, Object> result = new HashMap<>();
        result.put("production", from.production());
        result.put("fromVersion", from.versionNo());
        result.put("toVersion", to.versionNo());
        result.put("added", added);
        result.put("removed", removed);
        result.put("changed", changed);
        result.put("unchanged", unchanged);
        return result;
    }

    /** 查询版本及其提示点。 */
    @Transactional(readOnly = true)
    public Map<String, Object> describe(long versionId) {
        CueVersion version = versions.findVersion(versionId)
                .orElseThrow(() -> BusinessException.notFound("版本不存在: " + versionId));
        return Map.of("version", version, "cues", versions.findItems(versionId));
    }
}