package restservices.publish;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.AsyncContext;

import org.json.JSONObject;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;

import communitycommons.XPath;
import communitycommons.XPath.IBatchProcessor;

import restservices.RestServices;
import restservices.proxies.ObjectState;
import restservices.proxies.ServiceState;
import restservices.publish.RestServiceRequest.ContentType;
import restservices.util.JsonSerializer;
import restservices.util.Utils;

public class ChangeManager {
	
	private PublishedService service;
	final private Set<ChangeFeedSubscriber> longPollSessions = Collections.newSetFromMap(new ConcurrentHashMap<ChangeFeedSubscriber, Boolean>());
	private IMendixObject serviceState;

	public ChangeManager(PublishedService service) {
		this.service = service;
	}

	//TODO: move to other class
	JSONObject writeObjectStateToJson(ObjectState state){
		JSONObject res = new JSONObject();
		res
			.put("key", state.getkey()) //TODO: use constants
			.put("url", service.getServiceUrl() + state.getkey())
			.put("rev", state.getrevision())
			.put("etag", state.getetag())
			.put("deleted", state.getdeleted())
			.put("data", new JSONObject(state.getjson()));
		return res;
	}

	public void writeChanges(final RestServiceRequest rsr, IContext c,
			long since) throws CoreException {
		XPath.create(c, ObjectState.class)
			.eq(ObjectState.MemberNames.ObjectState_ServiceState, this.getServiceState(c))
			.compare(ObjectState.MemberNames.revision, ">", since)
			.addSortingAsc(ObjectState.MemberNames.revision)
			.batch((int) RestServices.BATCHSIZE, new IBatchProcessor<ObjectState>() {
	
				@Override
				public void onItem(ObjectState item, long offset, long total)
						throws Exception {
					rsr.datawriter.value(writeObjectStateToJson(item));
				}
			});
	}

	private void storeUpdate(IContext context,  ObjectState objectState,
			String eTag, String jsonString, boolean deleted) throws Exception {
		
		/* store the update*/
		long rev = getNextRevisionId(context);
		
		objectState.setetag(eTag);
		objectState.setdeleted(deleted);
		objectState.setjson(deleted ? "" : jsonString);
		objectState.setrevision(rev);
		objectState.commit();
		
		publishUpdate(objectState);
	}

	private void serveChangesList(final RestServiceRequest rsr, long since) throws CoreException {
		IContext c = Core.createSystemContext();
		
		
		rsr.datawriter.array();
		writeChanges(rsr, c, since);
		rsr.datawriter.endArray();
		
		rsr.close();
	}

	private void serveChangesFeed(RestServiceRequest rsr, long since) throws IOException, CoreException {
			//Continuation continuation = ContinuationSupport.getContinuation(rsr.request);
				
			if (rsr.request.getAttribute("lpsession") == null) {	
				// - this request just arrived (first branch) -> sleep until message arrive
				service.debug("New continuation on " + rsr.request.getPathInfo());
	
				//make sure headers are send and some data is written, so that clients do not wait for headers to complete
				rsr.response.getOutputStream().write("\r\n\r\n".getBytes(RestServices.UTF8)); //TODO: extract constant
				writeChanges(rsr, Core.createSystemContext(), since); //TODO: write changes or add to queue?
				rsr.response.flushBuffer();
				if (!rsr.response.isCommitted())
					throw new IllegalStateException("Not committing");
				
				AsyncContext asyncContext = rsr.request.startAsync();
				
				ChangeFeedSubscriber lpsession = new ChangeFeedSubscriber(asyncContext);
				longPollSessions.add(lpsession);
				rsr.request.setAttribute("lpsession", lpsession);
				
				lpsession.markInSync();
	
				asyncContext.setTimeout(RestServices.LONGPOLL_MAXDURATION * 1000); //TODO: use parameter
			}
			else { //expired
				ChangeFeedSubscriber lpsession = (ChangeFeedSubscriber)rsr.request.getAttribute("lpsession");
				longPollSessions.remove(lpsession);
			}
	}

	public void serveChanges(RestServiceRequest rsr) throws IOException, CoreException {
		rsr.response.setStatus(IMxRuntimeResponse.OK);
		rsr.response.flushBuffer();
		long since = 0;
	
		if (rsr.getContentType() == ContentType.HTML) //TODO: make separate block for this in rsr!
			rsr.startHTMLDoc();
		else if (rsr.getContentType() == ContentType.XML)
			rsr.startXMLDoc();
	
		if (rsr.request.getParameter("since") != null) //TODO: extract since constant
			since = Long.parseLong(rsr.request.getParameter("since"));
		
		if ("true".equals(rsr.request.getParameter("feed"))) 
			serveChangesFeed(rsr, since);
		else {
			serveChangesList(rsr, since);
			
			if (rsr.getContentType() == ContentType.HTML) //TODO: make separate block for this in rsr!
				rsr.endHTMLDoc();
		}
	}

