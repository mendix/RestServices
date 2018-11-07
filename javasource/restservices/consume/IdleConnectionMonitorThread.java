package restservices.consume;

import org.apache.commons.httpclient.HttpConnectionManager;

public class IdleConnectionMonitorThread extends Thread {

	private final HttpConnectionManager connMgr;
	private long intervalMillis;
	private long maxIdleTimeMillis;
	private volatile boolean shutdown;

	public IdleConnectionMonitorThread(HttpConnectionManager connMgr, long intervalMillis, long maxIdleTimeMillis) {
		super();
		this.connMgr = connMgr;
		this.intervalMillis = intervalMillis;
		this.maxIdleTimeMillis = maxIdleTimeMillis;
	}

	@Override
	public void run() {
		try {
			while (!shutdown) {
				synchronized (this) {
					wait(intervalMillis);
					connMgr.closeIdleConnections(maxIdleTimeMillis);
				}
			}
		} catch (InterruptedException ex) {
			// terminate
		}
	}

	public void shutdown() {
		shutdown = true;
		synchronized (this) {
			notifyAll();
		}
	}
}