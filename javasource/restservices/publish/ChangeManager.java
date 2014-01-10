package restservices.publish;

import java.io.IOException;
import java.util.List;
import java.util.Vector;
import javax.servlet.AsyncContext;

import org.json.JSONObject;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.sun.xml.fastinfoset.stax.events.Util;

import communitycommons.XPath;
import communitycommons.XPath.IBatchProcessor;

import restservices.RestServices;
import restservices.proxies.ObjectState;
import restservices.proxies.ServiceState;
import restservices.util.JsonSerializer;
import restservices.util.RestServiceRuntimeException;
import restservices.util.Utils;

public class ChangeManager {
	
	private PublishedService service;
	final private List<ChangeFeedSubscriber> longPollSessions = new Vector<ChangeFeedSubscriber>(); 
	private IMendixObject serviceState;

	public ChangeManager(PublishedService service) {
		this.service = service;
	}

	JSONObject writeObjectStateToJson(ObjectState state){
		JSONObject res = new JSONObject();
		res
			.put("key", state.getkey())
			.put("url", service.getServiceUrl() + state.getkey())
			.put("rev", state.getrevision())
			.put("etag", state.getetag())
			.put("deleted", state.getdeleted());
		
		if (!state.getdeleted())
			res.put("data", new JSONObject(state.getjson()));
		
		return res;
	}

	private void storeUpdate(IContext context,  ObjectState objectState,
			String eTag, String jsonString, boolean deleted) throws Exception {
		
		/* store the update*/
		long rev = getNextRevisionId(context);
		
		if (RestServices.LOG.isDebugEnabled())
			RestServices.LOG.debug("Updated: " + objectState.getkey() + " to revision " + rev);
		
		objectState.setetag(eTag);
		objectState.setdeleted(deleted);
		objectState.setjson(deleted ? "" : jsonString);
		objectState.setrevision(rev);
		objectState.commit();
		
		publishUpdate(objectState);
	}

	private void writeChanges(final RestServiceRequest rsr, IContext c,
			long since) throws CoreException {
		
		XPath.create(c, ObjectState.class)
			.eq(ObjectState.MemberNames.ObjectState_ServiceState, this.getServiceState(c))
			.compare(ObjectState.MemberNames.revision, ">=", since)
			.addSortingAsc(ObjectState.MemberNames.revision)
			.batch((int) RestServices.BATCHSIZE, new IBatchProcessor<ObjectState>() {
	
				@Override
				public void onItem(ObjectState item, long offset, long total)
						throws Exception {
					rsr.datawriter.value(writeObjectStateToJson(item));
				}
			});
	}

	
	private void serveChangesList(final RestServiceRequest rsr, long since) throws CoreException {
		IContext c = Core.createSystemContext();
		
		rsr.datawriter.array();
		writeChanges(rsr, c, since);
		rsr.datawriter.endArray();
		
		rsr.close();
	}

	/**
	 * 
	 * @param rsr
	 * @param since
	 * @param maxDurationSeconds. Zero for never, positive for fixed timeout, negative for fixed timeout or first update that needs publishing 
	 * @throws IOException
	 * @throws CoreException
	 */
	private void serveChangesFeed(RestServiceRequest rsr, long since, long maxDurationSeconds) throws IOException, CoreException {
			//Continuation continuation = ContinuationSupport.getContinuation(rsr.request);
				
			if (rsr.request.getAttribute("lpsession") == null) {	
				// - this request just arrived (first branch) -> sleep until message arrive
				service.debug("New continuation on " + rsr.request.getPathInfo());
	
				//make sure headers are send and some data is written, so that clients do not wait for headers to complete
				rsr.response.getOutputStream().write(RestServices.END_OF_HTTPHEADER.getBytes(RestServices.UTF8));
				writeChanges(rsr, Core.createSystemContext(), since); 
				rsr.response.flushBuffer();
				
				AsyncContext asyncContext = rsr.request.startAsync();
				
				ChangeFeedSubscriber lpsession = new ChangeFeedSubscriber(asyncContext, maxDurationSeconds < 0);
				longPollSessions.add(lpsession);
				rsr.request.setAttribute("lpsession", lpsession);
				
				lpsession.markInSync();
	
				if (maxDurationSeconds != 0L)
					asyncContext.setTimeout(Math.abs(maxDurationSeconds) * 1000); 
			}
			
			else { //expired or completed
				ChangeFeedSubscriber lpsession = (ChangeFeedSubscriber)rsr.request.getAttribute("lpsession");
				longPollSessions.remove(lpsession);
				rsr.endDoc();
			}
	}

