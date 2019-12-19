package org.briarproject.bramble.api.sync.event;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.event.ConsumableEvent;
import org.briarproject.bramble.api.sync.Ack;

/**
 * An event that is broadcast when an ack needs to be sent to a contact. The
 * consumer should send the ack.
 */
public class AckToSendEvent extends ConsumableEvent {

	private final ContactId contactId;
	private final Ack ack;

	public AckToSendEvent(ContactId contactId, Ack ack) {
		this.contactId = contactId;
		this.ack = ack;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public Ack getAck() {
		return ack;
	}
}
