/* 
 * Copyright 2014 OpenMarket Ltd
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.androidsdk.util;

import android.text.TextUtils;
import android.util.Log;

import org.matrix.androidsdk.MXDataHandler;
import org.matrix.androidsdk.listeners.IMXNetworkEventListener;
import org.matrix.androidsdk.network.NetworkConnectivityReceiver;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.callback.RestAdapterCallback;
import org.matrix.androidsdk.rest.model.MatrixError;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import retrofit.RetrofitError;

/**
 * unsent matrix events manager
 * This manager schedules the unsent events sending.
 * 1 - it keeps the unsent events order (i.e. wait that the first event is resent before sending the second one)
 * 2 - Apply the retry rules (event time life, 3 tries...)
 */
public class UnsentEventsManager {

    // 3 mins
    public static final int MAX_MESSAGE_LIFETIME_MS = 180000;
    private static final String LOG_TAG = "UnsentEventsManager";

    // Some matrix errors could have been triggered.
    // Assume that the server requires some delays because of internal issues
    static List<Integer> AUTO_RESENT_MS_DELAYS =  Arrays.asList(1 * 1000, 5 * 1000, 10 * 1000);

    // perform only MAX_RETRIES retries
    static int MAX_RETRIES = 3;

    private NetworkConnectivityReceiver mNetworkConnectivityReceiver;
    // faster way to check if the event is already sent
    private HashMap<Object, UnsentEventSnapshot> mUnsentEventsMap = new HashMap<Object, UnsentEventSnapshot>();
    // get the sending order
    private ArrayList<UnsentEventSnapshot> mUnsentEvents = new ArrayList<UnsentEventSnapshot>();
    // true of the device is connected to a data network
    private boolean mbIsConnected = false;

    // matrix error management
    private MXDataHandler mDataHandler;

    /**
     * storage class
     */
    protected class UnsentEventSnapshot {
        // first time the message has been sent
        protected long mAge;
        // the number of retries
        // it should be limited
        protected int mRetryCount;
        // callbacks
        protected ApiCallback mApiCallback;
        // failure reason
        protected RetrofitError mRetrofitError;
        // retry callback.
        protected RestAdapterCallback.RequestRetryCallBack mRequestRetryCallBack;
        // retry timer
        private Timer mAutoResendTimer = null;

        public Timer mLifeTimeTimer = null;
        // the retry is in progress
        public boolean mIsResending = false;
        // human description of the event
        // The snapshot creator can hide some fields
        public String mEventDescription = null;

        /**
         *
         */
        public boolean waitToBeResent() {
            return (null != mAutoResendTimer);
        }

