package org.briarproject.bramble.api.sync.event;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.event.ConsumableEvent;
import org.briarproject.bramble.api.sync.Request;

/**
 * An event that is broadcast when a request needs to be sent to a contact. The
 * consumer should send the request.
 */
public class RequestToSendEvent extends ConsumableEvent {

	private final ContactId contactId;
	private final Request request;

	public RequestToSendEvent(ContactId contactId, Request request) {
		this.contactId = contactId;
		this.request = request;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public Request getRequest() {
		return request;
	}
}
