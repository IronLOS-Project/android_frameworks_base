/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.server.location;

import android.app.PendingIntent;
import android.content.Context;
import android.hardware.contexthub.V1_0.ContextHubMsg;
import android.hardware.contexthub.V1_0.IContexthub;
import android.hardware.contexthub.V1_0.Result;
import android.hardware.location.ContextHubInfo;
import android.hardware.location.ContextHubTransaction;
import android.hardware.location.IContextHubClient;
import android.hardware.location.IContextHubClientCallback;
import android.hardware.location.NanoAppMessage;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * A class that acts as a broker for the ContextHubClient, which handles messaging and life-cycle
 * notification callbacks. This class implements the IContextHubClient object, and the implemented
 * APIs must be thread-safe.
 *
 * @hide
 */
public class ContextHubClientBroker extends IContextHubClient.Stub
        implements IBinder.DeathRecipient {
    private static final String TAG = "ContextHubClientBroker";

    /*
     * The context of the service.
     */
    private final Context mContext;

    /*
     * The proxy to talk to the Context Hub HAL.
     */
    private final IContexthub mContextHubProxy;

    /*
     * The manager that registered this client.
     */
    private final ContextHubClientManager mClientManager;

    /*
     * The object describing the hub that this client is attached to.
     */
    private final ContextHubInfo mAttachedContextHubInfo;

    /*
     * The host end point ID of this client.
     */
    private final short mHostEndPointId;

    /*
     * The remote callback interface for this client. This will be set to null whenever the
     * client connection is closed (either explicitly or via binder death).
     */
    private IContextHubClientCallback mCallbackInterface = null;

    /*
     * True if the client is still registered with the Context Hub Service, false otherwise.
     */
    private boolean mRegistered = true;

    /*
     * Internal interface used to invoke client callbacks.
     */
    private interface CallbackConsumer {
        void accept(IContextHubClientCallback callback) throws RemoteException;
    }

    /* package */ ContextHubClientBroker(
            Context context, IContexthub contextHubProxy, ContextHubClientManager clientManager,
            ContextHubInfo contextHubInfo, short hostEndPointId,
            IContextHubClientCallback callback) {
        mContext = context;
        mContextHubProxy = contextHubProxy;
        mClientManager = clientManager;
        mAttachedContextHubInfo = contextHubInfo;
        mHostEndPointId = hostEndPointId;
        mCallbackInterface = callback;
    }

    /**
     * Attaches a death recipient for this client
     *
     * @throws RemoteException if the client has already died
     */
    /* package */ synchronized void attachDeathRecipient() throws RemoteException {
        if (mCallbackInterface != null) {
            mCallbackInterface.asBinder().linkToDeath(this, 0 /* flags */);
        }
    }

    /**
     * Sends from this client to a nanoapp.
     *
     * @param message the message to send
     * @return the error code of sending the message
     */
    @ContextHubTransaction.Result
    @Override
    public int sendMessageToNanoApp(NanoAppMessage message) {
        ContextHubServiceUtil.checkPermissions(mContext);

        int result;
        IContextHubClientCallback callback = null;
        synchronized (this) {
            callback = mCallbackInterface;
        }
        if (callback != null) {
            ContextHubMsg messageToNanoApp =
                    ContextHubServiceUtil.createHidlContextHubMessage(mHostEndPointId, message);

            int contextHubId = mAttachedContextHubInfo.getId();
            try {
                result = mContextHubProxy.sendMessageToHub(contextHubId, messageToNanoApp);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException in sendMessageToNanoApp (target hub ID = "
                        + contextHubId + ")", e);
                result = Result.UNKNOWN_FAILURE;
            }
        } else {
            Log.e(TAG, "Failed to send message to nanoapp: client connection is closed");
            result = Result.UNKNOWN_FAILURE;
        }

        return ContextHubServiceUtil.toTransactionResult(result);
    }

    /**
     * @param pendingIntent the intent to register
     * @param nanoAppId     the ID of the nanoapp to send events for
     * @return true on success, false otherwise
     */
    @Override
    public boolean registerIntent(PendingIntent pendingIntent, long nanoAppId) {
        // TODO: Implement this
        return false;
    }

    /**
     * @param pendingIntent the intent to unregister
     * @return true on success, false otherwise
     */
    @Override
    public boolean unregisterIntent(PendingIntent pendingIntent) {
        // TODO: Implement this
        return false;
    }

    /**
     * Closes the connection for this client with the service.
     */
    @Override
    public void close() {
        synchronized (this) {
            if (mCallbackInterface != null) {
                mCallbackInterface.asBinder().unlinkToDeath(this, 0 /* flags */);
                mCallbackInterface = null;
            }
            if (mRegistered) {
                mClientManager.unregisterClient(mHostEndPointId);
                mRegistered = false;
            }
        }
    }

    /**
     * Invoked when the underlying binder of this broker has died at the client process.
     */
    @Override
    public void binderDied() {
        close();
    }

    /**
     * @return the ID of the context hub this client is attached to
     */
    /* package */ int getAttachedContextHubId() {
        return mAttachedContextHubInfo.getId();
    }

    /**
     * @return the host endpoint ID of this client
     */
    /* package */ short getHostEndPointId() {
        return mHostEndPointId;
    }

    /**
     * Sends a message to the client associated with this object.
     *
     * @param message the message that came from a nanoapp
     */
    /* package */ void sendMessageToClient(NanoAppMessage message) {
        invokeCallback(callback -> callback.onMessageFromNanoApp(message));
    }

    /**
     * Notifies the client of a nanoapp load event if the connection is open.
     *
     * @param nanoAppId the ID of the nanoapp that was loaded.
     */
    /* package */ void onNanoAppLoaded(long nanoAppId) {
        invokeCallback(callback -> callback.onNanoAppLoaded(nanoAppId));
    }

    /**
     * Notifies the client of a nanoapp unload event if the connection is open.
     *
     * @param nanoAppId the ID of the nanoapp that was unloaded.
     */
    /* package */ void onNanoAppUnloaded(long nanoAppId) {
        invokeCallback(callback -> callback.onNanoAppUnloaded(nanoAppId));
    }

    /**
     * Notifies the client of a hub reset event if the connection is open.
     */
    /* package */ void onHubReset() {
        invokeCallback(callback -> callback.onHubReset());
    }

    /**
     * Notifies the client of a nanoapp abort event if the connection is open.
     *
     * @param nanoAppId the ID of the nanoapp that aborted
     * @param abortCode the nanoapp specific abort code
     */
    /* package */ void onNanoAppAborted(long nanoAppId, int abortCode) {
        invokeCallback(callback -> callback.onNanoAppAborted(nanoAppId, abortCode));
    }

    /**
     * Helper function to invoke a specified client callback, if the connection is open.
     *
     * @param consumer the consumer specifying the callback to invoke
     */
    private synchronized void invokeCallback(CallbackConsumer consumer) {
        if (mCallbackInterface != null) {
            try {
                consumer.accept(mCallbackInterface);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while invoking client callback (host endpoint ID = "
                        + mHostEndPointId + ")", e);
            }
        }
    }
}
