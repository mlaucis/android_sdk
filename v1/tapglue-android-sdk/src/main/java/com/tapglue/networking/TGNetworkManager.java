/*
 * Copyright (c) 2015-2016 Tapglue (https://www.tapglue.com/). All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.tapglue.networking;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Base64;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.squareup.okhttp.Headers;
import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.logging.HttpLoggingInterceptor;
import com.tapglue.Tapglue;
import com.tapglue.model.TGBaseObject;
import com.tapglue.model.TGComment;
import com.tapglue.model.TGCommentsList;
import com.tapglue.model.TGConnection;
import com.tapglue.model.TGErrorList;
import com.tapglue.model.TGEvent;
import com.tapglue.model.TGEventsList;
import com.tapglue.model.TGFeed;
import com.tapglue.model.TGFeedCount;
import com.tapglue.model.TGLike;
import com.tapglue.model.TGLikesList;
import com.tapglue.model.TGLoginUser;
import com.tapglue.model.TGPendingConnections;
import com.tapglue.model.TGPost;
import com.tapglue.model.TGPostsList;
import com.tapglue.model.TGRecommendedUsers;
import com.tapglue.model.TGSearchCriteria;
import com.tapglue.model.TGSocialConnections;
import com.tapglue.model.TGUser;
import com.tapglue.model.TGUsersList;
import com.tapglue.model.queries.TGQuery;
import com.tapglue.networking.requests.TGRequestCallback;
import com.tapglue.networking.requests.TGRequestErrorType;
import com.tapglue.networking.requests.TGRequestType;
import com.tapglue.utils.TGLog;

import java.io.IOException;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;

import retrofit.Call;
import retrofit.Callback;
import retrofit.GsonConverterFactory;
import retrofit.Response;
import retrofit.Retrofit;

import static com.tapglue.utils.TGErrorUtil.sendErrorToCallbacks;

public class TGNetworkManager {

    private static final String KEY_AMOUNT = "CACHE_AMOUNT";

    private static final String KEY_QUEUE = "CACHE_QUEUE";

    /**
     * Current library version - string used in requests
     */
    @NonNull
    private static final String currentLibraryVersion = "1.1.6";

    /**
     * Custom headers required for analytics purposes
     */
    @Nullable
    private static Headers analyticsHeaders = null;

    private static String userAgent;

    /**
     * Api object
     */
    private final TGApi api;

    /**
     * Configuration object
     */
    @NonNull
    private final Tapglue.TGConfiguration configuration;

    /**
     * Request requests
     */
    @NonNull
    private final TGRequestsImpl requests;

    /**
     * Logging tool
     */
    @NonNull
    private final Tapglue tapglue;

    /**
     * Was analytics request already sent?
     */
    private boolean analyticsSent = false;

    /**
     * Flush timer
     */
    @Nullable
    private Timer flushTimer;

    private static void buildAnalyticsHeaders(@NonNull Context context, @NonNull String appName, @NonNull String appVersion) {
        TelephonyManager manager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        String carrierName = manager.getNetworkOperatorName();
        Calendar cal = Calendar.getInstance();
        TimeZone tz = cal.getTimeZone();

        Headers.Builder builder = new Headers.Builder();
        builder.add("X-Tapglue-App", appName)
               .add("X-Tapglue-Appversion", appVersion)
               .add("X-Tapglue-Carrier", carrierName != null ? carrierName : "Unknown_carrier")
               .add("X-Tapglue-Manufacturer", Build.MANUFACTURER != null ? Build.MANUFACTURER : "Unknown_manufacturer")
               .add("X-Tapglue-Model", Build.MODEL != null ? Build.MODEL : "Unknown_model")
               .add("X-Tapglue-AndroidID", Build.SERIAL != null ? Build.SERIAL : "Unknown_serial")
               .add("X-Tapglue-OS", "Android")
               .add("X-Tapglue-OSVersion", Build.VERSION.RELEASE)
               .add("X-Tapglue-AndroidPlatformSDK", String.valueOf(Build.VERSION.SDK_INT))
               .add("X-Tapglue-SDKVersion", currentLibraryVersion)
               .add("X-Tapglue-Timezone", tz.getID());

        analyticsHeaders = builder.build();
    }

    /**
     * Get current application version
     *
     * @param context Context used to read this information
     *
     * @return Application version
     */
    private static String getAppVersion(@NonNull Context context) {
        String appVersion = "unknown-version";

        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageInfo(
                context.getPackageName(), 0);
            appVersion = info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return appVersion;
    }

    /**
     * Check if at least one callback is not outdated
     *
     * @param callback list of callbacks
     *
     * @return true if at least one callback is outdated
     */
    static private <OUTOBJECT extends TGBaseObject> boolean hasOutdatedCallback(@Nullable List<TGRequestCallback<OUTOBJECT>> callback) {
        if (callback == null || callback.size() == 0) return false;

        for (int i = 0; i < callback.size(); i++) {
            if (callback.get(i) != null && callback.get(i).callbackIsEnabled()) {
                return true;
            }
        }
        return false;
    }

    public TGNetworkManager(@NonNull Tapglue.TGConfiguration configuration, @NonNull final Tapglue tapglue) {
        this.configuration = configuration;
        this.tapglue = tapglue;
        requests = new TGRequestsImpl(this);
        Context context = tapglue.getContext();
        String appVersion = getAppVersion(context);
        String appName = context.getString(context.getApplicationInfo().labelRes);

        userAgent = appName + "/" + context.getApplicationInfo().packageName + "; " +
            appVersion +
            " (" + (Build.MODEL != null ? Build.MODEL : "Unknown_model") +
            "; Android " + Build.VERSION.RELEASE + ") Tapglue-SDK/" + currentLibraryVersion;

        OkHttpClient client = new OkHttpClient();
        client.setProtocols(new ArrayList<>(Util.immutableList(Protocol.HTTP_1_1)));
        final HttpLoggingInterceptor interceptor = new HttpLoggingInterceptor();
        interceptor.setLevel(HttpLoggingInterceptor.Level.BODY);

        // should we enable debugging to console?
        if (this.configuration.isDebugMode()) { client.interceptors().add(interceptor); }

        if (analyticsHeaders == null) {
            buildAnalyticsHeaders(context, appName, appVersion);
        }

        // we add custom headers to each request
        client.interceptors().add(new Interceptor() {
            @Override
            public com.squareup.okhttp.Response intercept(@NonNull Chain chain) throws IOException {
                Request currentRequest = chain.request();
                boolean analyticsRequest = currentRequest.httpUrl().toString().contains("analytics");
                Request.Builder builder = currentRequest.newBuilder();

                if (analyticsRequest) {
                    builder.headers(analyticsHeaders);
                }

                builder.addHeader("Authorization", "Basic " + createAuthorizationString())
                       .addHeader("User-Agent", userAgent)
                       .addHeader("Content-Type", "application/json");

                Request req = builder.build();
                return chain.proceed(req);
            }
        });

        Retrofit mRetrofit = new Retrofit.Builder()
            .addConverterFactory(GsonConverterFactory.create(new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()))
            .baseUrl(configuration.getApiUrl())
            .client(client)
            .build();
        api = mRetrofit.create(TGApi.class);
        createPendingFlush();
    }

    /**
     * Add request to cache
     *
     * @param request Request to be added in cache
     */
    synchronized private void addToCache(@NonNull TGRequest request) {
        getLogger().log("Adding request to cache " + request.toString());
        // first check if object exists in cache - if yes, then if new version is different from cached one, overwrite old one
        SharedPreferences cacheFile = tapglue.getContext().getSharedPreferences(TGNetworkManager.class.toString(), Context.MODE_PRIVATE);

        Integer amount = cacheFile.getInt(KEY_AMOUNT, 0);
        if (amount == 0) {
            String requestString = TGCustomCacheObject.serialize(request);
            cacheFile.edit().putString(KEY_QUEUE + "_0", requestString).putInt(KEY_AMOUNT, 1).apply();
        }
        else {
            String requestString = TGCustomCacheObject.serialize(request);
            cacheFile.edit().putString(KEY_QUEUE + "_" + amount, requestString).putInt(KEY_AMOUNT, amount + 1).apply();
        }

        createPendingFlush();
    }

    /**
     * Create authorization string
     *
     * @return Authorization string
     */
    private String createAuthorizationString() {
        try {
            String auth = String.format("%s:%s",
                configuration.getToken(),
                tapglue.getUserManager().getCurrentUser() != null ? tapglue.getUserManager().getCurrentUser().getSessionToken() : "");
            byte[] data = auth.getBytes("UTF-8");
            return Base64.encodeToString(data, Base64.NO_WRAP);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Create flush request
     */
    synchronized private void createPendingFlush() {
        // check if timer is already working
        if (flushTimer != null) { return; }
        flushTimer = new Timer();
        flushTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                flushCache();
                flushTimer = null;
            }
        }, tapglue.getConfiguration().getFlushInterval());
    }

    /**
     * Create network request
     *
     * @return Network request creation interface
     */
    @NonNull
    public TGRequests createRequest() {
        return requests;
    }

    /**
     * Flush all pending requests from cache
     */
    synchronized private void flushCache() {
        if (!isCacheEnabled() || !isNetworkAvailable()) return;

        getLogger().log("Trying to flush cache");
        SharedPreferences cacheFile = tapglue.getContext().getSharedPreferences(TGNetworkManager.class.toString(), Context.MODE_PRIVATE);

        Integer amount = cacheFile.getInt(KEY_AMOUNT, 0);
        if (amount <= 0) {
            if (flushTimer != null) {
                flushTimer.cancel();
            }
            flushTimer = null;
            getLogger().log("Nothing to flush");
            return;
        }

        TGRequest cachedRequest = TGCustomCacheObject.deserialize(cacheFile.getString(KEY_QUEUE + "_0", null));

        // move cache by 1 backward
        getLogger().log("Flushing cache");
        for (int i = 1; i < amount; i++) {
            cacheFile.edit().putString(KEY_QUEUE + "_" + (i - 1), cacheFile.getString(KEY_QUEUE + "_" + (i), null)).apply();
        }

        cacheFile.edit().remove(KEY_QUEUE + "_" + (amount - 1)).putInt(KEY_AMOUNT, amount - 1).apply();
        if (amount - 1 <= 0) {
            if (flushTimer != null) {
                flushTimer.cancel();
            }
            flushTimer = null;
        }

        // TODO should we do something better here? What?
        if (cachedRequest == null) {
            return;
        }

        performRequest(cachedRequest, false);
        if (amount - 1 > 0) {
            flushCache();
        }
    }

    /**
     * Get logging tool
     *
     * @return
     */
    private TGLog getLogger() {
        return tapglue.getLogger();
    }

    /**
     * Internal info if library should cache requests
     *
     * @return is caching enabled or not
     */
    private boolean isCacheEnabled() {
        return configuration.isCacheEnabled();
    }

    /**
     * Is internet accessible at this moment?
     *
     * @return is the network available
     */
    private boolean isNetworkAvailable() {
        ConnectivityManager cm =
            (ConnectivityManager) tapglue.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        return netInfo != null && netInfo.isConnected();

    }

    /**
     * Perform request and flush data if possible
     *
     * @param request Request to be performed
     */
    void performRequest(@NonNull final TGRequest request) {
        performRequest(request, true);
    }

    /**
     * Actually perform request
     *
     * FIXME God method is bad for soul. Possible ways to break this down would be to split by
     * request type
     *
     * @param request         Request to be performed
     * @param flushIfPossible Flush all pending requests from cache if there are any available
     */
    private void performRequest(@NonNull final TGRequest request, boolean flushIfPossible) {
        tryToSendAnalytics();

        TGBaseObject reqObject = request.getObject();
        List reqCallbacks = request.getCallbacks();

        if (reqObject == null &&
            request.getRequestType() != TGRequestType.LOGOUT) {
            sendErrorToCallbacks(reqCallbacks, TGRequestErrorType.ErrorType.NULL_INPUT);
            return;
        }

        // check if we got valid user or request is not a login/creation one
        if (tapglue.getUserManager().getCurrentUser() == null &&
            !(request.getRequestType() == TGRequestType.LOGIN ||
                (request.getRequestType() == TGRequestType.CREATE && reqObject instanceof TGUser)
            )) {
            sendErrorToCallbacks(reqCallbacks, TGRequestErrorType.ErrorType.USER_NOT_LOGGED_IN);
            return;
        }

        // check if request is not outdated
        if (!hasOutdatedCallback(reqCallbacks)) return;

        if (!isNetworkAvailable()) {
            // check if request required to be done only when internet is accessible
            if (request.needToBeDoneLive()) {
                sendErrorToCallbacks(reqCallbacks, TGRequestErrorType.ErrorType.NO_NETWORK);
                return;
            }

            if (!isCacheEnabled()) {
                // cache and network are not available
                sendErrorToCallbacks(reqCallbacks, TGRequestErrorType.ErrorType.NO_NETWORK);
                return;
            }

            addToCache(request);
            for (int i = 0; i < reqCallbacks.size(); i++) {
                ((TGRequestCallback<?>) reqCallbacks.get(i)).onRequestFinished(null, false);
            }
            return;
        }
        else if (flushIfPossible) {
            flushCache();
        }

        TGNetworkRequestWithErrorHandling networkRequest = new TGNetworkRequestWithErrorHandling<>(this, request);

        // different actions based on request parameters
        switch (request.getRequestType()) {
            case SEARCH:
                if (reqObject == null || (!(reqObject instanceof TGSearchCriteria))) {
                    sendErrorToCallbacks(reqCallbacks, TGRequestErrorType.ErrorType.UNSUPPORTED_INPUT);
                    return;
                }

                String criteria = ((TGSearchCriteria) reqObject).getSearchCriteria();
                if (criteria != null) {
                    if (TextUtils.isEmpty(criteria)) {
                        sendErrorToCallbacks(reqCallbacks, TGRequestErrorType.ErrorType.UNSUPPORTED_INPUT);
                        return;
                    }
                    Call<TGUsersList> searchRequest = api.search(criteria);
                    searchRequest.enqueue(networkRequest);
                    return;
                }

                List<String> criteriaEmail = ((TGSearchCriteria) reqObject).getEmailsSearchCriteria();
                if (criteriaEmail != null) {
                    if (criteriaEmail.size() == 0) {
                        sendErrorToCallbacks(reqCallbacks, TGRequestErrorType.ErrorType.UNSUPPORTED_INPUT);
                        return;
                    }
                    Call<TGUsersList> searchRequest = api.searchWithEmails(criteriaEmail);
                    searchRequest.enqueue(networkRequest);
                    return;
                }

                List<String> criteriaSocial = ((TGSearchCriteria) reqObject).getSocialSearchCriteriaIds();
                String criteriaSocialPlatform = ((TGSearchCriteria) reqObject).getSocialSearchCriteriaPlatform();
                if (criteriaSocial != null) {
                    if (criteriaSocial.size() == 0 || TextUtils.isEmpty(criteriaSocialPlatform)) {
                        sendErrorToCallbacks(reqCallbacks, TGRequestErrorType.ErrorType.UNSUPPORTED_INPUT);
                        return;
                    }
                    Call<TGUsersList> searchRequest = api.searchWithSocialIds(criteriaSocial, criteriaSocialPlatform);
                    searchRequest.enqueue(networkRequest);
                }
                break;
            case LOGOUT:
                Call<Object> userRequest = api.logout();
                userRequest.enqueue(networkRequest);
                break;
            case LOGIN:
                if (reqObject instanceof TGLoginUser) {
                    Call<TGUser> userRequestLogin = api.login((TGLoginUser) reqObject);
                    userRequestLogin.enqueue(networkRequest);
                    return;
                }

                sendErrorToCallbacks(reqCallbacks, TGRequestErrorType.ErrorType.UNSUPPORTED_INPUT);
                break;
            case CREATE:
                if (reqObject instanceof TGUser) {
                    Call<TGUser> userRequestCreateUser = api.createUser((TGUser) reqObject);
                    userRequestCreateUser.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGConnection) {
                    TGConnection connectionCreateObject = (TGConnection) reqObject;
                    if (connectionCreateObject.getCacheObjectType() == null) {
                        sendErrorToCallbacks(reqCallbacks, TGRequestErrorType.ErrorType.UNSUPPORTED_INPUT);
                        return;
                    }

                    TGConnection connection = new TGConnection()
                        .setUserToId(connectionCreateObject.getUserToId())
                        .setUserFromId(connectionCreateObject.getUserFromId())
                        .setType(connectionCreateObject.getType())
                        .setState(connectionCreateObject.getState());
                    Call<TGConnection> createConnectionRequest = api.createConnection(connection);
                    createConnectionRequest.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGEvent) {
                    Call<TGEvent> createEventRequest = api.createEvent((TGEvent) reqObject);
                    createEventRequest.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGPost) {
                    Call<TGPost> createRequest = api.createPost((TGPost) reqObject);
                    createRequest.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGLike) {
                    Call<TGLike> createRequest = api.likePost(((TGLike) reqObject).getPostId());
                    createRequest.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGComment) {
                    Call<TGComment> createRequest = api.createComment(((TGComment) reqObject).getPostId(), (TGComment) reqObject);
                    createRequest.enqueue(networkRequest);
                    return;
                }

                sendErrorToCallbacks(reqCallbacks, TGRequestErrorType.ErrorType.UNSUPPORTED_INPUT);
                break;
            case READ:
                if (reqObject instanceof TGUser) {
                    // user request
                    Call<TGUser> userRequestReadUser = api.getUser(reqObject.getRequestObjectId());
                    userRequestReadUser.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGConnection) {
                    // connection request
                    TGConnection readConnectionObject = (TGConnection) reqObject;
                    if (readConnectionObject.getUserFromId() == null) {
                        // read connections from current user
                        if (readConnectionObject.getType() == null) {
                            if (readConnectionObject.getState() == TGConnection.TGConnectionState.CONFIRMED) {
                                // read confirmed
                                Call<TGPendingConnections> getFollowedForCurrentUserRequest = api.getConfirmedConnections();
                                getFollowedForCurrentUserRequest.enqueue(networkRequest);
                                return;
                            }

                            if (readConnectionObject.getState() == TGConnection.TGConnectionState.REJECTED) {
                                // read rejected
                                Call<TGPendingConnections> getFollowedForCurrentUserRequest = api.getRejectedConnections();
                                getFollowedForCurrentUserRequest.enqueue(networkRequest);
                                return;
                            }

                            // read followers
                            Call<TGUsersList> getFollowedForCurrentUserRequest = api.getFollowed();
                            getFollowedForCurrentUserRequest.enqueue(networkRequest);
                            return;
                        }

                        if (readConnectionObject.getType() == TGConnection.TGConnectionType.FOLLOW) {
                            // get followed
                            Call<TGUsersList> getFollowsForCurrentUserRequest = api.getFollows();
                            getFollowsForCurrentUserRequest.enqueue(networkRequest);
                            return;
                        }

                        if (readConnectionObject.getType() == TGConnection.TGConnectionType.FRIEND) {
                            // get friends
                            Call<TGUsersList> getFriendsForCurrentUserRequest = api.getFriends();
                            getFriendsForCurrentUserRequest.enqueue(networkRequest);
                            return;
                        }

                        // option possible only if library would be extended without checking this
                        sendErrorToCallbacks(reqCallbacks, TGRequestErrorType.ErrorType.UNSUPPORTED_INPUT);
                        return;
                    }

                    // create connection for other user
                    if (readConnectionObject.getType() == null) {
                        // read followers
                        Call<TGUsersList> getFollowedForUserRequest = api.getFollowedForUser(readConnectionObject.getUserFromId());
                        getFollowedForUserRequest.enqueue(networkRequest);
                        return;
                    }

                    if (readConnectionObject.getType() == TGConnection.TGConnectionType.FOLLOW) {
                        // get followed
                        Call<TGUsersList> getFollowsForUserRequest = api.getFollowsForUser(readConnectionObject.getUserFromId());
                        getFollowsForUserRequest.enqueue(networkRequest);
                        return;
                    }

                    if (readConnectionObject.getType() == TGConnection.TGConnectionType.FRIEND) {
                        // get friends
                        Call<TGUsersList> getFriendsForUserRequest = api.getFriendsForUser(readConnectionObject.getUserFromId());
                        getFriendsForUserRequest.enqueue(networkRequest);
                        return;
                    }

                    // option possible only if library would be extended without checking this
                    sendErrorToCallbacks(reqCallbacks, TGRequestErrorType.ErrorType.UNSUPPORTED_INPUT);
                    return;
                }

                if (reqObject instanceof TGEvent) {
                    // event request
                    Call<TGEvent> readEventRequest;
                    if (reqObject.getReadRequestUserId() == null) {
                        readEventRequest = api.getEvent(reqObject.getRequestObjectId());
                    }
                    else {
                        // read event for selected user
                        readEventRequest = api.getEvent(reqObject.getReadRequestUserId(), reqObject.getRequestObjectId());
                    }
                    readEventRequest.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGFeed) {
                    // for feed
                    if ((((TGFeed) reqObject).getUnreadCount() == null) || (((TGFeed) reqObject).getUnreadCount() != 1)) {
                        // get feed
                        Call<TGFeed> feedRequest;
                        if (((TGFeed) reqObject).getSearchQuery() == null) {
                            feedRequest = api.getFeed();
                        }
                        else {
                            feedRequest = api.getFeed(serializeSearchQuery(((TGFeed) reqObject).getSearchQuery()));
                        }
                        feedRequest.enqueue(networkRequest);
                        return;
                    }

                    // get unread feed
                    Call<TGEventsList> unreadFeedRequest;
                    if (((TGFeed) reqObject).getSearchQuery() == null) {
                        unreadFeedRequest = api.getUnreadFeed();
                    }
                    else {
                        unreadFeedRequest = api.getUnreadFeed(serializeSearchQuery(((TGFeed) reqObject).getSearchQuery()));
                    }
                    unreadFeedRequest.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGEventsList) {
                    // for events list
                    if (reqObject.getReadRequestUserId() == null) {
                        Call<TGEventsList> readEventsRequest;
                        if (((TGEventsList) reqObject).getSearchQuery() == null) {
                            readEventsRequest = api.getEvents();
                        }
                        else {
                            readEventsRequest = api.getEvents(serializeSearchQuery(((TGEventsList) reqObject).getSearchQuery()));
                        }
                        readEventsRequest.enqueue(networkRequest);
                        return;
                    }

                    // read events from selected user
                    Call<TGEventsList> readEventsRequest;
                    if (((TGEventsList) reqObject).getSearchQuery() == null) {
                        readEventsRequest = api.getEvents(reqObject.getReadRequestUserId());
                    }
                    else {
                        readEventsRequest = api.getEvents(reqObject.getReadRequestUserId(), serializeSearchQuery(((TGEventsList) reqObject).getSearchQuery()));
                    }
                    readEventsRequest.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGFeedCount) {
                    // feed count request
                    Call<TGFeedCount> countRequest = api.getUnreadFeedCount();
                    countRequest.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGPendingConnections) {
                    Call<TGPendingConnections> connectionsRequest = api.getPendingConnections();
                    connectionsRequest.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGPost) {
                    if (reqObject.getReadRequestUserId() == null) {
                        Call<TGPost> req = api.getPost(reqObject.getRequestObjectIdString());
                        req.enqueue(networkRequest);
                        return;
                    }

                    if (reqObject.getReadRequestUserId().longValue() == TGRequestsImpl.POST_READ_ID_GET_ALL.longValue()) {
                        Call<TGPostsList> req = api.getPosts();
                        req.enqueue(networkRequest);
                        return;
                    }

                    if (reqObject.getReadRequestUserId().longValue() == TGRequestsImpl.POST_READ_ID_GET_FEED.longValue()) {
                        Call<TGPostsList> req = api.getFeedPosts();
                        req.enqueue(networkRequest);
                        return;
                    }

                    if (reqObject.getReadRequestUserId().longValue() == TGRequestsImpl.POST_READ_ID_GET_MY.longValue()) {
                        Call<TGPostsList> req = api.getMyPosts();
                        req.enqueue(networkRequest);
                        return;
                    }

                    if (reqObject.getReadRequestUserId().longValue() == TGRequestsImpl.POST_READ_ID_USER.longValue()) {
                        Call<TGPostsList> req = api.getUserPosts(reqObject.getRequestObjectId());
                        req.enqueue(networkRequest);
                        return;
                    }

                    sendErrorToCallbacks(reqCallbacks, TGRequestErrorType.ErrorType.UNSUPPORTED_INPUT);
                    return;
                }

                if (reqObject instanceof TGLikesList) {
                    Call<TGLikesList> req = api.getPostLikes(reqObject.getRequestObjectIdString());
                    req.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGCommentsList) {
                    Call<TGCommentsList> req = api.getCommentsForPost(reqObject.getRequestObjectIdString());
                    req.enqueue(networkRequest);
                    return;
                }

                if (request.getObject() instanceof TGRecommendedUsers) {
                    Call<TGUsersList> req = api.getRecommendedUsers(((TGRecommendedUsers) reqObject).getType().toString(), ((TGRecommendedUsers) reqObject).getPeriod().toString());
                    req.enqueue(networkRequest);
                    return;
                }

                sendErrorToCallbacks(reqCallbacks, TGRequestErrorType.ErrorType.UNSUPPORTED_INPUT);
                break;
            case UPDATE:
                if (reqObject instanceof TGSocialConnections) {
                    Call<TGUsersList> socialRequest = api.socialConnections((TGSocialConnections) reqObject);
                    socialRequest.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGUser) {
                    // user request
                    Call<TGUser> userRequestUpdate = api.updateUser((TGUser) reqObject);
                    userRequestUpdate.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGEvent) {
                    // event request
                    Call<TGEvent> eventUpdateRequest = api.updateEvent(((TGEvent) reqObject).getID(), (TGEvent) reqObject);
                    eventUpdateRequest.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGConnection) {
                    // connection request
                    sendErrorToCallbacks(reqCallbacks, TGRequestErrorType.ErrorType.UNSUPPORTED_INPUT);
                    return;
                }

                if (reqObject instanceof TGPost) {
                    Call<TGPost> updateRequest = api.updatePost(reqObject.getRequestObjectIdString(), (TGPost) reqObject);
                    updateRequest.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGComment) {
                    Call<TGComment> updateReq = api.updatePostComment(((TGComment) reqObject).getPostId(),
                        ((TGComment) reqObject).getID(), (TGComment) reqObject);
                    updateReq.enqueue(networkRequest);
                    return;
                }

                sendErrorToCallbacks(reqCallbacks, TGRequestErrorType.ErrorType.UNSUPPORTED_INPUT);
                return;
            case DELETE:
                if (reqObject instanceof TGUser) {
                    // user request
                    Call<Object> userRequestDelete = api.deleteUser();
                    userRequestDelete.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGConnection) {
                    // connection request
                    TGConnection connectionCreateObject = (TGConnection) reqObject;
                    if (connectionCreateObject.getType() == null) {
                        sendErrorToCallbacks(reqCallbacks, TGRequestErrorType.ErrorType.UNSUPPORTED_INPUT);
                        return;
                    }

                    Call<Object> removeConnectionRequest = api.removeConnection(connectionCreateObject.getUserToId(), connectionCreateObject.getType().toString());
                    removeConnectionRequest.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGEvent) {
                    // event request
                    Call<Object> removeEventRequest = api.removeEvent(reqObject.getRequestObjectId());
                    removeEventRequest.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGPost) {
                    Call<Object> removePostRequest = api.removePost(reqObject.getRequestObjectIdString());
                    removePostRequest.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGLike) {
                    Call<Object> removePostRequest = api.unlikePost(((TGLike) reqObject).getPostId());
                    removePostRequest.enqueue(networkRequest);
                    return;
                }

                if (reqObject instanceof TGComment) {
                    Call<Object> removePostRequest = api.removePostComment(((TGComment) reqObject).getPostId(), reqObject.getRequestObjectId());
                    removePostRequest.enqueue(networkRequest);
                    return;
                }

                sendErrorToCallbacks(reqCallbacks, TGRequestErrorType.ErrorType.UNSUPPORTED_INPUT);
                break;
        }
    }

    /**
     * Serialize search query
     *
     * @param searchQuery
     *
     * @return
     */
    private String serializeSearchQuery(TGQuery searchQuery) {
        return new Gson().toJson(searchQuery, new TypeToken<TGQuery>() {}.getType());
    }

    /**
     * Try to send analytics request - if not possible or already done, sleep until next
     * possibility
     */
    private void tryToSendAnalytics() {
        if (analyticsSent || !configuration.isAnalyticsEnabled()) return;
        if (!isNetworkAvailable()) return;

        getLogger().log("Trying to send analytics");
        analyticsSent = true;
        api.sendAnalytics().enqueue(new Callback<Object>() {
            @Override
            public void onFailure(Throwable t) {
                analyticsSent = false;
                getLogger().log("Analytics sending fail, will retry in future");
            }

            @Override
            public void onResponse(Response<Object> response, Retrofit retrofit) {
                // do nothing
                getLogger().log("Analytics sent");
            }
        });
    }

    /**
     * Extended network request handling - only for use with TGNetworkManager
     *
     * @param <OBJECT>
     */
    private static class TGNetworkRequestWithErrorHandling<OBJECT extends TGBaseObject, OUTOBJECT extends TGBaseObject> implements Callback<OUTOBJECT> {

        @NonNull
        private final WeakReference<TGNetworkManager> netManager;

        private final TGRequest<OBJECT, OUTOBJECT> request;

        public TGNetworkRequestWithErrorHandling(TGNetworkManager netManager, TGRequest<OBJECT, OUTOBJECT> request) {
            this.request = request;
            this.netManager = new WeakReference<>(netManager);
        }

        @Override
        public void onFailure(@NonNull Throwable t) {
            // check if request is not outdated
            if (!hasOutdatedCallback(request.getCallbacks())) return;
            netManager.get().getLogger().logE(t);
            if (request.needToBeDoneLive() || !netManager.get().isCacheEnabled()) {
                sendErrorToCallbacks(request.getCallbacks(), TGRequestErrorType.ErrorType.SERVER_ERROR);
                return;
            }

            netManager.get().addToCache(request);
            netManager.get().getLogger().log("Request added to cache");
        }

        @Override
        public void onResponse(@NonNull Response<OUTOBJECT> response, Retrofit retrofit) {
            // check if request is not outdated
            if (!hasOutdatedCallback(request.getCallbacks())) { return; }

            // interpret error code
            if (response.errorBody() == null) {
                for (int i = 0; i < request.getCallbacks().size(); i++) {
                    if (request.getCallbacks().get(i) != null) {
                        request.getCallbacks().get(i).onRequestFinished(response.body(), true);
                    }
                }
                return;
            }

            int intCh;
            try {
                Reader stream = response.errorBody().charStream();
                StringBuilder builder = new StringBuilder();
                while ((intCh = stream.read()) != -1) {
                    builder.append((char) intCh);
                }

                String stringResponse = builder.toString();
                TGErrorList error = new Gson().fromJson(stringResponse, new TypeToken<TGErrorList>() {}.getType());

                for (int i = 0; i < error.getErrors().size(); i++) {
                    sendErrorToCallbacks(request.getCallbacks(), error.getErrors().get(i).getErrorCode(), error.getErrors().get(i).getMessage());
                }
            } catch (IOException e) {
                TGRequestErrorType.ErrorType errorType = TGRequestErrorType.ErrorType.get(response.code());
                if (errorType == null) {
                    errorType = TGRequestErrorType.ErrorType.UNKNOWN_ERROR;
                }
                sendErrorToCallbacks(request.getCallbacks(), errorType);
            }
        }
    }
}
