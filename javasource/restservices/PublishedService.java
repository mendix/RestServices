package restservices;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.output.StringBuilderWriter;
import org.codehaus.jackson.annotate.JsonProperty;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.json.JSONArray;
import org.json.JSONObject;

import restservices.RestServiceRequest.ContentType;
import restservices.proxies.ObjectState;
import restservices.proxies.ServiceState;

import com.google.common.collect.ImmutableMap;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.core.objectmanagement.member.MendixObjectReference;
import com.mendix.core.objectmanagement.member.MendixObjectReferenceSet;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.connectionbus.requests.IRetrievalSchema;
import com.mendix.systemwideinterfaces.connectionbus.requests.ISortExpression.SortDirection;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.IMendixObjectMember;
import com.mendix.systemwideinterfaces.core.meta.IMetaAssociation;
import com.mendix.systemwideinterfaces.core.meta.IMetaAssociation.AssociationType;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive;
import communitycommons.XPath;
import communitycommons.XPath.IBatchProcessor;

public class PublishedService {

	private static final long BATCHSIZE = 1000;
	final private Set<LongPollSession> longPollSessions = Collections.newSetFromMap(new ConcurrentHashMap<LongPollSession, Boolean>());


	@JsonProperty
	private String servicename;
	
	@JsonProperty
	private String sourceentity;
	
	@JsonProperty
	private String constraint;
	
	public String getConstraint() {
		return constraint == null ? "" : constraint;
	}
	
	@JsonProperty
	private String idattribute;
	
	@JsonProperty
	private String publishentity;
	
	@JsonProperty
	private String publishmicroflow;

	private IMetaObject sourceMetaEntity;

	private IMetaObject publishMetaEntity;
	private IMendixObject serviceState;

	public void consistencyCheck() {
		// source exists and persistent
		// keyattr exists
		// constraint is valid
		// name is a valid identifier
		// reserved attributes: _key, _etag
	}

	public String getName() {
		return servicename;
	}

	public String getSourceEntity() {
		return sourceentity;
	}

	public void serveListing(RestServiceRequest rsr) throws CoreException {
		IRetrievalSchema schema = Core.createRetrievalSchema();
		schema.addSortExpression(this.idattribute, SortDirection.ASC);
		schema.addMetaPrimitiveName(this.idattribute);
		schema.setAmount(BATCHSIZE);

		switch(rsr.getContentType()) {
		case HTML:
			rsr.startHTMLDoc();
			break;
		case XML:
			rsr.startXMLDoc();
			rsr.write("<items>");
			break;
		case JSON:
			rsr.datawriter.array();
			break;
		}
		
		long offset = 0;
		List<IMendixObject> result;
		
		rsr.datawriter.array();
		do {
			schema.setOffset(offset);
			result = Core.retrieveXPathSchema(rsr.getContext(), "//" + this.sourceentity + this.getConstraint(), schema, false);
		
			for(IMendixObject item : result) {
				String key = item.getMember(rsr.getContext(), idattribute).parseValueToString(rsr.getContext());
				if (!RestServices.isValidKey(key))
					continue;

				String url = this.getServiceUrl() + key; //TODO: url param encode key?
				rsr.datawriter.value(url);
			}
			
			offset += BATCHSIZE;
		}
		while(!result.isEmpty());
		rsr.datawriter.endArray();
		
		switch(rsr.getContentType()) {
		case HTML:
			rsr.endHTMLDoc();
			break;
		case XML:
			rsr.write("</items>");
			break;
		case JSON:
			rsr.datawriter.endArray();
			break;
		}
		
		rsr.close();
	}

	private String getServiceUrl() {
		return Core.getConfiguration().getApplicationRootUrl() + "rest/" + this.servicename + "/";
	}

