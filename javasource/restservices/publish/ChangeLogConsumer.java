package restservices.publish;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;

import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;

import org.json.JSONObject;

import restservices.RestServices;
import restservices.util.RestServiceRuntimeException;

class ChangeLogConsumer {
	
	static long nextId = 1L;
	private final String id = "FeedRequest#" + nextId++;  
	
	final private LinkedBlockingQueue<JSONObject> pendingInstructions = new LinkedBlockingQueue<JSONObject>(RestServices.MAXPOLLQUEUE_LENGTH);
	
	final private AsyncContext continuation;
	private boolean completeAfterFirst;
	private ChangeLogManager	changeLogManager;
	
	public ChangeLogConsumer(AsyncContext asyncContext, boolean completeAfterFirst, ChangeLogManager changeLogManager) {
		this.continuation = asyncContext;
		this.completeAfterFirst = completeAfterFirst;
		this.changeLogManager = changeLogManager;
	}

	public void addInstruction(JSONObject json) 
	{
		if (RestServices.LOGPUBLISH.isDebugEnabled())
			RestServices.LOGPUBLISH.debug(this.id + " received instruction " + json.toString());
		
		if (!pendingInstructions.offer(json))
			throw new RestServiceRuntimeException(this.id + " dropped message; maximum queue size exceeded");
			
		writePendingChanges();
	}

	private void writePendingChanges() {
		//MWE: hmm... printwriter doesn't do the job!
		//PrintWriter writer = new PrintWriter(continuation.getServletResponse().getOutputStream());
		JSONObject instr = null;
		
		try {
			
			while(null != (instr = pendingInstructions.poll())) { 
				RestServices.LOGPUBLISH.debug("Publishing " + instr);
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

	void complete() {
		try {
			this.continuation.complete(); 
		}
		catch (Throwable e) {
			RestServices.LOGPUBLISH.warn("Failed to complete " + id + ": " + e.getMessage(), e);
		}
		changeLogManager.unregisterConsumer(this);
	}
}

