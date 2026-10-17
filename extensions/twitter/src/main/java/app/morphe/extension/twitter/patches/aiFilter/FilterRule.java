/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.aiFilter;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.crimera.settings.StringSetting;
import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.settings.Settings;

public class FilterRule {
    private static final double DEFAULT_THRESHOLD = 0.8;

    public final String text;
    public final double threshold;

    private FilterRule(String text, double threshold) {
        this.text = text;
        this.threshold = threshold;
    }

    // An empty rule slot means the slot is unused.
    public static List<FilterRule> loadFromSettings(String accountId) {
        List<FilterRule> rules = new ArrayList<>();
        for (int slot = 0; slot < Settings.AI_FILTER_RULE_SLOTS; slot++) {
            String text = Utils.getStringPref(Settings.aiFilterRuleText(accountId, slot)).trim();
            if (text.isEmpty()) continue;
            rules.add(new FilterRule(text, parseThreshold(Settings.aiFilterRuleThreshold(accountId, slot))));
        }
        return rules;
    }

    private static double parseThreshold(StringSetting setting) {
        try {
            double threshold = Double.parseDouble(Utils.getStringPref(setting).trim());
            if (threshold >= 0 && threshold <= 1) return threshold;
        } catch (NumberFormatException ignored) {
        }
        return DEFAULT_THRESHOLD;
    }

    // Cached probabilities stay valid while the rule texts are unchanged; thresholds are applied afterwards.
    public static String cacheSignature(List<FilterRule> rules) {
        StringBuilder signature = new StringBuilder();
        for (FilterRule rule : rules) {
            signature.append(rule.text).append('\n');
        }
        return signature.toString();
    }
}
