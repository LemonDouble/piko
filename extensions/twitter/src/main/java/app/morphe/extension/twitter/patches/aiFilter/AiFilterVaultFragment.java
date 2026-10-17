/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.aiFilter;

import static app.morphe.extension.shared.StringRef.str;

import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.twitter.Utils;

@SuppressWarnings("deprecation")
public class AiFilterVaultFragment extends Fragment {
    // Fixed rate for a rough KRW estimate of the daily cost.
    private static final double KRW_PER_USD = 1400;
    private static final int DAYS_PER_MONTH = 30;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Context context = getActivity();
        String accountId = CurrentAccount.id();
        List<JSONObject> items = accountId == null ? new ArrayList<>() : FilterVault.getItems(accountId);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView usageHeader = textView(context, usageSummary(), 13, secondaryTextColor(context));
        usageHeader.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
        root.addView(usageHeader);

        if (items.isEmpty()) {
            String message = str(accountId == null ? "piko_ai_filter_account_unknown" : "piko_ai_filter_vault_empty");
            TextView emptyMessage = textView(context, message, 15, secondaryTextColor(context));
            emptyMessage.setPadding(dp(context, 16), dp(context, 24), dp(context, 16), dp(context, 24));
            root.addView(emptyMessage);
            return root;
        }

        ListView listView = new ListView(context);
        listView.setAdapter(new VaultAdapter(context, items));
        listView.setOnItemClickListener((parent, view, position, id) ->
                PikoUtils.openUrl("https://x.com/i/status/" + items.get(position).optString("id"), true));
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    public static void showClearDialog(Context context) {
        String accountId = CurrentAccount.id();
        if (accountId == null) {
            PikoUtils.toast(str("piko_ai_filter_account_unknown"));
            return;
        }
        int itemCount = FilterVault.getItems(accountId).size();
        new AlertDialog.Builder(context)
                .setTitle(str("piko_pref_ai_filter_clear_vault"))
                .setMessage(String.format(str("piko_ai_filter_clear_vault_confirm"), itemCount))
                .setPositiveButton(str("ok"), (dialog, which) -> {
                    FilterVault.clearItems(accountId);
                    PikoUtils.toast(str("piko_ai_filter_clear_vault_done"));
                })
                .setNegativeButton(str("cancel"), null)
                .show();
    }

    private static String usageSummary() {
        UsageStats usage = FilterVault.getTodayUsage();
        StringBuilder summary = new StringBuilder()
                .append(String.format(Locale.US, str("piko_ai_filter_vault_usage"),
                        usage.judged, usage.inputTokens, usage.cost, usage.cost * KRW_PER_USD))
                .append('\n')
                .append(String.format(Locale.US, str("piko_ai_filter_vault_monthly_estimate"),
                        usage.cost * DAYS_PER_MONTH, usage.cost * DAYS_PER_MONTH * KRW_PER_USD))
                .append('\n')
                .append(String.format(Locale.US, str("piko_ai_filter_vault_failures"),
                        usage.failed(), usage.timeouts, usage.httpErrors, usage.otherErrors))
                .append('\n')
                .append(String.format(Locale.US, str("piko_ai_filter_vault_latency"),
                        usage.averageJudgeMillis(), usage.averageWaitMillis()));
        if (!usage.lastError.isEmpty()) {
            summary.append('\n').append(String.format(str("piko_ai_filter_vault_last_error"), usage.lastError));
        }
        return summary.toString();
    }

    private static class VaultAdapter extends BaseAdapter {
        private final Context context;
        private final List<JSONObject> items;

        VaultAdapter(Context context, List<JSONObject> items) {
            this.context = context;
            this.items = items;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            JSONObject item = items.get(position);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));

            row.addView(textView(context, authorLine(item), 14, primaryTextColor(context)));

            TextView body = textView(context, item.optString("text"), 15, primaryTextColor(context));
            body.setPadding(0, dp(context, 4), 0, 0);
            row.addView(body);

            String quotedText = item.optString("quoted_text");
            if (!quotedText.isEmpty()) {
                TextView quote = textView(context, str("piko_ai_filter_vault_quote") + " " + quotedText, 13, secondaryTextColor(context));
                quote.setMaxLines(3);
                quote.setEllipsize(TextUtils.TruncateAt.END);
                quote.setPadding(dp(context, 12), dp(context, 4), 0, 0);
                row.addView(quote);
            }

            TextView meta = textView(context, metaLine(item), 12, secondaryTextColor(context));
            meta.setPadding(0, dp(context, 6), 0, 0);
            row.addView(meta);
            return row;
        }

        private static String authorLine(JSONObject item) {
            String line = item.optString("author_name") + "  @" + item.optString("author_handle");
            String retweetedBy = item.optString("retweeted_by");
            if (!retweetedBy.isEmpty()) {
                line += "  ·  " + String.format(str("piko_ai_filter_vault_retweeted_by"), retweetedBy);
            }
            return line;
        }

        private static String metaLine(JSONObject item) {
            CharSequence filteredAgo = DateUtils.getRelativeTimeSpanString(
                    item.optLong("filtered_at"), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
            return String.format(Locale.US, str("piko_ai_filter_vault_meta"),
                    item.optString("filter"), item.optDouble("probability"), item.optInt("media_count"), filteredAgo);
        }
    }

    private static TextView textView(Context context, String text, int sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        return view;
    }

    private static int primaryTextColor(Context context) {
        return Utils.resolveColor(context, "coreColorPrimaryText");
    }

    private static int secondaryTextColor(Context context) {
        return Utils.resolveColor(context, "coreColorSecondaryText");
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }
}
