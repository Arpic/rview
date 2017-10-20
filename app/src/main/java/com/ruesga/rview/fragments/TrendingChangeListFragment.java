/*
 * Copyright (C) 2017 Jorge Ruesga
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ruesga.rview.fragments;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;

import com.google.gson.reflect.TypeToken;
import com.ruesga.rview.R;
import com.ruesga.rview.gerrit.GerritApi;
import com.ruesga.rview.gerrit.filter.ChangeQuery;
import com.ruesga.rview.gerrit.model.AccountInfo;
import com.ruesga.rview.gerrit.model.ChangeInfo;
import com.ruesga.rview.gerrit.model.ChangeMessageInfo;
import com.ruesga.rview.gerrit.model.ChangeOptions;
import com.ruesga.rview.gerrit.model.ReviewerStatus;
import com.ruesga.rview.misc.CacheHelper;
import com.ruesga.rview.misc.ModelHelper;
import com.ruesga.rview.misc.SerializationManager;
import com.ruesga.rview.model.Account;
import com.ruesga.rview.preferences.Preferences;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;

public class TrendingChangeListFragment extends ChangeListByFilterFragment {

    private static final String TAG = "TrendingFragment";

    private static final int FETCH_COUNT = 75;
    private static final Pattern VOTE_PATTERN = Pattern.compile(".*[+-]\\d.*");

    public static final int TRENDING_MAX_SCORE = 20;
    private static final int TRENDING_MIN_SCORE = 8;

    private boolean mForceRefresh;

    private static final List<ChangeOptions> OPTIONS = new ArrayList<ChangeOptions>() {{
        add(ChangeOptions.DETAILED_ACCOUNTS);
        add(ChangeOptions.LABELS);
        add(ChangeOptions.REVIEWED);
        add(ChangeOptions.MESSAGES);
        add(ChangeOptions.CURRENT_REVISION);
    }};

    public static TrendingChangeListFragment newInstance() {
        TrendingChangeListFragment fragment = new TrendingChangeListFragment();
        Bundle arguments = new Bundle();
        arguments.putBoolean(EXTRA_HAS_SEARCH, true);
        arguments.putBoolean(EXTRA_HAS_FAB, true);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public void fetchNewItems() {
        mForceRefresh = true;
        super.fetchNewItems();
    }

    @SuppressWarnings("ConstantConditions")
    protected List<ChangeInfo> doFetchChanges(Integer count, Integer start) {
        final ChangeQuery query = ChangeQuery.parse(getString(R.string.trending_query));
        final Context ctx = getActivity();
        final GerritApi api = ModelHelper.getGerritApi(ctx);

        // We don't want endless scroll (since we are going to fetch all available changes)
        notifyNoMoreItems();

        // Since trending data uses a lot of resources in server and client size, just
        // fetch data only if it is 1 hour aged
        List<ChangeInfo> changes = null;
        Account account = Preferences.getAccount(ctx);
        long age = CacheHelper.getTrendingChangesCacheAge(ctx, account);
        if (!mForceRefresh && System.currentTimeMillis() - age < DateUtils.HOUR_IN_MILLIS) {
            try {
                Type type = new TypeToken<List<ChangeInfo>>() {}.getType();
                changes = SerializationManager.getInstance().fromJson(
                        new String(CacheHelper.readTrendingChangesCache(ctx, account)), type);
            } catch (Exception ex) {
                Log.e(TAG, "Failed to read trending cache file", ex);
            }
        }

        // Fetch if needed
        if (changes == null) {
            changes = new ArrayList<>();

            // Fetch all the available changes
            int c = FETCH_COUNT;
            int s = 0;
            while (true) {
                List<ChangeInfo> fetched = api.getChanges(
                        query, c, Math.max(0, s), OPTIONS).blockingFirst();
                changes.addAll(fetched);
                if (fetched.size() < c) {
                    break;
                }
                s += c;
            }

            changes = extractTrendingChanges(changes);
            try {
                CacheHelper.writeTrendingChangesCache(ctx, account,
                        SerializationManager.getInstance().toJson(changes).getBytes());
            } catch (Exception ex) {
                Log.e(TAG, "Failed to serialize trending cache file", ex);
            }
        }

        mForceRefresh = false;
        return changes;
    }

    private List<ChangeInfo> extractTrendingChanges(List<ChangeInfo> changes) {
        // TRENDING RULES
        //  * Scoring over 20
        //  * Max trending changes = account_fetched_items preference
        //  * Only changes with a score >= TRENDING_MIN_SCORE are tagged as trending changes
        //  * Score weight:
        //      - Number of patchsets: 4 [3-20]
        //      - Number of votes: 3 [1-10]
        //      - Number of messages: 3 [1-20]
        //      - Number of unique accounts commenting: 4 [3-7]
        //      - Number of reviewers: 3 [5-15]
        //      - Updated in the last 2 hours: 3

        int maxItems = Preferences.getAccountFetchedItems(
                getContext(), Preferences.getAccount(getContext()));

        List<ChangeInfo> trending = new ArrayList<>();
        for (ChangeInfo change : changes) {
            long aged = System.currentTimeMillis() - change.updated.getTime();
            boolean aged2hours = aged < DateUtils.HOUR_IN_MILLIS * 2;
            change.trendingScore =
                    applyWeight(countNumberOfPatchsets(change), 4f, 3, 20) +
                    applyWeight(countNumberOfVotesOfNotRobotAccounts(change), 3f, 1, 10) +
                    applyWeight(countNumberOfMessagesFromNotRobotAccounts(change), 3f, 1, 20) +
                    applyWeight(countNumberOfNotRobotAccountsWithAComment(change), 4f, 3, 7) +
                    applyWeight(countNumberOfReviewers(change), 3f, 5, 15) +
                    (aged2hours ? 3 : 0);
            if (change.trendingScore >= TRENDING_MIN_SCORE) {
                trending.add(change);
            }
        }
        Collections.sort(trending, (c1, c2) -> {
            if (c1.trendingScore > c2.trendingScore) {
                return -1;
            }
            if (c1.trendingScore < c2.trendingScore) {
                return 1;
            }
            return c1.updated.compareTo(c2.updated) * -1;
        });

        if (trending.size() > maxItems) {
            return trending.subList(0, maxItems);
        }

        return trending;
    }

    private int applyWeight(int value, float weight, int min, int max) {
        if (value < min) {
            return 0;
        }
        if (value >= max) {
            return (int) weight;
        }

        return Math.round((value - min) * weight / (max - min));
    }

    // Counts the number of comments from user accounts (excluding robot accounts)
    private int countNumberOfMessagesFromNotRobotAccounts(ChangeInfo change) {
        int count = 0;
        if (change.messages != null) {
            for (ChangeMessageInfo message : change.messages) {
                if (TextUtils.isEmpty(message.tag)) {
                    count++;
                }
            }
        }
        return count;
    }

    // Counts the number of user accounts with a comment (excluding robot accounts
    // and voting only messages)
    private int countNumberOfNotRobotAccountsWithAComment(ChangeInfo change) {
        Set<Integer> accounts = new HashSet<>();
        if (change.messages != null) {
            for (ChangeMessageInfo message : change.messages) {
                if (TextUtils.isEmpty(message.tag) && message.message.indexOf("\n\n") > 0) {
                    accounts.add(message.author.accountId);
                }
            }
        }
        return accounts.size();
    }

    // Count the number of votes (activity registered by voting/remove voting) of user accounts
    // (excluding robot accounts)
    private int countNumberOfVotesOfNotRobotAccounts(ChangeInfo change) {
        int count = 0;
        if (change.labels != null) {
            for (ChangeMessageInfo message : change.messages) {
                if (TextUtils.isEmpty(message.tag)) {
                    String firstLine = message.message;
                    int pos = message.message.indexOf("\n");
                    if (pos > 0) {
                        firstLine = message.message.substring(0, pos);
                    }

                    if (VOTE_PATTERN.matcher(firstLine).find()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    // Count the number of reviewers added to this changes
    private int countNumberOfReviewers(ChangeInfo change) {
        if (change.reviewers == null) {
            return 0;
        }
        int count = 0;
        for (Entry<ReviewerStatus, AccountInfo[]> entry : change.reviewers.entrySet()) {
            if (entry.getKey().equals(ReviewerStatus.REMOVED)) {
                continue;
            }
            if (entry.getValue() != null) {
                count += entry.getValue().length;
            }
        }
        return count;
    }

    // Number of patchsets of present on this change
    private int countNumberOfPatchsets(ChangeInfo change) {
        if (change.revisions == null || change.currentRevision == null
                || !change.revisions.containsKey(change.currentRevision)) {
            return 0;
        }
        return change.revisions.get(change.currentRevision).number;
    }
}
