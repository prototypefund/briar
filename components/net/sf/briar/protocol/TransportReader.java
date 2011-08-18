package net.sf.briar.protocol;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.sf.briar.api.protocol.ProtocolConstants;
import net.sf.briar.api.protocol.Tags;
import net.sf.briar.api.protocol.TransportUpdate;
import net.sf.briar.api.serial.Consumer;
import net.sf.briar.api.serial.ObjectReader;
import net.sf.briar.api.serial.Reader;

class TransportReader implements ObjectReader<TransportUpdate> {

	private final TransportFactory transportFactory;
	private final ObjectReader<TransportProperties> propertiesReader;

	TransportReader(TransportFactory transportFactory) {
		this.transportFactory = transportFactory;
		propertiesReader = new TransportPropertiesReader();
	}

	public TransportUpdate readObject(Reader r) throws IOException {
		// Initialise the consumer
		Consumer counting =
			new CountingConsumer(ProtocolConstants.MAX_PACKET_LENGTH);
		// Read the data
		r.addConsumer(counting);
		r.readUserDefinedTag(Tags.TRANSPORT_UPDATE);
		r.addObjectReader(Tags.TRANSPORT_PROPERTIES, propertiesReader);
		r.setMaxStringLength(ProtocolConstants.MAX_PACKET_LENGTH);
		List<TransportProperties> l = r.readList(TransportProperties.class);
		r.resetMaxStringLength();
		r.removeObjectReader(Tags.TRANSPORT_PROPERTIES);
		Map<String, Map<String, String>> transports =
			new TreeMap<String, Map<String, String>>();
		for(TransportProperties t : l) transports.put(t.name, t.properties);
		long timestamp = r.readInt64();
		r.removeConsumer(counting);
		// Build and return the transport update
		return transportFactory.createTransports(transports, timestamp);
	}

	private static class TransportProperties {

		private final String name;
		private final Map<String, String> properties;

		TransportProperties(String name, Map<String, String> properties) {
			this.name = name;
			this.properties = properties;
		}
	}

	private static class TransportPropertiesReader
	implements ObjectReader<TransportProperties> {

		public TransportProperties readObject(Reader r) throws IOException {
			r.readUserDefinedTag(Tags.TRANSPORT_PROPERTIES);
			String name = r.readString();
			Map<String, String> properties =
				r.readMap(String.class, String.class);
			return new TransportProperties(name, properties);
		}
	}
}
