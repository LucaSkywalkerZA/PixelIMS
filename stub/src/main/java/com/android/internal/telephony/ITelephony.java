package com.android.internal.telephony;

import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;

public interface ITelephony extends android.os.IInterface {

    int setImsProvisioningInt(int subId, int key, int value) throws RemoteException;

    int getImsProvisioningInt(int subId, int key) throws RemoteException;

    abstract class Stub extends Binder implements ITelephony {
        public static ITelephony asInterface(IBinder binder) {
            throw new UnsupportedOperationException();
        }
    }
}
