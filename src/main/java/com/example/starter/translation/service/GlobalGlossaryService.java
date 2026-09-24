package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.repo.TranslationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 全局术语库（共享术语库）业务服务。
 * 全局术语库不属于任何文档：版本从 1 起递增，每版为不可变快照，已有版本不可覆盖，
 * 其更新不改变任何文档的 draftVersion。写操作对全局状态行加 FOR UPDATE 行锁串行推进，
 * 与文档侧引用升级按提交顺序裁决；方法均加入调用方事务，与幂等记录原子提交。
 */
@Service
public class GlobalGlossaryService {

    private final TranslationRepository repository;

    public GlobalGlossaryService(TranslationRepository repository) {
        this.repository = repository;
    }

    /**
     * 新增全局术语库版本：expectedGlobalTermVersion 必须等于当前全局版本（不符 409）；
     * 规则 0~200 条、按 sourceTerm 与目标语言唯一（不符 422），语言码统一小写化。
     * 成功后全局版本加一，版本快照与状态推进同一事务提交。
     */
    @Transactional
    public ApiDtos.GlobalTermVersionResponse updateGlobalTerms(ApiDtos.UpdateGlobalTermsRequest request) {
        int currentVersion = repository.lockGlobalGlossaryState();
        if (currentVersion != request.expectedGlobalTermVersion()) {
            throw ApiException.conflict("全局术语库版本冲突：当前版本 " + currentVersion
                    + "，与期望的 " + request.expectedGlobalTermVersion() + " 不一致");
        }
        List<TermRuleRow> rules = new ArrayList<>();
        Set<List<String>> seen = new HashSet<>();
        for (ApiDtos.GlobalTermRuleInput input : request.rules()) {
            String language = input.language().trim().toLowerCase(Locale.ROOT);
            if (!seen.add(List.of(input.sourceTerm(), language))) {
                throw ApiException.unprocessable(
                        "全局术语规则重复: " + input.sourceTerm() + "/" + language);
            }
            rules.add(new TermRuleRow(input.sourceTerm(), language, input.requiredTranslation(), false));
        }
        int globalTermVersion = currentVersion + 1;
        repository.insertGlobalTermVersion(globalTermVersion);
        for (TermRuleRow rule : rules) {
            repository.insertGlobalTermRule(globalTermVersion, rule);
        }
        repository.updateGlobalGlossaryState(globalTermVersion);
        return new ApiDtos.GlobalTermVersionResponse(globalTermVersion, rules.size());
    }

    /** 查询当前全局术语库版本及完整规则集；尚未建立版本时返回版本 0 与空规则。 */
    @Transactional(readOnly = true)
    public ApiDtos.GlobalTermVersionView getCurrentGlobalTerms() {
        int globalTermVersion = repository.findLatestGlobalTermVersion();
        return new ApiDtos.GlobalTermVersionView(globalTermVersion,
                toRuleViews(repository.listGlobalTermRules(globalTermVersion)));
    }

    /** 查询指定全局术语库版本的不可变规则集；版本不存在返回 404。 */
    @Transactional(readOnly = true)
    public ApiDtos.GlobalTermVersionView getGlobalTerms(int globalTermVersion) {
        if (globalTermVersion < 1 || !repository.globalTermVersionExists(globalTermVersion)) {
            throw ApiException.notFound("全局术语库版本不存在: " + globalTermVersion);
        }
        return new ApiDtos.GlobalTermVersionView(globalTermVersion,
                toRuleViews(repository.listGlobalTermRules(globalTermVersion)));
    }

    private static List<ApiDtos.TermRuleView> toRuleViews(List<TermRuleRow> rules) {
        return rules.stream()
                .map(rule -> new ApiDtos.TermRuleView(rule.sourceTerm(), rule.language(),
                        rule.requiredTranslation(), rule.suppressed()))
                .toList();
    }
}
