package restservices.publish;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;

import javax.servlet.AsyncContext;

import org.json.JSONObject;

import restservices.RestServices;

class ChangeFeedSubscriber {
	
	static long nextId = 1L;
	private final String id = "FeedRequest#" + nextId++;  
	private volatile boolean inSync = false;
	
	final private LinkedBlockingQueue<JSONObject> pendingInstructions = new LinkedBlockingQueue<JSONObject>(RestServices.MAXPOLLQUEUE_LENGTH);
	
	final private AsyncContext continuation;
	
	public ChangeFeedSubscriber(AsyncContext asyncContext) {
		this.continuation = asyncContext;
	}


	public void addInstruction(JSONObject json) throws IOException 
	{
		if (RestServices.LOG.isDebugEnabled())
			RestServices.LOG.debug(this.id + " received instruction " + json.toString());
		
		if (!pendingInstructions.offer(json))
			throw new IllegalStateException(this.id + " dropped message; maximum queue size exceeded");
			
		//schedule continuations async so we might serve multiple instructions at the same time
		if (inSync)
			writePendingChanges();
	}

	public boolean isEmpty()
	{
		return pendingInstructions.isEmpty();
	}

	public void writePendingChanges() throws IOException {
		//MWE: hmm... printwriter doesn't do the job!
		//PrintWriter writer = new PrintWriter(continuation.getServletResponse().getOutputStream());
		JSONObject instr = null;

		while(null != (instr = pendingInstructions.poll())) 
			continuation.getResponse().getOutputStream().write(instr.toString().getBytes(RestServices.UTF8));
		continuation.getResponse().flushBuffer();
	}


	public void markInSync() throws IOException {
		this.inSync  = true;
		
		if (!this.isEmpty())
			this.writePendingChanges();
	}


	public void complete() {
		this.continuation.complete(); //TODO: or, endless loop?		
	}
}