	public void serveGet(RestServiceRequest rsr, String key) throws Exception {
		String xpath = XPath.create(rsr.getContext(), this.sourceentity).eq(this.idattribute, key).getXPath() + this.getConstraint();
		
		List<IMendixObject> results = Core.retrieveXPathQuery(rsr.getContext(), xpath, 1, 0, ImmutableMap.of("id", "ASC"));
		if (results.size() == 0) {
			rsr.setStatus(IMxRuntimeResponse.NOT_FOUND);
			return;
		}
		
		IMendixObject view = convertSourceToView(rsr.getContext(), results.get(0));
		JSONObject result = convertViewToJson(rsr.getContext(), view);
				
		String jsonString = result.toString(4);
		String eTag = getMD5Hash(jsonString);
		
		if (eTag.equals(rsr.request.getHeader(Constants.IFNONEMATCH_HEADER))) {
			rsr.setStatus(IMxRuntimeResponse.NOT_MODIFIED);
			rsr.close();
			return;
		}
		rsr.response.setHeader(Constants.ETAG_HEADER, eTag);
		
		result.put(Constants.ETAG_ATTR, eTag);
		
		switch(rsr.getContentType()) {
		case JSON:
			rsr.write(jsonString);
			break;
		case HTML:
			rsr.startHTMLDoc();
			rsr.write("<h1>").write(servicename).write("/").write(key).write("</h1>");
			rsr.datawriter.value(result);
			rsr.endHTMLDoc();
			break;
		case XML:
			rsr.startXMLDoc(); //TODO: doesnt JSON.org provide a toXML?
			rsr.write("<" + this.servicename + ">");
			rsr.datawriter.value(result);
			rsr.write("</" + this.servicename + ">");
			break;
		}
		
		rsr.close();
		rsr.getContext().getSession().release(view.getId());
	}
	
	//TODO: move to utils
	public static String getMD5Hash(String jsonString)
			throws UnsupportedEncodingException {
		return DigestUtils.md5Hex(jsonString.getBytes(Constants.UTF8));
	}

	/**
	 * returns a json string containingURL if id is persistable or json object if with the json representation if the object is not. s
	 * @param rsr
	 * @param id
	 * @return
	 * @throws Exception 
	 */
	public static Object identifierToJSON(IContext context, IMendixIdentifier id) throws Exception {
		if (id == null)
			return null;
		
		/* persistable object, generate url */
		if (Core.getMetaObject(id.getObjectType()).isPersistable()) {
		
			PublishedService service = RestServices.getServiceForEntity(id.getObjectType());
			if (service == null) {
				RestServices.LOG.warn("No RestService has been definied for type: " + id.getObjectType() + ", identifier could not be serialized");
				return null;
			}
		
			if (service.identifierInConstraint(context, id)) {
				IMendixObject obj = Core.retrieveId(context, id); //TODO: inefficient, especially for refsets, use retrieveIds?
				if (obj == null) {
					RestServices.LOG.warn("Failed to retrieve identifier: " + id + ", does the object still exist?");
					return null;
				}
				return service.getObjecturl(context, obj);
			}
			return null;
		}
		
		/* transient object, export */
		else {
			IMendixObject obj = Core.retrieveId(context, id); //TODO: inefficient, especially for refsets, use retrieveIds?
			if (obj == null) {
				RestServices.LOG.warn("Failed to retrieve identifier: " + id + ", does the object still exist?");
				return null;
			}
			return convertViewToJson(context, obj);
		}
	}

	//TODO: move to separate class?
	public static JSONObject convertViewToJson(IContext context, IMendixObject view) throws Exception {
		JSONObject res = new JSONObject();
		
		Map<String, ? extends IMendixObjectMember<?>> members = view.getMembers(context);
		for(java.util.Map.Entry<String, ? extends IMendixObjectMember<?>> e : members.entrySet())
			serializeMember(context, res, e.getValue(), view.getMetaObject());
		
		return res;
	}

	private IMetaObject getSourceMetaEntity() {
		if (this.sourceMetaEntity == null)
			this.sourceMetaEntity = Core.getMetaObject(this.sourceentity);
		return this.sourceMetaEntity;
	}
	
	private IMetaObject getPublishMetaEntity() {//TODO: or not use property but reflect on convert microflow?
		if (this.publishMetaEntity == null)
			this.publishMetaEntity = Core.getMetaObject(this.publishentity);
		return this.publishMetaEntity;
	}

	public IMendixObject convertSourceToView(IContext context, IMendixObject source) throws CoreException {
		return (IMendixObject) Core.execute(context, this.publishmicroflow, source);
	}

