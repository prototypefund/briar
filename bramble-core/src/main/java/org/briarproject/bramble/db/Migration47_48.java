package org.briarproject.bramble.db;

import org.briarproject.bramble.api.db.DbException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

import static java.lang.System.arraycopy;
import static java.sql.Types.BINARY;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.sync.SyncConstants.MESSAGE_HEADER_LENGTH;
import static org.briarproject.bramble.db.JdbcUtils.tryToClose;

class Migration47_48 implements Migration<Connection> {

	private static final Logger LOG = getLogger(Migration47_48.class.getName());

	private final DatabaseTypes dbTypes;

	Migration47_48(DatabaseTypes dbTypes) {
		this.dbTypes = dbTypes;
	}

	@Override
	public int getStartVersion() {
		return 47;
	}

	@Override
	public int getEndVersion() {
		return 48;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		ResultSet rs = null;
		PreparedStatement ps = null;
		try {
			s = txn.createStatement();

			s.execute("ALTER TABLE messages"
					+ " ADD COLUMN deleted BOOLEAN DEFAULT FALSE NOT NULL");
			s.execute("UPDATE messages SET deleted = (raw IS NULL)");

			s.execute("ALTER TABLE messages"
					+ " ALTER COLUMN length RENAME TO dataLength");
			s.execute("UPDATE messages SET dataLength = dataLength - "
					+ MESSAGE_HEADER_LENGTH);

			s.execute("ALTER TABLE messages"
					+ " ADD COLUMN blockCount INT DEFAULT 1 NOT NULL");

			s.execute(dbTypes.replaceTypes("CREATE TABLE blocks"
					+ " (blockId _HASH NOT NULL,"
					+ " groupId _HASH NOT NULL,"
					+ " timestamp BIGINT NOT NULL,"
					+ " blockCount INT NOT NULL,"
					+ " blockNumber INT NOT NULL,"
					+ " messageId _HASH," // Null if not yet known
					+ " backHash _HASH," // Null for single block
					+ " prevBackHash _HASH," // Null for single or first block
					+ " nextBlockId _HASH," // Null for single or last block
					+ " dataLength INT NOT NULL," // Excluding header
					+ " data BLOB," // Null if message has been deleted
					+ " PRIMARY KEY (blockId),"
					+ " FOREIGN KEY (groupId)"
					+ " REFERENCES groups (groupId)"
					+ " ON DELETE CASCADE,"
					+ " FOREIGN KEY (messageId)"
					+ " REFERENCES messages (messageId)"
					+ " ON DELETE CASCADE)"));

			rs = s.executeQuery("SELECT messageId, groupId, timestamp,"
					+ " dataLength, raw"
					+ " FROM messages");
			ps = txn.prepareStatement("INSERT INTO blocks"
					+ " (blockId, groupId, timestamp, blockCount, blockNumber,"
					+ " messageId, dataLength, data)"
					+ " VALUES (?, ?, ?, 1, 0, ?, ?, ?)");

			int migrated = 0;
			while (rs.next()) {
				byte[] messageId = rs.getBytes(1);
				byte[] groupId = rs.getBytes(2);
				long timestamp = rs.getLong(3);
				int dataLength = rs.getInt(4);
				byte[] raw = rs.getBytes(5);
				// For existing messages, block ID equals message ID
				ps.setBytes(1, messageId);
				ps.setBytes(2, groupId);
				ps.setLong(3, timestamp);
				ps.setBytes(4, messageId);
				ps.setInt(5, dataLength);
				if (raw == null) {
					ps.setNull(6, BINARY);
				} else {
					byte[] data = new byte[raw.length - MESSAGE_HEADER_LENGTH];
					arraycopy(raw, MESSAGE_HEADER_LENGTH, data, 0, data.length);
					ps.setBytes(6, data);
				}
				if (ps.executeUpdate() != 1) throw new DbStateException();
				migrated++;
			}

			ps.close();
			rs.close();

			s.execute("ALTER TABLE messages DROP COLUMN raw");

			if (LOG.isLoggable(INFO))
				LOG.info("Migrated " + migrated + " messages");

			s.execute(dbTypes.replaceTypes("CREATE TABLE blockStatuses"
					+ " (blockId _HASH NOT NULL,"
					+ " contactId INT NOT NULL,"
					+ " groupId _HASH NOT NULL," // Denormalised
					+ " timestamp BIGINT NOT NULL," // Denormalised
					+ " blockCount INT NOT NULL," // Denormalised
					+ " blockNumber INT NOT NULL," // Denormalised
					+ " messageId _HASH," // Denormalised, null if not yet known
					+ " groupShared BOOLEAN NOT NULL," // Denormalised
					+ " messageShared BOOLEAN NOT NULL," // Denormalised
					+ " deleted BOOLEAN NOT NULL," // Denormalised
					+ " blocksToAck INT," // Non-null for first block in message
					+ " blocksSeen INT," // Non-null for first block in message
					+ " canSendOffer BOOLEAN NOT NULL,"
					+ " sendAck BOOLEAN NOT NULL,"
					+ " seen BOOLEAN NOT NULL,"
					+ " requested BOOLEAN NOT NULL,"
					+ " expiry BIGINT NOT NULL,"
					+ " txCount INT NOT NULL,"
					+ " eta BIGINT NOT NULL,"
					+ " PRIMARY KEY (blockId, contactId),"
					+ " FOREIGN KEY (blockId)"
					+ " REFERENCES blocks (blockId)"
					+ " ON DELETE CASCADE,"
					+ " FOREIGN KEY (contactId)"
					+ " REFERENCES contacts (contactId)"
					+ " ON DELETE CASCADE,"
					+ " FOREIGN KEY (groupId)"
					+ " REFERENCES groups (groupId)"
					+ " ON DELETE CASCADE,"
					+ " FOREIGN KEY (messageId)"
					+ " REFERENCES messages (messageId)"
					+ " ON DELETE CASCADE)"));

			rs = s.executeQuery("SELECT messageId, contactId, groupId,"
					+ " timestamp, groupShared, messageShared, deleted, ack,"
					+ " seen, requested, expiry, txCount, eta"
					+ " FROM statuses");
			ps = txn.prepareStatement("INSERT INTO blockStatuses"
					+ " (blockId, contactId, groupId, timestamp, blockCount,"
					+ " blockNumber, messageId, groupShared, messageShared,"
					+ " deleted, blocksToAck, blocksSeen, canSendOffer,"
					+ " sendAck, seen, requested, expiry, txCount, eta)"
					+ " VALUES (?, ?, ?, ?, 1, 0, ?, ?, ?, ?, 1, ?, TRUE, ?,"
					+ " ?, ?, ?, ?, ?)");

			migrated = 0;
			while (rs.next()) {
				byte[] messageId = rs.getBytes(1);
				int contactId = rs.getInt(2);
				byte[] groupId = rs.getBytes(3);
				long timestamp = rs.getLong(4);
				boolean groupShared = rs.getBoolean(5);
				boolean messageShared = rs.getBoolean(6);
				boolean deleted = rs.getBoolean(7);
				boolean ack = rs.getBoolean(8);
				boolean seen = rs.getBoolean(9);
				boolean requested = rs.getBoolean(10);
				long expiry = rs.getLong(11);
				int txCount = rs.getInt(12);
				long eta = rs.getLong(13);
				// For existing messages, block ID equals message ID
				ps.setBytes(1, messageId);
				ps.setInt(2, contactId);
				ps.setBytes(3, groupId);
				ps.setLong(4, timestamp);
				ps.setBytes(5, messageId);
				ps.setBoolean(6, groupShared);
				ps.setBoolean(7, messageShared);
				ps.setBoolean(8, deleted);
				ps.setInt(9, seen ? 1 : 0);
				ps.setBoolean(10, ack);
				ps.setBoolean(11, seen);
				ps.setBoolean(12, requested);
				ps.setLong(13, expiry);
				ps.setInt(14, txCount);
				ps.setLong(15, eta);
				if (ps.executeUpdate() != 1) throw new DbStateException();
				migrated++;
			}

			ps.close();
			rs.close();

			s.execute("CREATE INDEX IF NOT EXISTS"
					+ " blockStatusesByContactIdGroupId"
					+ " ON blockStatuses (contactId, groupId)");

			s.execute("CREATE INDEX IF NOT EXISTS"
					+ " blockStatusesByContactIdTimestamp"
					+ " ON blockStatuses (contactId, timestamp)");

			s.execute("CREATE INDEX IF NOT EXISTS"
					+ " blockStatusesByMessageIdContactId"
					+ " ON blockStatuses (messageId, contactId)");

			s.execute("DROP TABLE statuses");
			s.close();

			if (LOG.isLoggable(INFO))
				LOG.info("Migrated " + migrated + " statuses");
		} catch (SQLException e) {
			tryToClose(ps, LOG, WARNING);
			tryToClose(rs, LOG, WARNING);
			tryToClose(s, LOG, WARNING);
			throw new DbException(e);
		}
	}
}
