package org.briarproject.briar.android.navdrawer;

import org.briarproject.bramble.api.plugin.TransportId;

import androidx.annotation.UiThread;

interface TransportStateListener {

	@UiThread
	void stateUpdate(TransportId id, boolean enabled);
}
