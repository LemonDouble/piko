/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.aiFilter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.shared.Utils;

public class FilterVault {
    private static final String ITEMS_FILE = "ai_filter_vault.json";
    private static final String USAGE_FILE = "ai_filter_usage.json";
    private static final int MAX_ITEMS = 1000;
    private static final long RETENTION_MILLIS = TimeUnit.DAYS.toMillis(7);

    // Newest first.
    private static List<JSONObject> items;

    public static synchronized void addItems(List<JSONObject> newItems) {
        if (newItems.isEmpty()) return;
        List<JSONObject> current = loadedItems();
        Set<String> knownIds = new HashSet<>();
        for (JSONObject item : current) {
            knownIds.add(item.optString("id"));
        }
        for (JSONObject item : newItems) {
            if (knownIds.add(item.optString("id"))) {
                current.add(0, item);
            }
        }
        pruneOldItems(current);
        writeFile(ITEMS_FILE, new JSONArray(current).toString());
    }

    // Retention limits count all accounts together; only the listing is per account.
    public static synchronized List<JSONObject> getItems(String accountId) {
        List<JSONObject> current = loadedItems();
        pruneOldItems(current);
        List<JSONObject> accountItems = new ArrayList<>();
        for (JSONObject item : current) {
            if (accountId.equals(item.optString("account_id"))) accountItems.add(item);
        }
        return accountItems;
    }

    // Usage stats are kept so that the daily cost stays in line with the OpenRouter bill.
    public static synchronized void clearItems(String accountId) {
        List<JSONObject> current = loadedItems();
        current.removeIf(item -> accountId.equals(item.optString("account_id")));
        writeFile(ITEMS_FILE, new JSONArray(current).toString());
    }

    public static synchronized void recordUsage(UsageStats batch) {
        UsageStats today = getTodayUsage();
        today.add(batch);
        recordUsageAsToday(today);
    }

    private static void recordUsageAsToday(UsageStats usage) {
        try {
            writeFile(USAGE_FILE, usage.toJson().put("date", LocalDate.now().toString()).toString());
        } catch (Exception ex) {
            PikoUtils.logger(ex);
        }
    }

    // Lets the user start a fresh measurement before the day ends.
    public static synchronized void resetUsage() {
        recordUsageAsToday(new UsageStats());
    }

    public static synchronized UsageStats getTodayUsage() {
        try {
            JSONObject usage = new JSONObject(readFile(USAGE_FILE));
            if (LocalDate.now().toString().equals(usage.optString("date"))) {
                return UsageStats.fromJson(usage);
            }
        } catch (Exception ignored) {
        }
        return new UsageStats();
    }

    private static List<JSONObject> loadedItems() {
        if (items != null) return items;
        items = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(readFile(ITEMS_FILE));
            for (int index = 0; index < saved.length(); index++) {
                items.add(saved.getJSONObject(index));
            }
        } catch (Exception ignored) {
        }
        return items;
    }

    private static void pruneOldItems(List<JSONObject> current) {
        long oldestAllowed = System.currentTimeMillis() - RETENTION_MILLIS;
        current.removeIf(item -> item.optLong("filtered_at") < oldestAllowed);
        while (current.size() > MAX_ITEMS) {
            current.remove(current.size() - 1);
        }
    }

    private static File fileOf(String name) {
        return new File(Utils.getContext().getFilesDir(), name);
    }

    private static String readFile(String name) throws Exception {
        return new String(Files.readAllBytes(fileOf(name).toPath()), StandardCharsets.UTF_8);
    }

    private static void writeFile(String name, String content) {
        try (FileOutputStream output = new FileOutputStream(fileOf(name))) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            PikoUtils.logger(ex);
        }
    }
}
