package dev.mcai.companion.communication;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Conservative local safety net for obvious gameplay imperatives.
 *
 * <p>The language model remains the general conversation/task classifier.
 * This class exists because a conversational acknowledgement must never be
 * allowed to say "I'm following" while leaving the gameplay goal unchanged.
 * It recognizes only high-confidence commands and explicit confirmations of
 * the immediately preceding Agent proposal. It does not choose a skill or
 * mutate the world.</p>
 */
final class PlayerTaskIntent {
    private static final int MAX_CONTEXT_CODE_POINTS = 768;
    private static final Set<String> AFFIRMATIONS = Set.of(
            "行",
            "好",
            "好的",
            "可以",
            "嗯",
            "嗯嗯",
            "去吧",
            "做吧",
            "开始吧",
            "yes",
            "yeah",
            "yep",
            "ok",
            "okay",
            "sure",
            "do it",
            "go ahead"
    );
    private static final Pattern ENGLISH_IMPERATIVE = Pattern.compile(
            "(?i)(?:^|\\b)(?:please\\s+|could\\s+you\\s+|can\\s+you\\s+|"
                + "would\\s+you\\s+)?(?:follow|come|go|walk|run|flee|"
                + "escape|craft|make|drop|give|bring|gather|collect|mine|"
                + "chop|cut|build|place|plant|harvest|attack|fight|defend|"
                + "guard|explore|search|find|loot|equip|wear|eat|drink|"
                + "sail|drive|open|close|use|help|rescue|look\\s+around)"
                + "(?:\\b|$)"
    );
    private static final Pattern ENGLISH_PROPOSAL = Pattern.compile(
            "(?i)(?:would you like me to|do you want me to|should i|"
                + "shall i|want me to)"
    );

    private PlayerTaskIntent() {
    }

    static Result classify(
            final String message,
            final String ordinaryGoalText,
            final Optional<String> previousAgentSpeech
    ) {
        return classify(
                message,
                ordinaryGoalText,
                previousAgentSpeech,
                Optional.empty()
        );
    }

    static Result classify(
            final String message,
            final String ordinaryGoalText,
            final Optional<String> previousAgentSpeech,
            final Optional<String> currentGoal
    ) {
        final String normalized = normalize(message);
        final String lower = normalized.toLowerCase(Locale.ROOT);
        final Optional<String> previous = Objects.requireNonNull(
                previousAgentSpeech,
                "previousAgentSpeech"
        ).map(String::strip).filter(value -> !value.isEmpty());
        final Optional<String> activeGoal = Objects.requireNonNull(
                currentGoal,
                "currentGoal"
        ).map(String::strip).filter(value -> !value.isEmpty());

        /*
         * "走啊" and "来啊" are natural follow-up nudges after "跟我走".
         * Replacing the active follow goal with either two-character fragment
         * loses the target identity and cancels a running follow skill. Keep
         * the already-bound goal revision instead.
         */
        if (isFollowContinuation(normalized)
                && activeGoal.filter(
                        PlayerTaskIntent::isFollowRequest
                ).isPresent()) {
            return new Result(
                    true,
                    activeGoal.orElseThrow(),
                    "active_follow_continuation",
                false
            );
        }

        /*
         * A natural teammate exchange often has two turns: "I dropped you a
         * golden apple; eat it" followed by "then please eat it now". The
         * second turn intentionally omits the item name. Replacing the goal
         * would discard the first turn's object binding and lets the planner
         * treat "eat it" as an unspecified action. Preserve only these short
         * urgings when the active goal already names the food; a new named
         * food request remains a replacement goal.
         */
        if (isFoodConsumptionContinuation(normalized)
                && activeGoal.filter(
                        PlayerTaskIntent::isNamedFoodConsumptionRequest
                ).isPresent()) {
            return new Result(
                    true,
                    activeGoal.orElseThrow(),
                    "active_food_consumption_continuation",
                    false
            );
        }

        if (isAffirmation(lower)
                && previous.filter(
                        PlayerTaskIntent::containsProposal
                ).isPresent()) {
            final String proposal = bounded(
                    previous.orElseThrow(),
                    MAX_CONTEXT_CODE_POINTS
            );
            return new Result(
                    true,
                    "执行玩家刚刚确认的行动。AI上一句提议为："
                        + proposal + "；玩家确认：" + normalized,
                    "confirmed_previous_proposal"
            );
        }

        if (reportedImmediateThreat(normalized)
                || isFollowRequest(normalized)
                || obviousChineseImperative(normalized)
                || containsExplicitChineseRequestClause(normalized)
                || obviousEnglishImperative(lower)) {
            final String goal = normalize(ordinaryGoalText);
            return new Result(
                    true,
                    goal.isEmpty() ? normalized : goal,
                    reportedImmediateThreat(normalized)
                            ? "immediate_threat_report"
                            : isFollowRequest(normalized)
                                    ? "immediate_follow_request"
                                    : "obvious_gameplay_imperative",
                    true
            );
        }
        return new Result(false, "", "not_obvious_task", false);
    }