	public void serveChanges(RestServiceRequest rsr) throws IOException, CoreException {
		if (!service.def.getEnableChangeTracking())
			throw new NotFoundException("Change tracking is not enabled for this service");
		
		rsr.response.setStatus(IMxRuntimeResponse.OK);
		rsr.response.flushBuffer();
		long since = 0;
	
		rsr.startDoc();
		
		if (rsr.request.getParameter(RestServices.PARAM_SINCE) != null) 
			since = Long.parseLong(rsr.request.getParameter(RestServices.PARAM_SINCE));
		
		if ("true".equals(rsr.request.getParameter(RestServices.PARAM_FEED))) {
			String longPollMaxDuration = rsr.request.getParameter(RestServices.PARAM_TIMEOUT);
			serveChangesFeed(rsr, since, Util.isEmptyString(longPollMaxDuration) ? RestServices.LONGPOLL_MAXDURATION : Long.valueOf(longPollMaxDuration));
		}

		else {
			serveChangesList(rsr, since);
			
			rsr.endDoc(); //Changes Feed doc ends async
		}
	}

	private void publishUpdate(ObjectState objectState) {
		JSONObject json = writeObjectStateToJson(objectState);
		
		for(int i = longPollSessions.size() - 1; i >= 0; i--) {
			ChangeFeedSubscriber s = longPollSessions.get(i);
			try {
				s.addInstruction(json);
			} catch (Exception e) {
				RestServices.LOG.warn("Failed to publish update to some client: " + json);
				longPollSessions.remove(s);
				s.complete();
			}
		}
	}

	private synchronized void processUpdate(String key, String jsonString, String eTag, boolean deleted) throws Exception {
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

	private synchronized ServiceState getServiceState(final IContext context) throws CoreException {
		if (context.isInTransaction())
			throw new IllegalStateException("Context for getServiceState should not be in transaction!");
		
		if (this.serviceState == null)
			this.serviceState = XPath.create(context, ServiceState.class)
				.findOrCreateNoCommit(ServiceState.MemberNames.Name, service.getName()).getMendixObject();
		
		if (this.serviceState.isNew()) {
			Core.commit(context, serviceState); 
			
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

	private synchronized long getNextRevisionId(IContext context) {
		ServiceState state;
		try {
			state = getServiceState(context);
			long rev = state.getRevision() + 1;
			state.setRevision(rev);
			state.commit();
			return rev;
		} catch (CoreException e) {
			throw new RestServiceRuntimeException(e);
		}
	}

	public static void publishDelete(IContext context, IMendixObject source) {
		if (source == null)
			return;
		
		//publishDelete has no checksonraint, since, if the object was not published yet, there will be no objectstate created or updated if source is deleted
		PublishedService service = RestServices.getServiceForEntity(source.getType());
		
		if (!service.def.getEnableChangeTracking()) {
			RestServices.LOG.warn("Skipped publishing delete, changetracking is not enabled for service " + service.getName());
			return;
		}
		
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
		
		if (!service.def.getEnableChangeTracking()) {
			RestServices.LOG.warn("Skipped publishing update, changetracking is not enabled for service " + service.getName());
			return;
		}	
		try {
			//Check if publishable
			if (checkConstraint && !service.identifierInConstraint(context, source.getId())) {
				publishDelete(context, source); //maybe the object was visible but not anymore
				return; 
			}
			
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

	public void dispose() {
		while(!longPollSessions.isEmpty()) {
			ChangeFeedSubscriber s = longPollSessions.remove(0);
			s.complete();
		}
	}
}