        /**
         * Resend the event after
         * @param delayMs
         */
        public void resendEventAfter(int delayMs) {
            stopTimer();

            if (null != mEventDescription) {
                Log.d(LOG_TAG, "Resend after " + delayMs + " [" +  mEventDescription + "]");
            }

            mAutoResendTimer = new Timer();
            mAutoResendTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        UnsentEventSnapshot.this.mIsResending = true;

                        if (null != mEventDescription) {
                            Log.d(LOG_TAG, "Resend [" +  mEventDescription + "]");
                        }

                        mRequestRetryCallBack.onRetry();
                    } catch (Exception e) {
                    }
                }
            }, delayMs);
        }

        /**
         * Stop any pending resending timer.
         */
        public void stopTimer() {
            if (null != mAutoResendTimer) {
                mAutoResendTimer.cancel();
                mAutoResendTimer = null;
            }
        }

        /**
         * Stop timers.
         */
        public void stopTimers() {
            if (null != mAutoResendTimer) {
                mAutoResendTimer.cancel();
                mAutoResendTimer = null;
            }

            if (null != mLifeTimeTimer) {
                mLifeTimeTimer.cancel();
                mLifeTimeTimer = null;
            }
        }
    }

    /**
     * Constructor
     * @param networkConnectivityReceiver
     */
    public UnsentEventsManager(NetworkConnectivityReceiver networkConnectivityReceiver, MXDataHandler dataHandler) {
        mNetworkConnectivityReceiver = networkConnectivityReceiver;

        // add a default listener
        // to resend the unsent messages
        mNetworkConnectivityReceiver.addEventListener(new IMXNetworkEventListener() {
            @Override
            public void onNetworkConnectionUpdate(boolean isConnected) {
                mbIsConnected = isConnected;

                if (isConnected) {
                    resentUnsents();
                }
            }
        });

        mDataHandler = dataHandler;
    }

    /**
     * Warn that the apiCallback has been called
     * @param apiCallback the called apiCallback
     */
    public void onEventSent(ApiCallback apiCallback) {
        if (null != apiCallback) {
            UnsentEventSnapshot snapshot = null;

            synchronized (mUnsentEventsMap) {
                if (mUnsentEventsMap.containsKey(apiCallback)) {
                    snapshot = mUnsentEventsMap.get(apiCallback);
                }
            }

            if (null != snapshot) {
                if (null != snapshot.mEventDescription) {
                    Log.d(LOG_TAG, "Resend Succeeded [" +  snapshot.mEventDescription + "]");
                }

                snapshot.stopTimers();

                synchronized (mUnsentEventsMap) {
                    mUnsentEventsMap.remove(apiCallback);
                    mUnsentEvents.remove(snapshot);
                }

                resentUnsents();
            }
        }
    }

    /**
     * Clear the session data
     */
    public void clear() {
        synchronized (mUnsentEventsMap) {
            for(UnsentEventSnapshot snapshot : mUnsentEvents) {
                snapshot.stopTimers();
            }

            mUnsentEvents.clear();
            mUnsentEventsMap.clear();
        }
    }

    /**
     * The event failed to be sent and cannot be resent.
     * It triggers the error callbacks.
     * @param eventDescription the event description
     * @param error the retrofit error
     * @param callback the callback.
     */
    public static void triggerErrorCallback(MXDataHandler dataHandler, String eventDescription, RetrofitError error, ApiCallback callback) {
        if (null != error) {
            Log.e(LOG_TAG, error.getMessage() + " url=" + error.getUrl());
        }

        if (null == error) {
            try {
                if (null != eventDescription) {
                    Log.e(LOG_TAG, "Unexpected Error " + eventDescription);
                }
                if (null != callback) {
                    callback.onUnexpectedError(error);
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "Exception UnexpectedError " + e.getMessage() + " while managing " + error.getUrl());
            }
        }
        else if (error.isNetworkError()) {
            try {
                if (null != eventDescription) {
                    Log.e(LOG_TAG, "Network Error " + eventDescription);
                }
                if (null != callback) {
                    callback.onNetworkError(error);
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "Exception NetworkError " + e.getMessage() + " while managing " + error.getUrl());
            }
        }
        else {
            // Try to convert this into a Matrix error
            MatrixError mxError;
            try {
                mxError = (MatrixError) error.getBodyAs(MatrixError.class);
            }
            catch (Exception e) {
                mxError = null;
            }
            if (mxError != null) {
                try {
                    if (null != eventDescription) {
                        Log.e(LOG_TAG, "Matrix Error " + mxError + " " + eventDescription);
                    }

                    if (TextUtils.equals(MatrixError.UNKNOWN_TOKEN, mxError.errcode)) {
                        dataHandler.onInvalidToken();
                    } else if (null != callback) {
                        callback.onMatrixError(mxError);
                    }

                } catch (Exception e) {
                    Log.e(LOG_TAG, "Exception MatrixError " + e.getMessage() + " while managing " + error.getUrl());
                }
            }
            else {
                try {
                    if (null != eventDescription) {
                        Log.e(LOG_TAG, "Unexpected Error " + eventDescription);
                    }

                    if (null != callback) {
                        callback.onUnexpectedError(error);
                    }
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Exception UnexpectedError " + e.getMessage() + " while managing " + error.getUrl());
                }
            }
        }
    }

    /**
     * warns that an event failed to be sent.
     * @paral eventDescription the event description
     * @param retrofitError the retrofit error .
     * @param apiCallback the apiCallback.
     * @param requestRetryCallBack requestRetryCallBack.
     */
    public void onEventSendingFailed(final String eventDescription,  final RetrofitError retrofitError, final ApiCallback apiCallback, final RestAdapterCallback.RequestRetryCallBack requestRetryCallBack) {
        boolean isManaged = false;

        if (null != eventDescription) {
            Log.d(LOG_TAG, "Fail to send [" +  eventDescription + "]");
        }

        if ((null != requestRetryCallBack) && (null != apiCallback)) {
            synchronized (mUnsentEventsMap) {
                UnsentEventSnapshot snapshot;

                // Try to convert this into a Matrix error
                MatrixError mxError = null;

                if (null != retrofitError) {
                    try {
                        mxError = (MatrixError) retrofitError.getBodyAs(MatrixError.class);
                    } catch (Exception e) {
                        mxError = null;
                    }
                }

                // trace the matrix error.
                if ((null != eventDescription) && (null != mxError)) {
                    Log.d(LOG_TAG, "Matrix error " + mxError.errcode + " " + mxError.getLocalizedMessage() + " [" +  eventDescription + "]");
                }

                int matrixRetryTimeout = -1;

                if ((null != mxError) &&  MatrixError.LIMIT_EXCEEDED.equals(mxError.errcode) && (null != mxError.retry_after_ms)) {
                    matrixRetryTimeout = mxError.retry_after_ms + 200;
                }

                // some matrix errors are not trapped.
                if ((null == mxError) || !mxError.isSupportedErrorCode() || MatrixError.LIMIT_EXCEEDED.equals(mxError.errcode)) {
                    // is it the first time that the event has been sent ?
                    if (mUnsentEventsMap.containsKey(apiCallback)) {
                        snapshot = mUnsentEventsMap.get(apiCallback);

                        snapshot.mIsResending = false;
                        snapshot.stopTimer();

                        // assume that LIMIT_EXCEEDED error is not a default retry
                        if (matrixRetryTimeout < 0) {
                            snapshot.mRetryCount++;
                        }

                        // any event has a time life to avoid very old messages
                        if (((System.currentTimeMillis() - snapshot.mAge) > MAX_MESSAGE_LIFETIME_MS) || (snapshot.mRetryCount > MAX_RETRIES)) {
                            snapshot.stopTimers();
                            mUnsentEventsMap.remove(apiCallback);
                            mUnsentEvents.remove(snapshot);

                            if (null != eventDescription) {
                                Log.d(LOG_TAG, "Cancel [" +  eventDescription + "]");
                            }

                            isManaged = false;
                        } else {
                            isManaged = true;
                        }
                    } else {
                        snapshot = new UnsentEventSnapshot();

                        snapshot.mAge = System.currentTimeMillis();
                        snapshot.mApiCallback = apiCallback;
                        snapshot.mRetrofitError = retrofitError;
                        snapshot.mRequestRetryCallBack = requestRetryCallBack;
                        snapshot.mRetryCount = 1;
                        snapshot.mEventDescription = eventDescription;
                        mUnsentEventsMap.put(apiCallback, snapshot);
                        mUnsentEvents.add(snapshot);

                        // the event has a life time
                        final UnsentEventSnapshot fSnapshot = snapshot;
                        fSnapshot.mLifeTimeTimer = new Timer();
                        fSnapshot.mLifeTimeTimer.schedule(new TimerTask() {
                            @Override
                            public void run() {
                                try {

                                    if (null != eventDescription) {
                                        Log.d(LOG_TAG, "Cancel to send [" +  eventDescription + "]");
                                    }

                                    fSnapshot.stopTimers();
                                    synchronized (mUnsentEventsMap) {
                                        mUnsentEventsMap.remove(apiCallback);
                                        mUnsentEvents.remove(fSnapshot);
                                    }

                                    triggerErrorCallback(mDataHandler, eventDescription, retrofitError, apiCallback);
                                } catch (Exception e) {
                                }
                            }
                        }, MAX_MESSAGE_LIFETIME_MS);

                        isManaged = true;
                    }

                    // retry to send the message ?
                    if (isManaged) {
                        // resend the event only if there is an available network
                        // retrofitError.isNetworkError() does not provide a valid description of the failure
                        // 1- there is no available network / the connection is lost. (what we could expect)
                        // 2- the server did not response after 15s : the client would wrongly behave, it would wait until to switch to a valid network
                        //    It never happens, so the message is never resent.
                        //
                        if (mbIsConnected) {
                            snapshot.resendEventAfter((matrixRetryTimeout > 0) ? matrixRetryTimeout : AUTO_RESENT_MS_DELAYS.get(snapshot.mRetryCount - 1));
                        }
                    }
                }
            }
        }

        if (!isManaged) {
            Log.d(LOG_TAG, "Cannot resend it");
            triggerErrorCallback(mDataHandler, eventDescription, retrofitError, apiCallback);
        }
    }

    /**
     * check if some messages must be resent
     */
    private void resentUnsents() {
        Log.d(LOG_TAG, "resentUnsents");

        synchronized (mUnsentEventsMap) {
            if (mUnsentEvents.size() > 0) {
                try {
                    // retry the first
                    for(int index = 0; index < mUnsentEvents.size(); index++) {
                        UnsentEventSnapshot unsentEventSnapshot = mUnsentEvents.get(index);

                        // check if there is no required delay to resend the message
                        if (!unsentEventSnapshot.waitToBeResent()) {
                            // if the message is already resending,
                            if (unsentEventSnapshot.mIsResending) {
                                // do not resend any other one to try to keep the messages sending order.
                                return;
                            } else {
                                if (null != unsentEventSnapshot.mEventDescription) {
                                    Log.d(LOG_TAG, "Automatically resend " + unsentEventSnapshot.mEventDescription);
                                }

                                unsentEventSnapshot.mIsResending = true;
                                unsentEventSnapshot.mRequestRetryCallBack.onRetry();
                            }
                            break;
                        }
                    }
                } catch (Exception e) {

                }
            }
        }
    }
}
