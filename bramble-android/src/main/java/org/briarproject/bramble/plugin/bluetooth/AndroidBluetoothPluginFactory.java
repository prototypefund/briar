package org.briarproject.bramble.plugin.bluetooth;

import android.content.Context;

import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.plugin.PluginCallback;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.duplex.DuplexPlugin;
import org.briarproject.bramble.api.plugin.duplex.DuplexPluginFactory;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.bramble.api.system.Clock;

import java.security.SecureRandom;
import java.util.concurrent.Executor;

import javax.annotation.concurrent.Immutable;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.briarproject.bramble.api.plugin.BluetoothConstants.ID;

@Immutable
@NotNullByDefault
public class AndroidBluetoothPluginFactory implements DuplexPluginFactory {

	private static final int MAX_LATENCY = (int) SECONDS.toMillis(30);
	private static final int POLLING_INTERVAL = (int) MINUTES.toMillis(2);

	private final Executor ioExecutor;
	private final AndroidExecutor androidExecutor;
	private final Context appContext;
	private final SecureRandom secureRandom;
	private final EventBus eventBus;
	private final Clock clock;

	public AndroidBluetoothPluginFactory(Executor ioExecutor,
			AndroidExecutor androidExecutor, Context appContext,
			SecureRandom secureRandom, EventBus eventBus, Clock clock) {
		this.ioExecutor = ioExecutor;
		this.androidExecutor = androidExecutor;
		this.appContext = appContext;
		this.secureRandom = secureRandom;
		this.eventBus = eventBus;
		this.clock = clock;
	}

	@Override
	public TransportId getId() {
		return ID;
	}

	@Override
	public int getMaxLatency() {
		return MAX_LATENCY;
	}

	@Override
	public DuplexPlugin createPlugin(PluginCallback callback) {
		BluetoothConnectionLimiter connectionLimiter =
				new BluetoothConnectionLimiterImpl();
		AndroidBluetoothPlugin plugin = new AndroidBluetoothPlugin(
				connectionLimiter, ioExecutor, androidExecutor, appContext,
				secureRandom, clock, callback, MAX_LATENCY, POLLING_INTERVAL);
		eventBus.addListener(plugin);
		return plugin;
	}
}
