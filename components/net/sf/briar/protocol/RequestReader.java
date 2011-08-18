package net.sf.briar.protocol;

import java.io.IOException;
import java.util.BitSet;

import net.sf.briar.api.protocol.OfferId;
import net.sf.briar.api.protocol.ProtocolConstants;
import net.sf.briar.api.protocol.Request;
import net.sf.briar.api.protocol.Tags;
import net.sf.briar.api.serial.Consumer;
import net.sf.briar.api.serial.ObjectReader;
import net.sf.briar.api.serial.Reader;

class RequestReader implements ObjectReader<Request> {

	private final ObjectReader<OfferId> offerIdReader;
	private final RequestFactory requestFactory;

	RequestReader(ObjectReader<OfferId> offerIdReader,
			RequestFactory requestFactory) {
		this.offerIdReader = offerIdReader;
		this.requestFactory = requestFactory;
	}

	public Request readObject(Reader r) throws IOException {
		// Initialise the consumer
		Consumer counting =
			new CountingConsumer(ProtocolConstants.MAX_PACKET_LENGTH);
		// Read the data
		r.addConsumer(counting);
		r.readUserDefinedTag(Tags.REQUEST);
		r.addObjectReader(Tags.OFFER_ID, offerIdReader);
		OfferId offerId = r.readUserDefined(Tags.OFFER_ID, OfferId.class);
		byte[] bitmap = r.readBytes(ProtocolConstants.MAX_PACKET_LENGTH);
		r.removeConsumer(counting);
		// Convert the bitmap into a BitSet
		BitSet b = new BitSet(bitmap.length * 8);
		for(int i = 0; i < bitmap.length; i++) {
			for(int j = 0; j < 8; j++) {
				byte bit = (byte) (128 >> j);
				if((bitmap[i] & bit) != 0) b.set(i * 8 + j);
			}
		}
		return requestFactory.createRequest(offerId, b);
	}
}
