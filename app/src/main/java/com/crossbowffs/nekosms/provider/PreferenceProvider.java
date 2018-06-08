package com.crossbowffs.nekosms.provider;

import com.crossbowffs.nekosms.consts.PreferenceConsts;
import com.crossbowffs.remotepreferences.RemotePreferenceProvider;

public class PreferenceProvider extends RemotePreferenceProvider {
    public PreferenceProvider() {
        super(PreferenceConsts.REMOTE_PREFS_AUTHORITY, new String[] {PreferenceConsts.FILE_MAIN});
    }

    @Override
    protected boolean checkAccess(String prefName, String prefKey, boolean write) {
        // Only allow read access
        if (write) {
            return false;
        }

        // Only allow access from telephony process
        return "com.android.phone".equals(getCallingPackage());
    }
}
