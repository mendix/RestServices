package restservices.publish;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;

import org.eclipse.jetty.continuation.Continuation;
import org.json.JSONObject;

import restservices.RestServices;
import restservices.RestServices;

import com.mendix.core.Core;

class ChangeFeedSubscriber {
	
	static long nextId = 1L;
	private final String id = "FeedRequest#" + nextId++;  
	private volatile boolean inSync = false;
	
	final private LinkedBlockingQueue<JSONObject> pendingInstructions = new LinkedBlockingQueue<JSONObject>(RestServices.MAXPOLLQUEUE_LENGTH);
	
	final private AsyncContext continuation;
	
	final private Semaphore resumeMutex = new Semaphore(1);
	private final PublishedService service;
	
	public ChangeFeedSubscriber(AsyncContext asyncContext, PublishedService publishedService) {
		this.continuation = asyncContext;
		this.service = publishedService;
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

