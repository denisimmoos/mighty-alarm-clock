// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.text.HtmlCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns calendar event text into what the alarm screen shows.
 */
public final class CalendarEventText {

    private static final String GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps";

    /** Google Calendar stores descriptions written on the web as HTML. */
    private static final Pattern HTML_TAG = Pattern.compile("<(br|p|div|span|b|i|u|a|ul|ol|li|strong|em)\\b[^>]*>",
        Pattern.CASE_INSENSITIVE);

    /** Longest description shown; the rest is cut off. */
    private static final int MAX_DESCRIPTION_LENGTH = 2000;

    private CalendarEventText() {
    }

    /**
     * @return the description as plain text, with HTML formatting removed if it has any.
     */
    public static CharSequence toDisplayText(String description) {
        String text = description.length() > MAX_DESCRIPTION_LENGTH
            ? description.substring(0, MAX_DESCRIPTION_LENGTH) + "…"
            : description;
        if (HTML_TAG.matcher(text).find()) {
            text = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_COMPACT).toString();
        }
        return text.trim();
    }

    /**
     * @return ways to show the place, best first: Google Maps, any maps app, then the Google Maps
     * website.
     */
    /**
     * @return the whole minutes until the event, rounded up, so an alarm 10 minutes before says
     * 10 minutes and not 9.
     */
    public static long minutesUntil(long begin, long now) {
        final long ms = begin - now;
        return ms <= 0 ? 0 : (ms + 59_999) / 60_000;
    }

    /**
     * @return for example "2 h 15 min" for the time until the event, or {@code null} once less
     * than a minute is left, as for the alarm at the event start.
     */
    @androidx.annotation.Nullable
    public static String formatTimeUntil(Context context, long begin, long now) {
        if (begin - now < 60_000) {
            return null;
        }
        final long minutes = minutesUntil(begin, now);
        final long hours = minutes / 60;
        final long rest = minutes % 60;
        if (hours == 0) {
            return context.getString(com.best.deskclock.R.string.calendar_alarm_duration_minutes, rest);
        }
        if (rest == 0) {
            return context.getString(com.best.deskclock.R.string.calendar_alarm_duration_hours, hours);
        }
        return context.getString(com.best.deskclock.R.string.calendar_alarm_duration_hours_minutes, hours, rest);
    }

    public static List<Intent> createMapIntents(String location) {
        final List<Intent> intents = new ArrayList<>(3);
        final Uri geo = Uri.parse("geo:0,0?q=" + Uri.encode(location));
        intents.add(new Intent(Intent.ACTION_VIEW, geo).setPackage(GOOGLE_MAPS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        intents.add(new Intent(Intent.ACTION_VIEW, geo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        intents.add(new Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(location)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return intents;
    }
}
