// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock;

/**
 * Public project URLs used in About, first launch, etc.
 */
public final class ProjectUrls {

    /** Repository of this app (no trailing slash). */
    public static final String GITHUB_REPO = "https://github.com/denisimmoos/mighty-calendar-alarms";

    /** Branch the calendar-only app is built from. */
    private static final String BRANCH = "feature/calendar-only";

    /** Feature list of the app (README on its branch). */
    public static final String GITHUB_FEATURES = GITHUB_REPO + "/tree/" + BRANCH + "?tab=readme-ov-file#-features";

    public static final String GITHUB_LICENSE = GITHUB_REPO + "/blob/" + BRANCH + "/LICENSE";

    private ProjectUrls() {
    }

    /**
     * What's new: the app has no releases yet, so this is the change history of its branch.
     */
    public static String githubReleaseTag(String versionTag) {
        return GITHUB_REPO + "/commits/" + BRANCH;
    }
}
