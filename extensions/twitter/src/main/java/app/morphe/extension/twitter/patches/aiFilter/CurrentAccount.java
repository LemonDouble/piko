/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.aiFilter;

import androidx.annotation.Nullable;

import com.twitter.util.user.UserIdentifier;

import app.morphe.extension.crimera.PikoUtils;

public class CurrentAccount {

    // Returns null when X's current-user holder cannot be read, which keeps the filter off.
    // Throwable is caught because a renamed method in a newer X version raises NoSuchMethodError.
    @Nullable
    public static String id() {
        try {
            UserIdentifier current = UserIdentifier.getCurrent();
            String id = current == null ? null : current.getStringId();
            return id == null || id.isEmpty() ? null : id;
        } catch (Throwable ex) {
            PikoUtils.logger(ex);
            return null;
        }
    }
}
