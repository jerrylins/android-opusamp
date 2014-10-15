/*
 * CallStateBroadcastReceiver.java
 *
 * Copyright (c) 2012, Philippe Chepy
 * All rights reserved.
 *
 * This software is the confidential and proprietary information
 * of Philippe Chepy.
 * You shall not disclose such Confidential Information.
 *
 * http://www.chepy.eu
 */
package net.opusapp.player.core.broadcastreceiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import net.opusapp.player.R;
import net.opusapp.player.ui.utils.MusicConnector;
import net.opusapp.player.ui.utils.PlayerApplication;

public class CallStateBroadcastReceiver extends BroadcastReceiver {

	public static final String TAG = "CallStateBroadcastReceiver";

	@Override
	public void onReceive(Context context, Intent intent) {
		PhoneStateListener phoneListener = new PhoneStateListener() {
			
			public void onCallStateChanged(int state, String incomingNumber) {
				switch (state) {
				case TelephonyManager.CALL_STATE_IDLE:
					MusicConnector.doCallManageIdle();
					break;
				case TelephonyManager.CALL_STATE_OFFHOOK:
					MusicConnector.doCallManageOffHook();
					break;
				case TelephonyManager.CALL_STATE_RINGING:
					MusicConnector.doCallManageRinging();
					break;
				}
			}
		};

        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (sharedPreferences.getBoolean(PlayerApplication.context.getString(R.string.preference_key_pause_call), true)) {
            TelephonyManager telephony = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            telephony.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE);
        }
	}

}