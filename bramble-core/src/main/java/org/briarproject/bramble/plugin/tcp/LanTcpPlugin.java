package org.briarproject.bramble.plugin.tcp;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.keyagreement.KeyAgreementConnection;
import org.briarproject.bramble.api.keyagreement.KeyAgreementListener;
import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.plugin.Backoff;
import org.briarproject.bramble.api.plugin.PluginCallback;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.settings.Settings;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import static java.lang.Integer.parseInt;
import static java.util.Collections.addAll;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.sort;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.keyagreement.KeyAgreementConstants.TRANSPORT_ID_LAN;
import static org.briarproject.bramble.api.plugin.LanTcpConstants.ID;
import static org.briarproject.bramble.api.plugin.LanTcpConstants.PREF_LAN_IP_PORTS;
import static org.briarproject.bramble.api.plugin.LanTcpConstants.PROP_IP_PORTS;
import static org.briarproject.bramble.api.plugin.LanTcpConstants.PROP_PORT;
import static org.briarproject.bramble.api.plugin.LanTcpConstants.PROP_SLAAC;
import static org.briarproject.bramble.util.ByteUtils.MAX_16_BIT_UNSIGNED;
import static org.briarproject.bramble.util.IoUtils.tryToClose;
import static org.briarproject.bramble.util.PrivacyUtils.scrubSocketAddress;
import static org.briarproject.bramble.util.StringUtils.fromHexString;
import static org.briarproject.bramble.util.StringUtils.isNullOrEmpty;
import static org.briarproject.bramble.util.StringUtils.join;
import static org.briarproject.bramble.util.StringUtils.toHexString;

@NotNullByDefault
class LanTcpPlugin extends TcpPlugin {

	private static final Logger LOG = getLogger(LanTcpPlugin.class.getName());

	private static final int MAX_ADDRESSES = 4;
	private static final String SEPARATOR = ",";

	/**
	 * The network prefix of a SLAAC IPv6 address. See
	 * https://tools.ietf.org/html/rfc4862#section-5.3
	 */
	private static final byte[] SLAAC_PREFIX =
			new byte[] {(byte) 0xFE, (byte) 0x80, 0, 0, 0, 0, 0, 0};

	/**
	 * The IP address of an Android device providing a wifi access point.
	 * <p>
	 * Most devices use this address, but at least one device (Honor 8A) may
	 * use other addresses in the range 192.168.43.0/24.
	 */
	private static final InetAddress WIFI_AP_ADDRESS;

	/**
	 * The IP address of an Android device providing a wifi direct
	 * legacy mode access point.
	 */
	private static final InetAddress WIFI_DIRECT_AP_ADDRESS;

	static {
		try {
			WIFI_AP_ADDRESS = InetAddress.getByAddress(
					new byte[] {(byte) 192, (byte) 168, 43, 1});
			WIFI_DIRECT_AP_ADDRESS = InetAddress.getByAddress(
					new byte[] {(byte) 192, (byte) 168, 49, 1});
		} catch (UnknownHostException e) {
			// Should only be thrown if the address has an illegal length
			throw new AssertionError(e);
		}
	}

	LanTcpPlugin(Executor ioExecutor, Backoff backoff, PluginCallback callback,
			int maxLatency, int maxIdleTime, int connectionTimeout) {
		super(ioExecutor, backoff, callback, maxLatency, maxIdleTime,
				connectionTimeout);
	}

	@Override
	public TransportId getId() {
		return ID;
	}

	@Override
	public void start() {
		if (used.getAndSet(true)) throw new IllegalStateException();
		initialisePortProperty();
		Settings settings = callback.getSettings();
		state.setStarted(settings.getBoolean(PREF_PLUGIN_ENABLE, false));
		bind();
	}

	protected void initialisePortProperty() {
		TransportProperties p = callback.getLocalProperties();
		if (isNullOrEmpty(p.get(PROP_PORT))) {
			int port = chooseEphemeralPort();
			p.put(PROP_PORT, String.valueOf(port));
			callback.mergeLocalProperties(p);
		}
	}

