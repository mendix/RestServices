package restservices.publish;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;

import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;

import org.json.JSONObject;

import restservices.RestServices;
import restservices.util.RestServiceRuntimeException;

class ChangeFeedSubscriber {
	
	static long nextId = 1L;
	private final String id = "FeedRequest#" + nextId++;  
	private volatile boolean inSync = false;
	
	final private LinkedBlockingQueue<JSONObject> pendingInstructions = new LinkedBlockingQueue<JSONObject>(RestServices.MAXPOLLQUEUE_LENGTH);
	
	final private AsyncContext continuation;
	private boolean completeAfterFirst;
	private ChangeManager	changeManager;
	
	public ChangeFeedSubscriber(AsyncContext asyncContext, boolean completeAfterFirst, ChangeManager changeManager) {
		this.continuation = asyncContext;
		this.completeAfterFirst = completeAfterFirst;
		this.changeManager = changeManager;
	}

	public void addInstruction(JSONObject json) 
	{
		if (RestServices.LOG.isDebugEnabled())
			RestServices.LOG.debug(this.id + " received instruction " + json.toString());
		
		if (!pendingInstructions.offer(json))
			throw new RestServiceRuntimeException(this.id + " dropped message; maximum queue size exceeded");
			
		//schedule continuations async so we might serve multiple instructions at the same time
		if (inSync)
			writePendingChanges();
	}

	private boolean isEmpty()
	{
		return pendingInstructions.isEmpty();
	}

	private void writePendingChanges() {
		//MWE: hmm... printwriter doesn't do the job!
		//PrintWriter writer = new PrintWriter(continuation.getServletResponse().getOutputStream());
		JSONObject instr = null;
		
		try {
			
			while(null != (instr = pendingInstructions.poll())) { 
				RestServices.LOG.debug("Publishing " + instr);
				ServletOutputStream out = continuation.getResponse().getOutputStream();
				out.write("\r\n".getBytes(RestServices.UTF8));
				out.write(instr.toString().getBytes(RestServices.UTF8));
			}
			continuation.getResponse().flushBuffer();
			
			if (completeAfterFirst) //return ASAP
				this.complete();
		} catch (IOException e) {
			throw new RestServiceRuntimeException("Failed to write changes to" + id, e);
		}
	}


	void markInSync() {
		this.inSync  = true;
		
		if (!this.isEmpty())
			this.writePendingChanges();
	}

	void complete() {
		try {
			this.continuation.complete(); 
		}
		catch (Throwable e) {
			RestServices.LOG.warn("Failed to complete " + id + ": " + e.getMessage(), e);
		}
		changeManager.unregisterListener(this);
	}
}

