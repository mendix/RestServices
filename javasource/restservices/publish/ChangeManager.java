package restservices.publish;

import java.io.IOException;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.AsyncContext;

import org.apache.commons.lang.StringUtils;
import org.json.JSONObject;

import restservices.RestServices;
import restservices.proxies.ChangeItem;
import restservices.proxies.ServiceDefinition;
import restservices.proxies.ServiceObjectIndex;
import restservices.publish.RestPublishException.RestExceptionType;
import restservices.util.JSONSchemaBuilder;
import restservices.util.JsonSerializer;
import restservices.util.RestServiceRuntimeException;
import restservices.util.Utils;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;
import com.sun.xml.fastinfoset.stax.events.Util;
import communitycommons.XPath;
import communitycommons.XPath.IBatchProcessor;

public class ChangeManager {
	
	private PublishedService service;
	private final List<ChangeFeedSubscriber> longPollSessions = new Vector<ChangeFeedSubscriber>(); 
	private volatile ServiceObjectIndex serviceObjectIndex;
	private volatile boolean isRebuildingIndex = false;
	
	public ChangeManager(PublishedService service) throws CoreException {
		this.service = service;
		if (service.def.getEnableChangeTracking() && service.def.getEnableGet()) {
			IContext context = Core.createSystemContext();
			
			serviceObjectIndex = XPath.create(context, ServiceObjectIndex.class)
				.findOrCreate(ServiceObjectIndex.MemberNames.ServiceObjectIndex_ServiceDefinition, service.def);
			
			if (!calculateIndexVersion(service.def).equals(serviceObjectIndex.get_ConfigurationHash())) 
				rebuildIndex();
		}
	}

	JSONObject writeObjectStateToJson(ChangeItem state){
		JSONObject res = new JSONObject();
		res
			.put(RestServices.CHANGE_KEY, state.getKey())
			.put(RestServices.CHANGE_URL, service.getServiceUrl() + state.getKey())
			.put(RestServices.CHANGE_SEQNR, state.getSequenceNr())
			.put(RestServices.CHANGE_ETAG, state.getEtag())
			.put(RestServices.CHANGE_DELETED, state.getIsDeleted());
		
		if (!state.getIsDeleted())
			res.put(RestServices.CHANGE_DATA, new JSONObject(state.getJson()));
		
		return res;
	}

	void storeUpdate(ChangeItem objectState,
			String eTag, String jsonString, boolean deleted) throws Exception {
		
		/* store the update*/
		long rev = getNextSequenceNr();
		
		if (RestServices.LOGPUBLISH.isDebugEnabled())
			RestServices.LOGPUBLISH.debug("Updated: " + objectState.getKey() + " to revision " + rev);
		
		objectState.setEtag(eTag);
		objectState.setIsDeleted(deleted);
		objectState.setJson(deleted ? "" : jsonString);
		objectState.setSequenceNr(rev);
		objectState.set_IsDirty(false);
		objectState.commit();
		
		publishUpdate(objectState);
	}