	@Override
	protected List<InetSocketAddress> getLocalSocketAddresses(boolean ipv4) {
		TransportProperties p = callback.getLocalProperties();
		int preferredPort = parsePortProperty(p.get(PROP_PORT));
		String oldIpPorts = p.get(PROP_IP_PORTS);
		List<InetSocketAddress> olds = parseIpv4SocketAddresses(oldIpPorts);

		List<InetSocketAddress> locals = new ArrayList<>();
		List<InetSocketAddress> fallbacks = new ArrayList<>();
		for (InetAddress local : getUsableLocalInetAddresses(ipv4)) {
			// If we've used this address before, try to use the same port
			int port = preferredPort;
			for (InetSocketAddress old : olds) {
				if (old.getAddress().equals(local)) {
					port = old.getPort();
					break;
				}
			}
			locals.add(new InetSocketAddress(local, port));
			// Fall back to any available port
			fallbacks.add(new InetSocketAddress(local, 0));
		}
		locals.addAll(fallbacks);
		return locals;
	}

	private int parsePortProperty(@Nullable String portProperty) {
		if (isNullOrEmpty(portProperty)) return 0;
		try {
			return parseInt(portProperty);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private List<InetSocketAddress> parseIpv4SocketAddresses(String ipPorts) {
		List<InetSocketAddress> addresses = new ArrayList<>();
		if (isNullOrEmpty(ipPorts)) return addresses;
		for (String ipPort : ipPorts.split(SEPARATOR)) {
			InetSocketAddress a = parseIpv4SocketAddress(ipPort);
			if (a != null) addresses.add(a);
		}
		return addresses;
	}

	protected List<InetAddress> getUsableLocalInetAddresses(boolean ipv4) {
		List<InterfaceAddress> ifAddrs =
				new ArrayList<>(getLocalInterfaceAddresses());
		// Prefer longer network prefixes
		sort(ifAddrs, (a, b) ->
				b.getNetworkPrefixLength() - a.getNetworkPrefixLength());
		List<InetAddress> addrs = new ArrayList<>();
		for (InterfaceAddress ifAddr : ifAddrs) {
			InetAddress addr = ifAddr.getAddress();
			if (isAcceptableAddress(addr, ipv4)) addrs.add(addr);
		}
		return addrs;
	}

	@Override
	protected void setLocalSocketAddress(InetSocketAddress a, boolean ipv4) {
		if (ipv4) setLocalIpv4SocketAddress(a);
		else setLocalIpv6SocketAddress(a);
	}

	private void setLocalIpv4SocketAddress(InetSocketAddress a) {
		String ipPort = getIpPortString(a);
		// Get the list of recently used addresses
		String setting = callback.getSettings().get(PREF_LAN_IP_PORTS);
		List<String> recent = new ArrayList<>();
		if (!isNullOrEmpty(setting))
			addAll(recent, setting.split(SEPARATOR));
		// Is the address already in the list?
		if (recent.remove(ipPort)) {
			// Move the address to the start of the list
			recent.add(0, ipPort);
			setting = join(recent, SEPARATOR);
		} else {
			// Add the address to the start of the list
			recent.add(0, ipPort);
			// Drop the least recently used address if the list is full
			if (recent.size() > MAX_ADDRESSES)
				recent = recent.subList(0, MAX_ADDRESSES);
			setting = join(recent, SEPARATOR);
			// Update the list of addresses shared with contacts
			List<String> shared = new ArrayList<>(recent);
			sort(shared);
			String property = join(shared, SEPARATOR);
			TransportProperties properties = new TransportProperties();
			properties.put(PROP_IP_PORTS, property);
			callback.mergeLocalProperties(properties);
		}
		// Save the setting
		Settings settings = new Settings();
		settings.put(PREF_LAN_IP_PORTS, setting);
		callback.mergeSettings(settings);
	}

	private void setLocalIpv6SocketAddress(InetSocketAddress a) {
		if (isSlaacAddress(a.getAddress())) {
			String property = toHexString(a.getAddress().getAddress());
			TransportProperties properties = new TransportProperties();
			properties.put(PROP_SLAAC, property);
			callback.mergeLocalProperties(properties);
		}
	}

	// See https://tools.ietf.org/html/rfc4862#section-5.3
	protected boolean isSlaacAddress(InetAddress a) {
		if (!(a instanceof Inet6Address)) return false;
		byte[] ip = a.getAddress();
		for (int i = 0; i < 8; i++) {
			if (ip[i] != SLAAC_PREFIX[i]) return false;
		}
		return (ip[8] & 0x02) == 0x02
				&& ip[11] == (byte) 0xFF
				&& ip[12] == (byte) 0xFE;
	}

	@Override
	protected List<InetSocketAddress> getRemoteSocketAddresses(
			TransportProperties p, boolean ipv4) {
		if (ipv4) return getRemoteIpv4SocketAddresses(p);
		else return getRemoteIpv6SocketAddresses(p);
	}

	private List<InetSocketAddress> getRemoteIpv4SocketAddresses(
			TransportProperties p) {
		String ipPorts = p.get(PROP_IP_PORTS);
		List<InetSocketAddress> remotes = parseIpv4SocketAddresses(ipPorts);
		int port = parsePortProperty(p.get(PROP_PORT));
		// If the contact has a preferred port, we can guess their IP:port when
		// they're providing a wifi access point
		if (port != 0) {
			InetSocketAddress wifiAp =
					new InetSocketAddress(WIFI_AP_ADDRESS, port);
			if (!remotes.contains(wifiAp)) remotes.add(wifiAp);
			InetSocketAddress wifiDirectAp =
					new InetSocketAddress(WIFI_DIRECT_AP_ADDRESS, port);
			if (!remotes.contains(wifiDirectAp)) remotes.add(wifiDirectAp);
		}
		return remotes;
	}

	private List<InetSocketAddress> getRemoteIpv6SocketAddresses(
			TransportProperties p) {
		InetAddress slaac = parseSlaacProperty(p.get(PROP_SLAAC));
		int port = parsePortProperty(p.get(PROP_PORT));
		if (slaac == null || port == 0) return emptyList();
		return singletonList(new InetSocketAddress(slaac, port));
	}

	@Nullable
	private InetAddress parseSlaacProperty(String slaacProperty) {
		if (isNullOrEmpty(slaacProperty)) return null;
		try {
			byte[] ip = fromHexString(slaacProperty);
			if (ip.length != 16) return null;
			InetAddress a = InetAddress.getByAddress(ip);
			return isSlaacAddress(a) ? a : null;
		} catch (IllegalArgumentException | UnknownHostException e) {
			return null;
		}
	}

	private boolean isAcceptableAddress(InetAddress a, boolean ipv4) {
		if (ipv4) {
			// Accept link-local and site-local IPv4 addresses
			boolean isIpv4 = a instanceof Inet4Address;
			boolean loop = a.isLoopbackAddress();
			boolean link = a.isLinkLocalAddress();
			boolean site = a.isSiteLocalAddress();
			return isIpv4 && !loop && (link || site);
		} else {
			// Accept IPv6 SLAAC addresses
			return isSlaacAddress(a);
		}
	}

	@Override
	protected boolean isConnectable(InterfaceAddress local,
			InetSocketAddress remote) {
		if (remote.getPort() == 0) return false;
		InetAddress remoteAddress = remote.getAddress();
		boolean ipv4 = local.getAddress() instanceof Inet4Address;
		if (!isAcceptableAddress(remoteAddress, ipv4)) return false;
		// Try to determine whether the address is on the same LAN as us
		byte[] localIp = local.getAddress().getAddress();
		byte[] remoteIp = remote.getAddress().getAddress();
		int prefixLength = local.getNetworkPrefixLength();
		return areAddressesInSameNetwork(localIp, remoteIp, prefixLength);
	}

	// Package access for testing
	static boolean areAddressesInSameNetwork(byte[] localIp, byte[] remoteIp,
			int prefixLength) {
		if (localIp.length != remoteIp.length) return false;
		// Compare the first prefixLength bits of the addresses
		for (int i = 0; i < prefixLength; i++) {
			int byteIndex = i >> 3;
			int bitIndex = i & 7; // 0 to 7
			int mask = 128 >> bitIndex; // Select the bit at bitIndex
			if ((localIp[byteIndex] & mask) != (remoteIp[byteIndex] & mask)) {
				return false; // Addresses differ at bit i
			}
		}
		return true;
	}

	@Override
	public boolean supportsKeyAgreement() {
		return true;
	}

	@Override
	public KeyAgreementListener createKeyAgreementListener(byte[] commitment) {
		ServerSocket ss = null;
		for (InetSocketAddress addr : getLocalSocketAddresses(true)) {
			// Don't try to reuse the same port we use for contact connections
			addr = new InetSocketAddress(addr.getAddress(), 0);
			try {
				ss = new ServerSocket();
				ss.bind(addr);
				break;
			} catch (IOException e) {
				if (LOG.isLoggable(INFO))
					LOG.info("Failed to bind " + scrubSocketAddress(addr));
				tryToClose(ss, LOG, WARNING);
			}
		}
		if (ss == null) {
			LOG.info("Could not bind server socket for key agreement");
			return null;
		}
		BdfList descriptor = new BdfList();
		descriptor.add(TRANSPORT_ID_LAN);
		InetSocketAddress local =
				(InetSocketAddress) ss.getLocalSocketAddress();
		descriptor.add(local.getAddress().getAddress());
		descriptor.add(local.getPort());
		return new LanKeyAgreementListener(descriptor, ss);
	}

	@Override
	public DuplexTransportConnection createKeyAgreementConnection(
			byte[] commitment, BdfList descriptor) {
		ServerSocket ss = state.getServerSocket(true);
		if (ss == null) return null;
		InterfaceAddress local = getLocalInterfaceAddress(ss.getInetAddress());
		if (local == null) {
			LOG.warning("No interface for key agreement server socket");
			return null;
		}
		InetSocketAddress remote;
		try {
			remote = parseSocketAddress(descriptor);
		} catch (FormatException e) {
			LOG.info("Invalid IP/port in key agreement descriptor");
			return null;
		}
		if (!isConnectable(local, remote)) {
			if (LOG.isLoggable(INFO)) {
				LOG.info(scrubSocketAddress(remote) +
						" is not connectable from " +
						scrubSocketAddress(ss.getLocalSocketAddress()));
			}
			return null;
		}
		try {
			if (LOG.isLoggable(INFO))
				LOG.info("Connecting to " + scrubSocketAddress(remote));
			Socket s = createSocket();
			s.bind(new InetSocketAddress(ss.getInetAddress(), 0));
			s.connect(remote, connectionTimeout);
			s.setSoTimeout(socketTimeout);
			if (LOG.isLoggable(INFO))
				LOG.info("Connected to " + scrubSocketAddress(remote));
			return new TcpTransportConnection(this, s);
		} catch (IOException e) {
			if (LOG.isLoggable(INFO))
				LOG.info("Could not connect to " + scrubSocketAddress(remote));
			return null;
		}
	}

	private InetSocketAddress parseSocketAddress(BdfList descriptor)
			throws FormatException {
		byte[] address = descriptor.getRaw(1);
		int port = descriptor.getLong(2).intValue();
		if (port < 1 || port > MAX_16_BIT_UNSIGNED) throw new FormatException();
		try {
			InetAddress addr = InetAddress.getByAddress(address);
			return new InetSocketAddress(addr, port);
		} catch (UnknownHostException e) {
			// Invalid address length
			throw new FormatException();
		}
	}

	private class LanKeyAgreementListener extends KeyAgreementListener {

		private final ServerSocket ss;

		private LanKeyAgreementListener(BdfList descriptor,
				ServerSocket ss) {
			super(descriptor);
			this.ss = ss;
		}

		@Override
		public KeyAgreementConnection accept() throws IOException {
			Socket s = ss.accept();
			if (LOG.isLoggable(INFO)) LOG.info(ID + ": Incoming connection");
			return new KeyAgreementConnection(new TcpTransportConnection(
					LanTcpPlugin.this, s), ID);
		}

		@Override
		public void close() {
			tryToClose(ss, LOG, WARNING);
		}
	}
}