	private void publishUpdate(ObjectState objectState) {
		// TODO async, parallel, separate thread etc etc. Or is continuation.resume async and isn't that needed at all?
		JSONObject json = writeObjectStateToJson(objectState);
		
		for(ChangeFeedSubscriber s: longPollSessions)
			try {
				s.addInstruction(json);
			} catch (IOException e) {
				RestServices.LOG.warn("Failed to publish update to some client: " + json);
			}
	}

	synchronized void processUpdate(String key, String jsonString, String eTag, boolean deleted) throws Exception {
	
		IContext context = Core.createSystemContext();
	
		ServiceState sState = getServiceState(context);
		
		ObjectState objectState = XPath.create(context, ObjectState.class)
				.eq(ObjectState.MemberNames.key, key)
				.eq(ObjectState.MemberNames.ObjectState_ServiceState, sState)
				.first();
		
		//not yet published
		if (objectState == null) {
			if (deleted) //no need to publish if it wasn't yet published
				return;
			
			objectState = new ObjectState(context);
			objectState.setkey(key);
			objectState.setObjectState_ServiceState(sState);
			storeUpdate(context, objectState, eTag, jsonString, deleted);
		}
		
		else if (objectState.getetag().equals(eTag) && objectState.getdeleted() != deleted) 
			return; //nothing changed
	
		else
			storeUpdate(context, objectState, eTag, jsonString, deleted);
	}

	public synchronized ServiceState getServiceState(final IContext context) throws CoreException {
		if (context.isInTransaction())
			throw new IllegalStateException("Context for getServiceState should not be in transaction!");
		
		if (this.serviceState == null)
			this.serviceState = XPath.create(context, ServiceState.class)
				.findOrCreateNoCommit(ServiceState.MemberNames.Name, service.getName()).getMendixObject();
		
		if (this.serviceState.isNew()) {
			//TODO: ..and change tracking enabled
			
			Core.commit(context, serviceState); //TODO: will break if initializing breaks halfway...
			
			RestServices.LOG.info(service.getName() + ": Initializing change log. This might take a while...");
			XPath.create(context, service.getSourceEntity())
				//.raw(this.constraint) //TODO:!
				.batch((int) RestServices.BATCHSIZE, new IBatchProcessor<IMendixObject>() {
	
					@Override
					public void onItem(IMendixObject item, long offset,
							long total) throws Exception {
						if (offset % 100 == 0)
							RestServices.LOG.info("Initialize change long for object " + offset + " of " + total);
						publishUpdate(context, item, true); //TODO: can be false if the constraint is applied raw above!
					}
				});
			
			RestServices.LOG.info(service.getName() + ": Initializing change log. DONE");
		}
		
		return ServiceState.initialize(context, serviceState);
	}

	private synchronized long getNextRevisionId(IContext context) throws CoreException {
		ServiceState state = getServiceState(context);
		long rev = state.getRevision() + 1;
		state.setRevision(rev);
		state.commit();
		return rev;
	}

	public static void publishDelete(IContext context, IMendixObject source) {
		if (source == null)
			return;
		//publishDelete has no checksontraint, since, if the object was not published yet, there will be no objectstate created or updated if source is deleted
		PublishedService service = RestServices.getServiceForEntity(source.getType());
		
		try {
	
			String key = service.getKey(context, source);
			if (!Utils.isValidKey(key)) {
				RestServices.LOG.warn("No valid key for object " + source + "; skipping updates");
				return;
			}
				
			service.getChangeManager().processUpdate(key, null, null, true);
		}
		catch(Exception e) {
			throw new RuntimeException("Failed to process change for " + source + ": " + e.getMessage(), e);
		}
	}

	public static void publishUpdate(IContext context, IMendixObject source) {
		publishUpdate(context, source, true);
	}

	public static void publishUpdate(IContext context, IMendixObject source, boolean checkConstraint) {
		if (source == null)
			return;
		
		PublishedService service = RestServices.getServiceForEntity(source.getType());
		
		try {
			//Check if publishable
			if (checkConstraint && !service.identifierInConstraint(context, source.getId()))
				return; 
			
			String key = service.getKey(context, source);
			if (!Utils.isValidKey(key)) {
				RestServices.LOG.warn("No valid key for object " + source + "; skipping updates");
				return;
			}
				
			IMendixObject view = service.convertSourceToView(context, source);
			JSONObject result = JsonSerializer.writeMendixObjectToJson(context, view);
					
			String jsonString = result.toString(4);
			String eTag = Utils.getMD5Hash(jsonString);
			
			service.getChangeManager().processUpdate(key, jsonString, eTag, false);
		}
		catch(Exception e) {
			throw new RuntimeException("Failed to process change for " + source + ": " + e.getMessage(), e);
		}
	}
}