	private ChangeItem writeChanges(final RestServiceRequest rsr, IContext c,
			long since) throws CoreException {
		if (since < 0)
			throw new IllegalArgumentException("Since parameter should be positive");
		
		final AtomicReference<ChangeItem> lastWrittenRevision = new AtomicReference<ChangeItem>();
		
		XPath.create(c, ChangeItem.class)
			.eq(ChangeItem.MemberNames.ChangeItem_ChangeLog, this.getServiceObjectIndex())
			.compare(ChangeItem.MemberNames.SequenceNr, ">", since)
			.addSortingAsc(ChangeItem.MemberNames.SequenceNr)
			.batch(RestServices.BATCHSIZE, new IBatchProcessor<ChangeItem>() {
	
				@Override
				public void onItem(ChangeItem item, long offset, long total)
						throws Exception {
					rsr.datawriter.value(writeObjectStateToJson(item));
					lastWrittenRevision.set(item);
				}
			});
		
		return lastWrittenRevision.get();
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
				
			if (!rsr.request.isAsyncSupported())
				throw new IllegalStateException("Async is not supported :(. Cannot serve REST feed");
			if (since < -1)
				throw new IllegalArgumentException("Since parameter should be positive or -1. ");
			
			if (rsr.request.getAttribute("lpsession") == null) {	
				// - this request just arrived (first branch) -> sleep until message arrive
				if (RestServices.LOGPUBLISH.isDebugEnabled())
					RestServices.LOGPUBLISH.debug("New continuation on " + rsr.request.getPathInfo());
	
				//make sure headers are send and some data is written, so that clients do not wait for headers to complete
				rsr.response.getOutputStream().write(RestServices.END_OF_HTTPHEADER.getBytes(RestServices.UTF8));

				ChangeItem lastWrittenChange = null;
				
				if (since != -1) {
					//write any changes between 'since' and the latest change
					lastWrittenChange = writeChanges(rsr, Core.createSystemContext(), since);

					//special case, if there where pending changes and the timeout is negative, which means "return when there are any changes", finish the request now. 
					if (lastWrittenChange != null && maxDurationSeconds < 0) {
						rsr.endDoc();
						return;
					}
				}
				
				rsr.response.flushBuffer();
				AsyncContext asyncContext = rsr.request.startAsync();
				
				/*
				 * This synchronized block is to make sure that a new incoming connection does not miss any changes that are
				 * processed by this change manager between the moment the missing changes are written, and the moment that the subscriber is
				 * actually registered. 
				 * 
				 * To make sure that doesn't happen, we again try to write any missing changes, but now in a synchronized block (note that processUpdate 
				 * is also synchronized). We don't synchronize on the first 'writeChanges' call above, because initially there might be many many 
				 * changes missing, and we don't want all consumers to block on them. 
				 */
				synchronized(this) {
					if (since != -1)
						writeChanges(rsr, Core.createSystemContext(), lastWrittenChange == null ? 0 : lastWrittenChange.getSequenceNr());					
					
					ChangeFeedSubscriber lpsession = new ChangeFeedSubscriber(asyncContext, maxDurationSeconds < 0, this);

					longPollSessions.add(lpsession);
					rsr.request.setAttribute("lpsession", lpsession);
				}
				
				if (maxDurationSeconds != 0L)
					asyncContext.setTimeout(Math.abs(maxDurationSeconds) * 1000); 
			}
			
			else { //request already has an 'lpsession', so this is not the initial call, so we conclude that the continuation has expired
				ChangeFeedSubscriber lpsession = (ChangeFeedSubscriber)rsr.request.getAttribute("lpsession");
				unregisterListener(lpsession);
			}
	}

	public void unregisterListener(ChangeFeedSubscriber lpsession)
	{
		longPollSessions.remove(lpsession);
	}

	public void serveChanges(RestServiceRequest rsr, boolean asFeed) throws IOException, CoreException, RestPublishException {
		if (!service.def.getEnableChangeTracking())
			throw new RestPublishException(RestExceptionType.METHOD_NOT_ALLOWED, "Change tracking is not enabled for this service");
		
		rsr.response.setStatus(IMxRuntimeResponse.OK);
		rsr.response.flushBuffer();
		long since = 0;
	
		rsr.startDoc();
		
		if (rsr.request.getParameter(RestServices.PARAM_SINCE) != null) 
			since = Long.parseLong(rsr.request.getParameter(RestServices.PARAM_SINCE));
		
		if (asFeed) {
			String longPollMaxDuration = rsr.request.getParameter(RestServices.PARAM_TIMEOUT);
			serveChangesFeed(rsr, since, Util.isEmptyString(longPollMaxDuration) ? RestServices.LONGPOLL_MAXDURATION : Long.valueOf(longPollMaxDuration));
		}

		else {
			serveChangesList(rsr, since);
			
			rsr.endDoc(); //Changes Feed doc ends async
		}
	}

