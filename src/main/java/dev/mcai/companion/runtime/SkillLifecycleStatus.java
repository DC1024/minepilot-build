package dev.mcai.companion.runtime;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Player-facing interpretation of a server-owned skill transition.
 *
 * <p>The model cannot create these statuses.  They are derived only from the
 * {@code SkillSupervisor} notices emitted after a skill has been accepted or
 * has produced a terminal result.  This keeps the companion conversational
 * without allowing a speech-only response to masquerade as gameplay.</p>
 */
record SkillLifecycleStatus(Type type, String skillName) {
    enum Type {
        STARTED,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    SkillLifecycleStatus {
        type = Objects.requireNonNull(type, "type");
        skillName = Objects.requireNonNull(skillName, "skillName").strip();
        if (skillName.isEmpty() || skillName.length() > 64
                || !skillName.matches("[a-z0-9]+(?:[._-][a-z0-9]+)*")) {
            throw new IllegalArgumentException("invalid skill name");
        }
    }

    static Optional<SkillLifecycleStatus> parse(final String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        final String[] parts = code.split("\\.", 2);
        if (parts.length != 2 || parts[1].isBlank()) {
            return Optional.empty();
        }
        final Type type = switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "skill_started" -> Type.STARTED;
            case "skill_completed" -> Type.COMPLETED;
            case "skill_failed" -> Type.FAILED;
            case "skill_cancelled" -> Type.CANCELLED;
            default -> null;
        };
        if (type == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SkillLifecycleStatus(type, parts[1]));
        } catch (IllegalArgumentException invalidAuditCode) {
            return Optional.empty();
        }
    }

    String key() {
        return type.name() + ":" + skillName;
    }

    String chineseMessage() {
        final String display = displayName(skillName);
        return switch (type) {
            case STARTED -> "开始执行：" + display + "。完成后我会继续汇报。";
            case COMPLETED -> "动作完成：" + display + "；我正在检查下一步。";
            case FAILED -> "动作未完成：" + display + "；我正在重新规划，不会假装已经完成。";
            case CANCELLED -> "动作已停止：" + display + "。";
        };
    }

    private static String displayName(final String skill) {
        return switch (skill) {
            case "follow_entity" -> "跟随玩家";
            case "survey_surroundings" -> "搜索周围环境";
            case "consume_owned_food" -> "进食";
            case "navigate_to_waypoint" -> "前往目标地点";
            case "mine_block", "break_block" -> "挖掘方块";
            case "gather_nearby_wood" -> "砍树并收集木材";
            case "place_block" -> "放置方块";
            case "attack_entity", "fight_ender_dragon" -> "战斗";
            case "open_container", "container_transfer" -> "整理容器";
            default -> skill;
        };
    }
}
