package org.briarproject.bramble.api.db;

/**
 * Thrown when a large message is requested from the database using a method
 * that's only suitable for small messages.
 */
public class MessageTooLargeException extends DbException {
}