	private void publishUpdate(ChangeItem objectState) {
		JSONObject json = writeObjectStateToJson(objectState);
		
		for(int i = longPollSessions.size() - 1; i >= 0; i--) {
			ChangeFeedSubscriber s = longPollSessions.get(i);
			try {
				s.addInstruction(json);
			} catch (Exception e) {
				RestServices.LOGPUBLISH.warn("Failed to publish update to some client: " + json, e);
				unregisterListener(s);
				s.complete();
			}
		}
	}

	synchronized private void processUpdate(String key, String jsonString, String eTag, boolean deleted) throws Exception {
		IContext context = Core.createSystemContext();
	
		ServiceObjectIndex sState = getServiceObjectIndex();
		
		ChangeItem objectState = XPath.create(context, ChangeItem.class)
				.eq(ChangeItem.MemberNames.Key, key)
				.eq(ChangeItem.MemberNames.ChangeItem_ChangeLog, sState)
				.first();
		
		//not yet published
		if (objectState == null) {
			if (deleted) //no need to publish if it wasn't yet published
				return;
			
			objectState = new ChangeItem(context);
			objectState.setKey(key);
			objectState.setChangeItem_ChangeLog(sState);
			storeUpdate(objectState, eTag, jsonString, deleted);
		}
		
		//nothing changed
		else if (
				(deleted && objectState.getIsDeleted()) 
			|| (!deleted && !objectState.getIsDeleted() && eTag != null && eTag.equals(objectState.getEtag()))
		) {
			if (objectState.get_IsDirty() && !objectState.getIsDeleted()) {
				//if there is a dirty mark, but no changes, the object should be preserved although it isn' t updated
				objectState.set_IsDirty(false);
				objectState.commit();
			}
			return; 
		}
		
		//changed
		else
			storeUpdate(objectState, eTag, jsonString, deleted);
	}