	public static void serializeMember(IContext context, JSONObject target,
			IMendixObjectMember<?> member, IMetaObject viewType) throws Exception {
		if (context == null)
			throw new IllegalStateException("Context is null");
	
		Object value = member.getValue(context);
		String memberName = member.getName();
		
		//Primitive?
		if (!(member instanceof MendixObjectReference) && !(member instanceof MendixObjectReferenceSet)) {
			switch(viewType.getMetaPrimitive(member.getName()).getType()) {
			case AutoNumber:
			case Long:
			case Boolean:
			case Currency:
			case Float:
			case Integer:
				//Numbers or bools should never be null!
				if (value == null)
					throw new IllegalStateException("Primitive member " + member.getName() + " should not be null!");
	
				target.put(memberName, value);
				break;
			case Enum:
			case HashString:
			case String:
				if (value == null)
					target.put(memberName, JSONObject.NULL);
				target.put(memberName, value);
				break;
			case DateTime:
				if (value == null)
					target.put(memberName, JSONObject.NULL);
					
				target.put(memberName, (long)(((Date)value).getTime()));
				break;
			case Binary:
			default: 
				throw new IllegalStateException("Not supported Mendix Membertype for member " + memberName);
			}
		}
			
		/**
		 * Reference
		 */
		else if (member instanceof MendixObjectReference){
			if (value != null) 
				value = identifierToJSON(context, (IMendixIdentifier) value);
			
			if (value == null)
				target.put(Utils.getShortMemberName(memberName), JSONObject.NULL);
			else
				target.put(Utils.getShortMemberName(memberName), value);
		}
		
		/**
		 * Referenceset
		 */
		else if (member instanceof MendixObjectReferenceSet){
			JSONArray ar = new JSONArray();
			if (value != null) {
				@SuppressWarnings("unchecked")
				List<IMendixIdentifier> ids = (List<IMendixIdentifier>) value;
				for(IMendixIdentifier id : ids) if (id != null) {
					Object url = identifierToJSON(context, id);
					if (url != null)
						ar.put(url);
				}
			}
			target.put(Utils.getShortMemberName(memberName), ar);			
		}
		
		else
			throw new IllegalStateException("Unimplemented membertype " + member.getClass().getSimpleName());
	}

	public boolean identifierInConstraint(IContext c, IMendixIdentifier id) throws CoreException {
		if (this.getConstraint().isEmpty())
			return true;
		return Core.retrieveXPathQueryAggregate(c, "count(//" + this.sourceentity + "[id='" + id.toLong() + "']" + this.getConstraint()) == 1;
	}

	public String getObjecturl(IContext c, IMendixObject obj) {
		//Pre: inConstraint is checked!, obj is not null
		String key = getKey(c, obj);
		if (!RestServices.isValidKey(key))
			throw new IllegalStateException("Invalid key for object " + obj.toString());
		return this.getServiceUrl() + key;
	}

	public String getKey(IContext c, IMendixObject obj) {
		return obj.getMember(c, idattribute).parseValueToString(c);
	}

	//TODO: replace with something recursive
	public Map<String, String> getPublishedMembers() {
		Map<String, String> res = new HashMap<String, String>();
		for(IMetaPrimitive prim : this.getPublishMetaEntity().getMetaPrimitives())
			res.put(prim.getName(), prim.getType().toString());
		for(IMetaAssociation assoc : this.getPublishMetaEntity().getMetaAssociationsParent()) {
			PublishedService service = RestServices.getServiceForEntity(assoc.getChild().getName());
			if (service == null)
				continue;
			String name = Utils.getShortMemberName(assoc.getName());
			String type = assoc.getType() == AssociationType.REFERENCESET ? "[" + service.getServiceUrl() + "]" : service.getServiceUrl();
			res.put(name,  type);
		}
		return res;
	}
	
