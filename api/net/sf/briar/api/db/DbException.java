package net.sf.briar.api.db;

public class DbException extends Exception {

	private static final long serialVersionUID = 3706581789209939441L;

	public DbException() {
		super();
	}

	public DbException(Throwable t) {
		super(t);
	}
}