	private synchronized long getNextSequenceNr() {
		ServiceObjectIndex state;
		try {
			state = getServiceObjectIndex();
			long seq = state.getSequenceNr() + 1;
			state.setSequenceNr(seq);
			state.commit();
			return seq;
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
			RestServices.LOGPUBLISH.warn("Skipped publishing delete, changetracking is not enabled for service " + service.getName());
			return;
		}
		
		try {
	
			String key = service.getKey(context, source);
			if (!Utils.isValidKey(key)) {
				RestServices.LOGPUBLISH.warn("No valid key for object " + source + "; skipping updates");
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
			RestServices.LOGPUBLISH.warn("Skipped publishing update, changetracking is not enabled for service " + service.getName());
			return;
		}	
		service.getChangeManager().publishUpdateHelper(context, source, checkConstraint);
	}

	void publishUpdateHelper(IContext context, IMendixObject source,
			boolean checkConstraint) {
		try {
			//Check if publishable
			if (checkConstraint && !service.identifierInConstraint(context, source.getId())) {
				publishDelete(context, source); //maybe the object was visible but not anymore
				return; 
			}
			
			String key = service.getKey(context, source);
			if (!Utils.isValidKey(key)) {
				RestServices.LOGPUBLISH.warn("No valid key for object " + source + "; skipping updates");
				return;
			}
				
			IMendixObject view = service.convertSourceToView(context, source);
			JSONObject result = JsonSerializer.writeMendixObjectToJson(context, view);
					
			String jsonString = result.toString(4);
			String eTag = Utils.getMD5Hash(jsonString);
			
			processUpdate(key, jsonString, eTag, false);
		}
		catch(Exception e) {
			throw new RuntimeException("Failed to process change for " + source + ": " + e.getMessage(), e);
		}
	}


	ServiceObjectIndex getServiceObjectIndex() {
		return this.serviceObjectIndex;
	}
	
	public void rebuildIndex() throws CoreException {
		synchronized(this) {
			if (isRebuildingIndex) {
				throw new IllegalStateException("SKIP rebuilding index, index is already building... ");
			}
			isRebuildingIndex = true;
		}
		
		try {
			final IContext context = Core.createSystemContext();
			RestServices.LOGPUBLISH.info(service.getName() + ": Initializing change log. This might take a while...");
			RestServices.LOGPUBLISH.info(service.getName() + ": Initializing change log. Marking old index dirty...");
			
			//int NR_OF_BATCHES = 8;
			
			/**
			 * From now on, consider everything dirty
			 */
			XPath.create(context, ChangeItem.class)
				.eq(ChangeItem.MemberNames.ChangeItem_ChangeLog, getServiceObjectIndex())
				.batch(RestServices.BATCHSIZE/*, NR_OF_BATCHES*/, new IBatchProcessor<ChangeItem>() {
	
					@Override
					public void onItem(ChangeItem item, long offset, long total)
							throws Exception {
						item.set_IsDirty(true);
						item.commit();
					}
				});
			
			RestServices.LOGPUBLISH.info(service.getName() + ": Initializing change log. Marking old index dirty... DONE. Rebuilding index for existing objects...");
			
			/** 
			 * Republish all known objects, if they are part of the constraint (won' t result in an update if nothing actually changed)
			 */
			XPath.create(context, service.getSourceEntity())
				.append(service.getConstraint(context).replaceAll("(^\\[|\\]$)","")) //Note: trims brackets
				.batch(RestServices.BATCHSIZE/*, NR_OF_BATCHES*/, new IBatchProcessor<IMendixObject>() {
	
					@Override
					public void onItem(IMendixObject item, long offset,
							long total) throws Exception {
						if (offset % 100 == 0)
							RestServices.LOGPUBLISH.info("Initialize change long for object " + offset + " of " + total);
						publishUpdateHelper(context, item, false); 
					}
				});
			
			RestServices.LOGPUBLISH.info(service.getName() + ": Initializing change log. Rebuilding... DONE. Removing old entries...");

			/**
			 * Everything that is marked dirty, is either deleted earlier and shouldn' t be dirty, or should be deleted now. 
			 */
			XPath.create(context, ChangeItem.class)
				.eq(ChangeItem.MemberNames.ChangeItem_ChangeLog, getServiceObjectIndex())
				.eq(ChangeItem.MemberNames._IsDirty, true)
				.batch(RestServices.BATCHSIZE/*, NR_OF_BATCHES*/, new IBatchProcessor<ChangeItem>() {
	
					@Override
					public void onItem(ChangeItem item, long offset, long total)
							throws Exception {
						//was already deleted, so OK
						if (item.getIsDeleted() == true) {
							item.set_IsDirty(false);
							item.commit();
						}
						
						//wasn' t deleted before. Delete now. 
						else
							storeUpdate(item, null, null, true);
					}
			});
			
			serviceObjectIndex.set_ConfigurationHash(calculateIndexVersion(service.def));
			serviceObjectIndex.commit();
			
			RestServices.LOGPUBLISH.info(service.getName() + ": Initializing change log. DONE");
		}
		finally {
			isRebuildingIndex = false;
		}
	}

	/**
	 * Determines on which settings this index was build. If changed, a new index should be generated
	 * @param def
	 * @return
	 */
	private String calculateIndexVersion(ServiceDefinition def) {
		IMetaObject returnType = Core.getMetaObject(Core.getReturnType(def.getOnPublishMicroflow()).getObjectType());
		JSONObject exporttype = JSONSchemaBuilder.build(returnType);
		
		return StringUtils.join(new String[] {
			def.getSourceEntity(), 
			def.getSourceKeyAttribute(), 
			def.getSourceConstraint(),
			exporttype.toString()
		},";");
	}

	public void dispose() {
		while(!longPollSessions.isEmpty()) {
			ChangeFeedSubscriber s = longPollSessions.remove(0);
			s.complete();
		}
	}

	public long getNrOfConnections() {
		return longPollSessions.size();
	}
}
