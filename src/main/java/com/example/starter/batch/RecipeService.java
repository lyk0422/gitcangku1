package com.example.starter.batch;

import com.example.starter.batch.dto.BindRecipeRequest;
import com.example.starter.batch.dto.BindRecipeResponse;
import com.example.starter.batch.dto.CorrectWeighingRequest;
import com.example.starter.batch.dto.CreateRecipeRequest;
import com.example.starter.batch.dto.IngredientTotal;
import com.example.starter.batch.dto.RecipeConformanceDetails;
import com.example.starter.batch.dto.RecipeResponse;
import com.example.starter.batch.dto.RecipeStatusResponse;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.WeighRequest;
import com.example.starter.batch.dto.WeighingResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 配方称量核心服务：配方版本（2～6 种成分、百分比最多 4 位小数、总和精确 100）、
 * 批次绑定不可变配方版本及目标总质量（克，最多 6 位小数）、称量累加与更正替代、
 * 放行前配方门禁（成分齐全、总质量相等、每项占比偏差不超过 0.1 个百分点，判断不提前舍入）、
 * 拆分时按子批质量比例精确继承父批成分质量（无法在 6 位小数内守恒则 422）。
 *
 * <p>并发策略：称量/更正在事务内对 batch 行 SELECT ... FOR UPDATE 串行化，与拆分、放行互斥；
 * requestId 幂等身份含操作、操作者、资源及全部参数和期望版本，经结构化 JSON 指纹比较。
 */
@Service
public class RecipeService {

    /**
     * 拆分继承称量记录的操作人标识。
     */
    public static final String SPLIT_OPERATOR = "system:split";

    private static final String CMD_CREATE_RECIPE = "CREATE_RECIPE";
    private static final String CMD_BIND = "BIND_RECIPE";
    private static final String CMD_WEIGH = "WEIGH";
    private static final String CMD_CORRECT = "CORRECT_WEIGH";

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MASS_SCALE = 6;
    private static final int PERCENTAGE_SCALE = 4;

    private final RecipeRepository recipeRepo;
    private final BatchRepository batchRepo;
    private final IdempotentExecutor idem;
    private final ObjectMapper objectMapper;

