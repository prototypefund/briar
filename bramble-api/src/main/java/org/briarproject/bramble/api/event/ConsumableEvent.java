package org.briarproject.bramble.api.event;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An event that can be consumed by a listener. All listeners receive the event
 * but only one can consume it.
 */
public class ConsumableEvent extends Event {

	private final AtomicBoolean consumed = new AtomicBoolean(false);

	/**
	 * Tries to consume the event. Returns true if the caller successfully
	 * consumed the event or false if another caller had already consumed it.
	 */
	public boolean consume() {
		return consumed.getAndSet(true);
	}
}