	public void serveServiceDescription(RestServiceRequest rsr) {
		rsr.datawriter.object()
			.key("name").value(this.servicename)
			.key("url").value(this.getServiceUrl())
			.key("attributes").object();
		
		for(Entry<String, String> e : getPublishedMembers().entrySet()) 
			rsr.datawriter.key(e.getKey()).value(e.getValue());
		
		rsr.datawriter.endObject().endObject();
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

	private void serveChangesList(final RestServiceRequest rsr, long since) throws CoreException {
		IContext c = Core.createSystemContext();
		
		
		rsr.datawriter.array();
		writeChanges(rsr, c, since);
		rsr.datawriter.endArray();
		
		rsr.close();
	}

	public void writeChanges(final RestServiceRequest rsr, IContext c,
			long since) throws CoreException {
		XPath.create(c, ObjectState.class)
			.eq(ObjectState.MemberNames.ObjectState_ServiceState, this.getServiceState(c))
			.compare(ObjectState.MemberNames.revision, ">", since)
			.addSortingAsc(ObjectState.MemberNames.revision)
			.batch((int) BATCHSIZE, new IBatchProcessor<ObjectState>() {

				@Override
				public void onItem(ObjectState item, long offset, long total)
						throws Exception {
					rsr.datawriter.value(writeObjectStateToJson(item));
				}
			});
	}
	
	//TODO: move to other class
	private JSONObject writeObjectStateToJson(ObjectState state){
		JSONObject res = new JSONObject();
		res
			.put("key", state.getkey()) //TODO: use constants
			.put("url", getServiceUrl() + state.getkey())
			.put("rev", state.getrevision())
			.put("etag", state.getetag())
			.put("deleted", state.getdeleted())
			.put("data", new JSONObject(state.getjson()));
		return res;
	}

	private void serveChangesFeed(RestServiceRequest rsr, long since) throws IOException, CoreException {
			Continuation continuation = ContinuationSupport.getContinuation(rsr.request);
				
			if (continuation.isInitial()) {	
				// - this request just arrived (first branch) -> sleep until message arrive
				debug("New continuation on " + rsr.request.getPathInfo());

				writeChanges(rsr, Core.createSystemContext(), since); //TODO: write changes or add to queue?
				rsr.response.flushBuffer();
				
				LongPollSession lpsession = new LongPollSession(continuation, this);
				longPollSessions.add(lpsession);
				continuation.setAttribute("lpsession", lpsession);
				
				lpsession.markInSync();

				continuation.setTimeout(Constants.LONGPOLL_MAXDURATION * 1000); //TODO: use parameter
				continuation.suspend(rsr.response);
			}
			else if (continuation.isExpired()) {
					longPollSessions.remove((LongPollSession)continuation.getAttribute("lpsession"));
					continuation.complete();
			}
			else
				throw new IllegalStateException("Illegal state");
	}
	

	private void debug(String msg) {
		if (RestServices.LOG.isDebugEnabled())
			RestServices.LOG.debug(msg);
	}

	public synchronized ServiceState getServiceState(final IContext context) throws CoreException {
		if (context.isInTransaction())
			throw new IllegalStateException("Context for getServiceState should not be in transaction!");
		
		if (this.serviceState == null)
			this.serviceState = XPath.create(context, ServiceState.class)
				.findOrCreateNoCommit(ServiceState.MemberNames.Name, getName()).getMendixObject();
		
		if (this.serviceState.isNew()) {
			//TODO: ..and change tracking enabled
			
			Core.commit(context, serviceState); //TODO: will break if initializing breaks halfway...
			
			RestServices.LOG.info(this.getName() + ": Initializing change log. This might take a while...");
			XPath.create(context, this.sourceentity)
				//.raw(this.constraint) //TODO:!
				.batch((int) BATCHSIZE, new IBatchProcessor<IMendixObject>() {

					@Override
					public void onItem(IMendixObject item, long offset,
							long total) throws Exception {
						if (offset % 100 == 0)
							RestServices.LOG.info("Initialize change long for object " + offset + " of " + total);
						ChangeTracker.publishUpdate(context, item, true); //TODO: can be false if the constraint is applied raw above!
					}
				});
			
			RestServices.LOG.info(this.getName() + ": Initializing change log. DONE");
		}
		
		return ServiceState.initialize(context, serviceState);
	}

	synchronized void processUpdate(String key, String jsonString, String eTag, boolean deleted) throws Exception {
	
		IContext context = Core.createSystemContext();
	
		ServiceState serviceState = getServiceState(context);
		
		ObjectState objectState = XPath.create(context, ObjectState.class)
				.eq(ObjectState.MemberNames.key, key)
				.eq(ObjectState.MemberNames.ObjectState_ServiceState, serviceState)
				.first();
		
		//not yet published
		if (objectState == null) {
			if (deleted) //no need to publish if it wasn't yet published
				return;
			
			objectState = new ObjectState(context);
			objectState.setkey(key);
			objectState.setObjectState_ServiceState(serviceState);
			storeUpdate(context, objectState, eTag, jsonString, deleted);
		}
		
		else if (objectState != null && objectState.getetag().equals(eTag) && objectState.getdeleted() != deleted) 
			return; //nothing changed
	
		else
			storeUpdate(context, objectState, eTag, jsonString, deleted);
	}

	private synchronized long getNextRevisionId(IContext context) throws CoreException {
		ServiceState state = getServiceState(context);
		long rev = state.getRevision() + 1;
		state.setRevision(rev);
		state.commit();
		return rev;
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

	private void publishUpdate(ObjectState objectState) throws UnsupportedEncodingException {
		// TODO async, parallel, separate thread etc etc. Or is continuation.resume async and isn't that needed at all?
		JSONObject json = writeObjectStateToJson(objectState);
		
		for(LongPollSession s: longPollSessions)
			try {
				s.addInstruction(json);
			} catch (IOException e) {
				RestServices.LOG.warn("Failed to publish update to some client: " + json);
			}
	}

}