    static boolean looksLikeActionCommitment(final String speech) {
        final String value = normalize(speech);
        if (value.isEmpty()) {
            return false;
        }
        final String compact = value
                .replaceAll("[\\s!！。.,，~～]+", "")
                .toLowerCase(Locale.ROOT);
        if (compact.length() <= 16 && Set.of(
                "好",
                "好的",
                "好好好",
                "收到",
                "明白",
                "明白了",
                "没问题",
                "可以",
                "行",
                "ok",
                "okay",
                "gotit",
                "sure",
                "understood",
                "onit",
                "willdo"
        ).contains(compact)) {
            return true;
        }
        final String lower = value.toLowerCase(Locale.ROOT);
        return value.startsWith("目标已接受")
                || value.startsWith("任务已接受")
                || value.startsWith("任务已创建")
                || value.startsWith("已接受任务")
                || value.startsWith("开始执行")
                || value.startsWith("开始行动")
                || value.startsWith("正在执行")
                || value.startsWith("正在前往")
                || value.startsWith("正在跟随")
                || value.startsWith("正在移动")
                || value.startsWith("已经开始")
                || value.contains("我这就")
                || value.contains("我马上")
                || value.contains("我先去")
                || value.contains("我跟着")
                || value.contains("我来了")
                || value.contains("我来啦")
                || value.contains("我来帮")
                || value.contains("我去")
                || value.contains("开始做")
                || value.contains("开始准备")
                || value.contains("正准备")
                || value.contains("正在观察")
                || value.contains("正在寻找")
                || value.contains("正在上岸")
                || value.contains("我会去")
                || value.contains("我将前往")
                || value.contains("我将开始")
                || lower.startsWith("i'll ")
                || lower.startsWith("i will ")
                || lower.startsWith("i'm going to ")
                || lower.startsWith("task accepted")
                || lower.startsWith("goal accepted")
                || lower.startsWith("task created")
                || lower.startsWith("starting now")
                || lower.startsWith("proceeding ")
                || lower.startsWith("i'm heading ")
                || lower.startsWith("i am heading ")
                || lower.startsWith("starting ")
                || lower.startsWith("surveying ")
                || lower.startsWith("gathering ")
                || lower.startsWith("following ");
    }

    /**
     * A small, unambiguous teammate safety command.  Cancellation is handled
     * locally so it remains responsive during a slow or unavailable model;
     * it still goes through GoalCoordinator's evaluation lock and safe skill
     * checkpoint rather than directly mutating the body.
     */
    static boolean isCancellationRequest(final String message) {
        final String normalized = normalize(message)
                .replaceAll("[.!?,，。！？~～]+$", "")
                .strip()
                .toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        if (Set.of(
                "停下",
                "停止",
                "别动",
                "不要动",
                "停一下",
                "取消",
                "取消任务",
                "取消刚才的任务"
        ).contains(normalized)) {
            return true;
        }
        return normalized.matches(
                "(?:please\\s+)?(?:stop|stop moving|hold|wait|cancel|"
                        + "abort|pause)(?:\\s+now)?"
        );
    }