    public RecipeService(RecipeRepository recipeRepo, BatchRepository batchRepo,
                         IdempotentExecutor idem, ObjectMapper objectMapper) {
        this.recipeRepo = recipeRepo;
        this.batchRepo = batchRepo;
        this.idem = idem;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建配方版本：同 recipeKey+version 内容一致返回既有定义（200），内容不同 409（版本不可变）。
     */
    public StoredResponse createRecipe(String actorId, CreateRecipeRequest req) {
        String actor = requireActor(actorId);
        List<Map<String, Object>> ingredientParams = new ArrayList<>();
        for (CreateRecipeRequest.IngredientSpec spec : req.ingredients()) {
            Map<String, Object> entry = new TreeMap<>();
            entry.put("name", spec.name());
            entry.put("percentage", norm(spec.percentage()));
            ingredientParams.add(entry);
        }
        Map<String, Object> params = new TreeMap<>();
        params.put("version", req.version());
        params.put("ingredients", ingredientParams);
        String fingerprint = fingerprint(CMD_CREATE_RECIPE, actor, req.recipeKey(), params, null);
        return idem.execute(CMD_CREATE_RECIPE, req.requestId(), fingerprint, () -> {
            validateIngredients(req.ingredients());
            var existing = recipeRepo.findRecipeVersion(req.recipeKey(), req.version());
            if (existing.isPresent()) {
                List<RecipeRepository.IngredientRow> existingIngredients =
                        recipeRepo.findIngredients(req.recipeKey(), req.version());
                if (sameContent(existingIngredients, req.ingredients())) {
                    return new StoredResponse(200,
                            toJson(toRecipeResponse(existing.get(), existingIngredients)));
                }
                throw ApiException.conflict("配方版本已存在且内容不同，配方版本不可变: "
                        + req.recipeKey() + " v" + req.version());
            }
            String now = now();
            recipeRepo.insertRecipeVersion(
                    new RecipeRepository.RecipeVersionRow(0L, req.recipeKey(), req.version(), now));
            for (int i = 0; i < req.ingredients().size(); i++) {
                CreateRecipeRequest.IngredientSpec spec = req.ingredients().get(i);
                recipeRepo.insertIngredient(new RecipeRepository.IngredientRow(0L, req.recipeKey(),
                        req.version(), spec.name(), spec.percentage(), i + 1));
            }
            RecipeResponse body = toRecipeResponse(
                    new RecipeRepository.RecipeVersionRow(0L, req.recipeKey(), req.version(), now),
                    recipeRepo.findIngredients(req.recipeKey(), req.version()));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 批次绑定配方版本及目标总质量：每批最多绑定一次；仅放行前状态可绑定。
     */
    public StoredResponse bindRecipe(String batchKey, String actorId, BindRecipeRequest req) {
        String actor = requireActor(actorId);
        Map<String, Object> params = new TreeMap<>();
        params.put("recipeKey", req.recipeKey());
        params.put("version", req.version());
        params.put("targetMass", norm(req.targetMass()));
        String fingerprint = fingerprint(CMD_BIND, actor, batchKey, params, null);
        return idem.execute(CMD_BIND, req.requestId(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交，须重放首次结果
            var logged = idem.loggedResponse(CMD_BIND, req.requestId(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            validateMass(req.targetMass(), "targetMass");
            String status = batch.status();
            if (!BatchStatus.QUARANTINED.name().equals(status)
                    && !BatchStatus.PENDING_RELEASE.name().equals(status)
                    && !BatchStatus.RELEASE_REVIEW.name().equals(status)) {
                throw ApiException.conflict("批次状态 " + status + " 不允许绑定配方");
            }
            if (recipeRepo.findBatchRecipe(batchKey).isPresent()) {
                throw ApiException.conflict("批次已绑定配方，不可修改: " + batchKey);
            }
            recipeRepo.findRecipeVersion(req.recipeKey(), req.version())
                    .orElseThrow(() -> ApiException.notFound(
                            "配方版本不存在: " + req.recipeKey() + " v" + req.version()));
            String now = now();
            recipeRepo.insertBatchRecipe(new RecipeRepository.BatchRecipeRow(0L, batchKey,
                    req.recipeKey(), req.version(), req.targetMass(), 0L, now));
            BindRecipeResponse body = new BindRecipeResponse(batchKey, req.recipeKey(), req.version(),
                    req.targetMass(), 0L, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 追加称量：同成分多次称量累加；未知成分、负量、超精度 400；期望版本不一致 409。
     */
    public StoredResponse weigh(String batchKey, String actorId, WeighRequest req) {
        String actor = requireActor(actorId);
        Map<String, Object> params = new TreeMap<>();
        params.put("ingredient", req.ingredient());
        params.put("mass", norm(req.mass()));
        String fingerprint = fingerprint(CMD_WEIGH, actor, batchKey, params, req.expectedVersion());
        return idem.execute(CMD_WEIGH, req.requestId(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交，须重放首次结果
            var logged = idem.loggedResponse(CMD_WEIGH, req.requestId(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            RecipeRepository.BatchRecipeRow binding = bindingOrThrow(batchKey);
            assertWeighable(batch);
            checkVersion(binding, req.expectedVersion());
            validateMass(req.mass(), "mass");
            List<RecipeRepository.IngredientRow> ingredients =
                    recipeRepo.findIngredients(binding.recipeKey(), binding.recipeVersion());
            boolean known = ingredients.stream()
                    .anyMatch(i -> i.ingredient().equals(req.ingredient()));
            if (!known) {
                throw ApiException.badRequest("未知成分，不属于批次绑定的配方版本: " + req.ingredient());
            }
            String now = now();
            long id = recipeRepo.insertWeighing(new RecipeRepository.WeighingRow(0L, batchKey,
                    req.ingredient(), req.mass(), null, null, actor, now));
            recipeRepo.incrementStateVersion(batchKey);
            RecipeConformanceDetails conformance = computeConformance(batchKey, binding, ingredients);
            WeighingResponse body = new WeighingResponse(id, null, batchKey, req.ingredient(),
                    req.mass(), binding.stateVersion() + 1, conformance.ingredients(),
                    conformance.conforms(), Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 追加更正：指定该成分最近一条有效称量 ID，以替代量重算而不是重复累加；
     * 被替代记录随即失效。负量、超精度 400；记录不存在 404；非最近有效或已失效 409。
     */
    public StoredResponse correct(String batchKey, String actorId, CorrectWeighingRequest req) {
        String actor = requireActor(actorId);
        Map<String, Object> params = new TreeMap<>();
        params.put("weighingId", req.weighingId());
        params.put("mass", norm(req.mass()));
        String fingerprint = fingerprint(CMD_CORRECT, actor, batchKey, params, req.expectedVersion());
        return idem.execute(CMD_CORRECT, req.requestId(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交，须重放首次结果
            var logged = idem.loggedResponse(CMD_CORRECT, req.requestId(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            RecipeRepository.BatchRecipeRow binding = bindingOrThrow(batchKey);
            assertWeighable(batch);
            checkVersion(binding, req.expectedVersion());
            validateMass(req.mass(), "mass");
            RecipeRepository.WeighingRow target = recipeRepo.findWeighing(req.weighingId())
                    .filter(w -> w.batchKey().equals(batchKey))
                    .orElseThrow(() -> ApiException.notFound("称量记录不存在: " + req.weighingId()));
            if (target.supersededBy() != null) {
                throw ApiException.conflict("称量记录已被更正替代，不能再次更正: " + req.weighingId());
            }
            long latestEffective = recipeRepo.findEffectiveWeighings(batchKey).stream()
                    .filter(w -> w.ingredient().equals(target.ingredient()))
                    .mapToLong(RecipeRepository.WeighingRow::id)
                    .max()
                    .orElseThrow(() -> ApiException.conflict("该成分不存在有效称量记录"));
            if (latestEffective != target.id()) {
                throw ApiException.conflict("只能更正该成分最近一条有效称量记录: " + req.weighingId());
            }
            String now = now();
            long newId = recipeRepo.insertWeighing(new RecipeRepository.WeighingRow(0L, batchKey,
                    target.ingredient(), req.mass(), target.id(), null, actor, now));
            recipeRepo.markSuperseded(target.id(), newId);
            recipeRepo.incrementStateVersion(batchKey);
            List<RecipeRepository.IngredientRow> ingredients =
                    recipeRepo.findIngredients(binding.recipeKey(), binding.recipeVersion());
            RecipeConformanceDetails conformance = computeConformance(batchKey, binding, ingredients);
            WeighingResponse body = new WeighingResponse(newId, target.id(), batchKey,
                    target.ingredient(), req.mass(), binding.stateVersion() + 1,
                    conformance.ingredients(), conformance.conforms(), Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 批次配方状态查询：绑定信息、当前有效称量链与符合性汇总。
     */
    public RecipeStatusResponse status(String batchKey) {
        BatchRepository.BatchRow batch = batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        RecipeRepository.BatchRecipeRow binding = recipeRepo.findBatchRecipe(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次未绑定配方: " + batchKey));
        List<RecipeRepository.IngredientRow> ingredients =
                recipeRepo.findIngredients(binding.recipeKey(), binding.recipeVersion());
        RecipeConformanceDetails conformance = computeConformance(batchKey, binding, ingredients);
        List<RecipeStatusResponse.WeighingEntry> entries = recipeRepo.findEffectiveWeighings(batchKey)
                .stream()
                .map(w -> new RecipeStatusResponse.WeighingEntry(w.id(), w.ingredient(), w.mass(),
                        w.supersedesId(), w.operator(), SPLIT_OPERATOR.equals(w.operator()),
                        Instant.parse(w.createdAt())))
                .toList();
        return new RecipeStatusResponse(batchKey, binding.recipeKey(), binding.recipeVersion(),
                binding.targetMass(), binding.stateVersion(), isFrozen(batch.status()),
                conformance.conforms(), conformance.ingredients(), entries);
    }

    /**
     * 放行门禁（在放行事务内调用）：批次绑定配方时，必须每种成分齐全、有效称量总质量
     * 与目标精确相等、每项实际占比偏差不超过 0.1 个百分点；不满足抛 422 并给出各成分差额。
     * 未绑定配方的批次不施加门禁。
     */
    public void assertReleaseConformance(String batchKey) {
        var bindingOpt = recipeRepo.findBatchRecipe(batchKey);
        if (bindingOpt.isEmpty()) {
            return;
        }
        RecipeRepository.BatchRecipeRow binding = bindingOpt.get();
        List<RecipeRepository.IngredientRow> ingredients =
                recipeRepo.findIngredients(binding.recipeKey(), binding.recipeVersion());
        RecipeConformanceDetails conformance = computeConformance(batchKey, binding, ingredients);
        if (!conformance.conforms()) {
            throw ApiException.unprocessable("投料不满足配方要求，禁止放行", conformance);
        }
    }

    /**
     * 拆分继承计划：父批未绑定配方时子批不得指定 targetMass（400）并返回 null；
     * 已绑定时全部子批必须指定 targetMass，其和须等于父批目标总质量，
     * 每个成分按子批质量比例精确折算，无法在 6 位小数内守恒则 422。
     */
    public SplitInheritance planSplitInheritance(String parentKey,
                                                 List<SplitRequest.ChildSpec> children) {
        var bindingOpt = recipeRepo.findBatchRecipe(parentKey);
        boolean anyTarget = children.stream().anyMatch(c -> c.targetMass() != null);
        if (bindingOpt.isEmpty()) {
            if (anyTarget) {
                throw ApiException.badRequest("父批未绑定配方，子批不得指定 targetMass");
            }
            return null;
        }
        RecipeRepository.BatchRecipeRow binding = bindingOpt.get();
        for (SplitRequest.ChildSpec child : children) {
            if (child.targetMass() == null) {
                throw ApiException.badRequest("父批已绑定配方，子批必须指定 targetMass: " + child.batchKey());
            }
            validateMass(child.targetMass(), "targetMass");
        }
        BigDecimal sum = children.stream().map(SplitRequest.ChildSpec::targetMass)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(binding.targetMass()) != 0) {
            throw ApiException.unprocessable("子批目标总质量之和 " + sum.toPlainString()
                    + " 不等于父批目标总质量 " + binding.targetMass().toPlainString() + "，无法守恒");
        }
        List<RecipeRepository.IngredientRow> ingredients =
                recipeRepo.findIngredients(binding.recipeKey(), binding.recipeVersion());
        Map<String, BigDecimal> actual = effectiveTotals(parentKey);
        List<ChildPlan> plans = new ArrayList<>();
        for (SplitRequest.ChildSpec child : children) {
            List<IngredientMass> masses = new ArrayList<>();
            for (RecipeRepository.IngredientRow ingredient : ingredients) {
                BigDecimal parentMass = actual.getOrDefault(ingredient.ingredient(), BigDecimal.ZERO);
                BigDecimal product = parentMass.multiply(child.targetMass());
                final BigDecimal quotient;
                try {
                    quotient = product.divide(binding.targetMass());
                } catch (ArithmeticException e) {
                    throw ApiException.unprocessable("成分 " + ingredient.ingredient()
                            + " 按子批质量比例拆分后无法在 6 位小数内守恒");
                }
                if (quotient.stripTrailingZeros().scale() > MASS_SCALE) {
                    throw ApiException.unprocessable("成分 " + ingredient.ingredient()
                            + " 按子批质量比例拆分后无法在 6 位小数内守恒");
                }
                masses.add(new IngredientMass(ingredient.ingredient(),
                        quotient.setScale(MASS_SCALE, RoundingMode.UNNECESSARY)));
            }
            plans.add(new ChildPlan(child.batchKey(), child.targetMass(), masses));
        }
        return new SplitInheritance(binding, plans);
    }

    /**
     * 应用拆分继承：为子批写入相同配方版本的绑定与按比例的继承称量记录。
     */
    public void applySplitInheritance(String childKey, SplitInheritance inheritance, String now) {
        ChildPlan plan = inheritance.planFor(childKey);
        recipeRepo.insertBatchRecipe(new RecipeRepository.BatchRecipeRow(0L, childKey,
                inheritance.binding().recipeKey(), inheritance.binding().recipeVersion(),
                plan.targetMass(), plan.masses().size(), now));
        for (IngredientMass mass : plan.masses()) {
            recipeRepo.insertWeighing(new RecipeRepository.WeighingRow(0L, childKey,
                    mass.ingredient(), mass.mass(), null, null, SPLIT_OPERATOR, now));
        }
    }

    /**
     * 单成分继承质量。
     */
    public record IngredientMass(String ingredient, BigDecimal mass) {
    }

    /**
     * 单个子批的继承计划。
     */
    public record ChildPlan(String batchKey, BigDecimal targetMass, List<IngredientMass> masses) {
    }

    /**
     * 拆分继承计划：父批绑定信息 + 各子批继承明细。
     */
    public record SplitInheritance(RecipeRepository.BatchRecipeRow binding, List<ChildPlan> children) {

        public ChildPlan planFor(String batchKey) {
            return children.stream()
                    .filter(c -> c.batchKey().equals(batchKey))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("拆分继承计划缺少子批: " + batchKey));
        }
    }

    /**
     * 计算符合性：每种成分齐全、有效称量总质量与目标精确相等、每项占比偏差不超过 0.1 个百分点。
     * 占比判断用精确 BigDecimal 比较，不提前舍入。
     */
    private RecipeConformanceDetails computeConformance(String batchKey,
                                                        RecipeRepository.BatchRecipeRow binding,
                                                        List<RecipeRepository.IngredientRow> ingredients) {
        Map<String, BigDecimal> actual = effectiveTotals(batchKey);
        BigDecimal totalActual = actual.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal target = binding.targetMass();
        boolean totalMatches = totalActual.compareTo(target) == 0;
        List<IngredientTotal> totals = new ArrayList<>();
        boolean allWeighed = true;
        boolean allWithin = true;
        for (RecipeRepository.IngredientRow ingredient : ingredients) {
            BigDecimal expectedMass = ingredient.percentage().multiply(target).divide(HUNDRED);
            BigDecimal actualMass = actual.get(ingredient.ingredient());
            boolean weighed = actualMass != null;
            boolean within = weighed && totalActual.signum() > 0
                    && withinTolerance(actualMass, ingredient.percentage(), totalActual);
            if (!weighed) {
                allWeighed = false;
            }
            if (!within) {
                allWithin = false;
            }
            BigDecimal massDifference = weighed ? actualMass.subtract(expectedMass) : null;
            BigDecimal deviation = weighed && totalActual.signum() > 0
                    ? actualMass.multiply(HUNDRED)
                            .divide(totalActual, 10, RoundingMode.HALF_UP)
                            .subtract(ingredient.percentage())
                            .setScale(MASS_SCALE, RoundingMode.HALF_UP)
                            .stripTrailingZeros()
                    : null;
            totals.add(new IngredientTotal(ingredient.ingredient(), ingredient.percentage(),
                    expectedMass, actualMass, massDifference, deviation, weighed, within));
        }
        boolean conforms = allWeighed && totalMatches && allWithin;
        return new RecipeConformanceDetails(batchKey, target, totalActual,
                totalActual.subtract(target), totalMatches, conforms, List.copyOf(totals));
    }

    /**
     * 精确容差判断：|actual/total×100 − percentage| ≤ 0.1
     * 等价于 |1000×actual − 10×percentage×total| ≤ total，全程精确无舍入。
     */
    private boolean withinTolerance(BigDecimal actualMass, BigDecimal percentage,
                                    BigDecimal totalActual) {
        BigDecimal lhs = actualMass.multiply(BigDecimal.valueOf(1000))
                .subtract(percentage.multiply(BigDecimal.TEN).multiply(totalActual)).abs();
        return lhs.compareTo(totalActual) <= 0;
    }

    private Map<String, BigDecimal> effectiveTotals(String batchKey) {
        Map<String, BigDecimal> actual = new HashMap<>();
        for (RecipeRepository.WeighingRow w : recipeRepo.findEffectiveWeighings(batchKey)) {
            actual.merge(w.ingredient(), w.mass(), BigDecimal::add);
        }
        return actual;
    }

    private void validateIngredients(List<CreateRecipeRequest.IngredientSpec> ingredients) {
        if (new HashSet<>(ingredients.stream().map(CreateRecipeRequest.IngredientSpec::name).toList())
                .size() != ingredients.size()) {
            throw ApiException.badRequest("成分名称在配方版本内重复");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (CreateRecipeRequest.IngredientSpec spec : ingredients) {
            BigDecimal percentage = spec.percentage();
            if (percentage.signum() <= 0) {
                throw ApiException.badRequest("成分百分比必须为正值: " + spec.name());
            }
            if (percentage.stripTrailingZeros().scale() > PERCENTAGE_SCALE) {
                throw ApiException.badRequest("成分百分比最多 4 位小数: " + spec.name());
            }
            sum = sum.add(percentage);
        }
        if (sum.compareTo(HUNDRED) != 0) {
            throw ApiException.badRequest("成分百分比总和必须精确等于 100，当前为 " + sum.toPlainString());
        }
    }

    /**
     * 质量校验：正值且最多 6 位小数；负量或超精度 400。
     */
    private void validateMass(BigDecimal mass, String field) {
        if (mass.signum() <= 0) {
            throw ApiException.badRequest(field + " 必须为正值（单位克）");
        }
        if (mass.stripTrailingZeros().scale() > MASS_SCALE) {
            throw ApiException.badRequest(field + " 最多 6 位小数（单位克）");
        }
    }

    private RecipeRepository.BatchRecipeRow bindingOrThrow(String batchKey) {
        return recipeRepo.findBatchRecipe(batchKey)
                .orElseThrow(() -> ApiException.unprocessable("批次未绑定配方，不能称量或更正: " + batchKey));
    }

    /**
     * 已放行/已拆分/已召回/已拒绝的批次称量链冻结，不再接受称量或更正。
     */
    private void assertWeighable(BatchRepository.BatchRow batch) {
        if (isFrozen(batch.status())) {
            throw ApiException.conflict("批次状态 " + batch.status() + " 称量链已冻结，不能再称量或更正");
        }
    }

    private boolean isFrozen(String status) {
        return BatchStatus.RELEASED.name().equals(status)
                || BatchStatus.REJECTED.name().equals(status)
                || BatchStatus.RECALLED.name().equals(status)
                || BatchStatus.SPLIT.name().equals(status);
    }

    private void checkVersion(RecipeRepository.BatchRecipeRow binding, Long expectedVersion) {
        if (binding.stateVersion() != expectedVersion) {
            throw ApiException.conflict("期望版本不匹配: 当前称量链版本 " + binding.stateVersion()
                    + "，请求期望 " + expectedVersion);
        }
    }

    private String requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        return actorId.trim();
    }

    private boolean sameContent(List<RecipeRepository.IngredientRow> existing,
                                List<CreateRecipeRequest.IngredientSpec> requested) {
        if (existing.size() != requested.size()) {
            return false;
        }
        for (int i = 0; i < existing.size(); i++) {
            if (!existing.get(i).ingredient().equals(requested.get(i).name())
                    || existing.get(i).percentage().compareTo(requested.get(i).percentage()) != 0) {
                return false;
            }
        }
        return true;
    }

    private RecipeResponse toRecipeResponse(RecipeRepository.RecipeVersionRow version,
                                            List<RecipeRepository.IngredientRow> ingredients) {
        return new RecipeResponse(version.recipeKey(), version.version(),
                ingredients.stream()
                        .map(i -> new RecipeResponse.IngredientEntry(i.ingredient(), i.percentage()))
                        .toList(),
                Instant.parse(version.createdAt()));
    }

    /**
     * 幂等身份：操作、操作者、资源及全部参数和期望版本，经结构化 JSON 指纹比较。
     */
    private String fingerprint(String operation, String operator, String resource,
                               Map<String, Object> params, Long expectedVersion) {
        Map<String, Object> identity = new TreeMap<>();
        identity.put("operation", operation);
        identity.put("operator", operator);
        identity.put("resource", resource);
        identity.put("params", params);
        if (expectedVersion != null) {
            identity.put("expectedVersion", expectedVersion);
        }
        return idem.structuredFingerprint(identity);
    }

    private BigDecimal norm(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private String now() {
        return Instant.now().toString();
    }
}