    /**
     * Handles only the two low-risk social turns that should never wait for a
     * 90-second gameplay request slot: a greeting and an explicit language
     * capability question. The caller still requires the normal addressed
     * chat boundary and a verified model gateway; this is not an offline
     * speech or gameplay fallback.
     */
    static Optional<String> immediateSocialReply(final String message) {
        final String value = normalize(message)
                .replaceAll("[!！。,.，?？~～]+$", "")
                .strip();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        final String lower = value.toLowerCase(Locale.ROOT);
        final boolean chinese = prefersChinese(value);
        if (lower.equals("hello")
                || lower.equals("hi")
                || lower.equals("hey")
                || lower.startsWith("hello ")
                || lower.startsWith("hi ")
                || lower.startsWith("hey ")) {
            return Optional.of(chinese
                    ? "你好，我在线。"
                    : "Hi, I am here.");
        }
        if (lower.contains("speak chinese")
                || lower.contains("say chinese")
                || lower.contains("can you speak chinese")
                || value.contains("说中文")
                || value.contains("讲中文")) {
            return Optional.of("可以，我会说中文。");
        }
        if (lower.contains("speak english")
                || lower.contains("say english")
                || lower.contains("can you speak english")) {
            return Optional.of("Yes, I can speak English.");
        }
        return Optional.empty();
    }

    /**
     * A model-selected task must not turn a conversational question into a
     * durable gameplay goal merely because the provider returned ASK_PLAYER.
     * The local imperative classifier remains the authority for obvious
     * commands; this narrower guard covers the provider's common language,
     * greeting and status-question mistakes without restricting unfamiliar
     * imperative Minecraft tasks that contain no question shape.
     */
    static boolean looksLikeConversationalQuestion(final String message) {
        final String value = normalize(message);
        if (value.isEmpty()) {
            return true;
        }
        if (value.endsWith("?")
                || value.endsWith("？")
                || value.endsWith("吗")
                || value.endsWith("么")
                || value.endsWith("呢")) {
            return true;
        }
        final String lower = value.toLowerCase(Locale.ROOT);
        return lower.equals("hello")
                || lower.equals("hi")
                || lower.equals("hey")
                || lower.startsWith("hello ")
                || lower.startsWith("hi ")
                || lower.startsWith("hey ")
                || lower.contains("speak chinese")
                || lower.contains("say chinese")
                || lower.contains("speak ")
                    && lower.contains("language")
                || value.contains("说中文")
                || value.contains("讲中文")
                || value.contains("什么意");
    }

    static boolean prefersChinese(final String message) {
        return normalize(message).codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint)
                        == Character.UnicodeScript.HAN
        );
    }

    private static boolean obviousChineseImperative(
            final String value
    ) {
        if (value.isEmpty()) {
            return false;
        }
        if (value.startsWith("请问")
                || value.endsWith("吗")
                || value.endsWith("吗？")
                || value.endsWith("么")
                || value.endsWith("么？")) {
            return false;
        }
        final boolean directPrefix = value.startsWith("请")
                || value.startsWith("你去")
                || value.startsWith("你先")
                || value.startsWith("你快")
                || value.startsWith("快点")
                || value.startsWith("快跑")
                || value.startsWith("跟")
                || value.startsWith("过来")
                || value.startsWith("走")
                || value.startsWith("跑")
                || value.startsWith("逃")
                || value.startsWith("合成")
                || value.startsWith("丢")
                || value.startsWith("扔")
                || value.startsWith("给我")
                || value.startsWith("拿")
                || value.startsWith("取")
                || value.startsWith("砍")
                || value.startsWith("挖")
                || value.startsWith("采")
                || value.startsWith("收")
                || value.startsWith("种")
                || value.startsWith("建")
                || value.startsWith("造")
                || value.startsWith("放")
                || value.startsWith("打开")
                || value.startsWith("关上")
                || value.startsWith("攻击")
                || value.startsWith("打")
                || value.startsWith("防御")
                || value.startsWith("吃")
                || value.startsWith("喝")
                || value.startsWith("穿")
                || value.startsWith("装备")
                || value.startsWith("观察")
                || value.startsWith("看看")
                || value.startsWith("看向")
                || value.startsWith("转过来")
                || value.startsWith("探索")
                || value.startsWith("搜刮")
                || value.startsWith("回家")
                || value.startsWith("救")
                || value.startsWith("帮我");
        if (directPrefix) {
            return true;
        }
        return value.contains("跟我走")
                || value.contains("跟上我")
                || value.contains("到我这里")
                || value.contains("来我这里")
                || value.contains("丢给我")
                || value.contains("扔给我")
                || value.contains("交给我")
                || value.contains("帮我砍")
                || value.contains("帮我挖")
                || value.contains("帮我找")
                || value.contains("帮我做")
                || value.contains("帮我建")
                || value.contains("帮我拿")
                || value.contains("帮我收")
                || value.contains("帮我种")
                || value.contains("快吃")
                || value.contains("赶紧吃")
                || value.contains("马上吃")
                || value.contains("吃吧")
                || value.contains("吃了吧")
                || value.contains("喝吧")
                || value.contains("穿上")
                || value.contains("装备上")
                || value.contains("看着我")
                || value.contains("看向我")
                || value.contains("转过来");
    }

    /**
     * Natural chat often starts with context before the actual command, for
     * example "we are starting from zero; please chop a nearby tree". The
     * prefix-only recognizer deliberately misses that shape and unnecessarily
     * spends a full model round trip merely deciding that it is a task. This
     * bounded clause check identifies only an explicit non-questioning 请
     * request followed by a gameplay verb; it still leaves the model in
     * charge of selecting the high-level action.
     */
    private static boolean containsExplicitChineseRequestClause(
            final String value
    ) {
        if (value.isEmpty()
                || value.contains("请问")
                || value.endsWith("?")
                || value.endsWith("？")) {
            return false;
        }
        final int request = value.indexOf('请');
        if (request < 0) {
            return false;
        }
        final String clause = bounded(
                value.substring(request + 1),
                64
        );
        return clause.matches(
                ".*(?:跟|来|去|走|跑|逃|合成|制作|丢|扔|给|拿|取|"
                        + "砍|挖|采|收|种|建|造|放|打开|关闭|攻击|打|"
                        + "防御|吃|喝|穿|装备|观察|看|探索|搜刮|救).*"
        );
    }

    static boolean isFollowRequest(final String value) {
        final String normalized = normalize(value);
        /*
         * Chat is a player-facing channel, so short follow requests commonly
         * arrive with a comma, ideographic comma, or a trailing ellipsis
         * ("跟我，走" / "跟我……").  Matching the raw string only made
         * those messages fall through to a slow model round trip, where a
         * conversational acknowledgement could be emitted without a bound
         * follow goal.  Punctuation is not gameplay intent; remove it only
         * for this conservative phrase match and retain the original text in
         * the installed goal for audit/context.
         */
        final String compact = compactIntentText(normalized);
        /* Keep spaces for the English phrases below; compact matching is
         * only needed for the CJK punctuation variants. */
        final String lower = normalized.toLowerCase(Locale.ROOT);
        /*
         * A short "跟我" is a complete, natural player command in chat.
         * Treating it as ordinary conversation left the model free to say
         * "好的" without installing the server-bound player identity, so a
         * later follow skill had no authoritative target to act on.  Keep
         * the broader prefix intentionally limited to this exact Chinese
         * follow construction; unrelated text still goes through the model.
         */
        return compact.equals("跟我")
                || compact.startsWith("跟我走")
                || compact.startsWith("跟我来")
                || compact.startsWith("跟我去")
                || compact.contains("跟我走")
                || compact.contains("跟我来")
                || compact.contains("跟我去")
                || compact.contains("跟着我")
                || compact.contains("跟上我")
                || compact.contains("跟过来")
                || compact.contains("跟着走")
                || compact.startsWith("跟上")
                || compact.startsWith("过来")
                || compact.contains("到我这里")
                || compact.contains("来我这里")
                || lower.contains("follow me")
                || lower.contains("come with me")
                || lower.contains("come here")
                || lower.contains("come to me")
                || lower.contains("stay with me");
    }

    private static String compactIntentText(final String value) {
        final StringBuilder compact = new StringBuilder(value.length());
        value.codePoints().filter(codePoint ->
                Character.isLetterOrDigit(codePoint)
        ).forEach(compact::appendCodePoint);
        return compact.toString();
    }

    private static boolean isFollowContinuation(final String value) {
        final String normalized = normalize(value)
                .replaceAll("[!！。,.，?？~～]+$", "")
                .strip();
        final String lower = normalized.toLowerCase(Locale.ROOT);
        return normalized.equals("走")
                || normalized.equals("走啊")
                || normalized.equals("来")
                || normalized.equals("来啊")
                || normalized.equals("过来")
                || normalized.equals("过来啊")
                || normalized.equals("跟上")
                || normalized.equals("跟上啊")
                || normalized.equals("快点")
                || lower.equals("come on")
                || lower.equals("keep up")
                || lower.equals("move");
    }

    private static boolean isFoodConsumptionContinuation(
            final String value
    ) {
        final String normalized = normalize(value)
                .replaceAll("[!！。,.，?？~～]+$", "")
                .strip();
        final String compact = compactIntentText(normalized);
        final String lower = normalized.toLowerCase(Locale.ROOT);
        return compact.equals("吃")
                || compact.equals("吃吧")
                || compact.equals("快吃")
                || compact.equals("快吃吧")
                || compact.equals("赶紧吃")
                || compact.equals("赶紧吃吧")
                || compact.equals("马上吃")
                || compact.equals("马上吃吧")
                || compact.equals("把它吃了")
                || compact.equals("把它吃了吧")
                || compact.contains("快吃吧")
                || compact.contains("吃了吧")
                || lower.equals("eat it")
                || lower.equals("eat it now")
                || lower.equals("please eat it")
                || lower.equals("go ahead and eat it");
    }

    private static boolean isNamedFoodConsumptionRequest(
            final String value
    ) {
        final String normalized = normalize(value);
        final String lower = normalized.toLowerCase(Locale.ROOT);
        final boolean consumes = normalized.contains("吃")
                || normalized.contains("喝")
                || normalized.contains("食用")
                || lower.matches(".*\\b(?:eat|consume|drink)\\b.*");
        final boolean namedFood = normalized.contains("金苹果")
                || lower.contains("golden apple")
                || lower.contains("enchanted golden apple");
        return consumes && namedFood;
    }

    private static boolean obviousEnglishImperative(
            final String lower
    ) {
        if (lower.isEmpty()
                || lower.matches(
                        ".*\\b(?:speak|say|talk)\\b.*"
                )) {
            return false;
        }
        return ENGLISH_IMPERATIVE.matcher(lower).find();
    }

    private static boolean reportedImmediateThreat(
            final String value
    ) {
        if (value.endsWith("吗")
                || value.endsWith("吗？")
                || value.endsWith("?")
                || value.endsWith("？")) {
            return false;
        }
        final boolean threat = value.contains("僵尸")
                || value.contains("骷髅")
                || value.contains("苦力怕")
                || value.contains("蜘蛛")
                || value.contains("怪物")
                || value.contains("敌人");
        final boolean nearby = value.contains("后面")
                || value.contains("身后")
                || value.contains("旁边")
                || value.contains("附近")
                || value.contains("打你")
                || value.contains("攻击你");
        return threat && nearby;
    }

    private static boolean isAffirmation(final String lower) {
        final String stripped = lower
                .replaceAll("[!！。,.，?？~～]+$", "")
                .strip();
        return AFFIRMATIONS.contains(stripped);
    }

    private static boolean containsProposal(final String prior) {
        return prior.contains("要我")
                && (prior.contains("吗") || prior.contains("？"))
                || prior.contains("需要我")
                && (prior.contains("吗") || prior.contains("？"))
                || ENGLISH_PROPOSAL.matcher(prior).find();
    }

    private static String normalize(final String value) {
        return Objects.requireNonNullElse(value, "").strip();
    }

    private static String bounded(
            final String value,
            final int maximumCodePoints
    ) {
        final int count = value.codePointCount(0, value.length());
        if (count <= maximumCodePoints) {
            return value;
        }
        return value.substring(
                0,
                value.offsetByCodePoints(0, maximumCodePoints)
        );
    }

    record Result(
            boolean task,
            String goalText,
            String reason,
            boolean replacesGoal
    ) {
        Result(
                final boolean task,
                final String goalText,
                final String reason
        ) {
            this(task, goalText, reason, task);
        }

        Result {
            Objects.requireNonNull(goalText, "goalText");
            Objects.requireNonNull(reason, "reason");
            if (!task && !goalText.isEmpty()) {
                throw new IllegalArgumentException(
                        "A non-task cannot carry a goal"
                );
            }
            if (!task && replacesGoal) {
                throw new IllegalArgumentException(
                        "A non-task cannot replace a goal"
                );
            }
        }
    }
}
