package restservices.publish;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.json.JSONObject;

import restservices.RestServices;
import restservices.proxies.ObjectState;
import restservices.proxies.ServiceDefinition;
import restservices.publish.RestPublishException.RestExceptionType;
import restservices.publish.RestServiceRequest.ContentType;
import restservices.util.JsonDeserializer;
import restservices.util.JsonSerializer;
import restservices.util.Utils;

import com.google.common.collect.ImmutableMap;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.core.CoreRuntimeException;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.connectionbus.requests.IRetrievalSchema;
import com.mendix.systemwideinterfaces.connectionbus.requests.ISortExpression.SortDirection;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;
import communitycommons.XPath;
import communitycommons.XPath.IBatchProcessor;

public class PublishedService {

	ServiceDefinition def;

	public PublishedService(ServiceDefinition def) {
		this.def = def;
		try {
			changeManager = new ChangeManager(this);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public String getConstraint(IContext context) {
		String constraint = def.getSourceConstraint() == null ? "" : def.getSourceConstraint();
		
		if (constraint.contains(RestServices.CURRENTUSER_TOKEN))
			constraint.replace(RestServices.CURRENTUSER_TOKEN, "'" + context.getSession().getUser().getMendixObject().getId() + "'");

		return constraint;
	}
	
	private IMetaObject sourceMetaEntity;

	private ChangeManager changeManager;
	
	public ChangeManager getChangeManager() {
		return changeManager;
	}

	public String getName() {
		return def.getName();
	}

	public String getSourceEntity() {
		return def.getSourceEntity();
	}
	
	public String getKeyAttribute() {
		return def.getSourceKeyAttribute();
	}

	public String getServiceUrl() {
		return RestServices.getServiceUrl(getName());
	}

	private IMendixObject getObjectByKey(IContext context,
			String key) throws CoreException {
		try {
			String xpath = XPath.create(context, getSourceEntity()).eq(getKeyAttribute(), key).getXPath() + this.getConstraint(context);
			List<IMendixObject> results = Core.retrieveXPathQuery(context, xpath, 1, 0, ImmutableMap.of("id", "ASC"));
			return results.size() == 0 ? null : results.get(0);
		}
		catch(CoreRuntimeException e) {
			RestServices.LOG.warn("Failed to retrieve " + getName() + "/" + key + ". Assuming that the key is invalid. 404 will be returned", e);
			return null;
		}
	}

	private ObjectState getObjectStateByKey(IContext context, String key) throws CoreException {
		return XPath.create(context, ObjectState.class)
				.eq(ObjectState.MemberNames.key,key)
				.eq(ObjectState.MemberNames.ObjectState_ServiceObjectIndex, getChangeManager().getServiceObjectIndex())
				.first();
	}	

	public void serveCount(RestServiceRequest rsr) throws CoreException, RestPublishException {
		if (!def.getEnableListing())
			throw new RestPublishException(RestExceptionType.METHOD_NOT_ALLOWED, "List is not enabled for this service");

		rsr.startDoc();
		rsr.datawriter.object();
		
		long count;
		
		if (def.getEnableChangeTracking()) {
			count = XPath.create(rsr.getContext(), ObjectState.class)
					.eq(ObjectState.MemberNames.ObjectState_ServiceObjectIndex, getChangeManager().getServiceObjectIndex())
					.eq(ObjectState.MemberNames.deleted, false)
					.count();
		} else 
			count = Core.retrieveXPathQueryAggregate(rsr.getContext(), "count(//" + getSourceEntity() + getConstraint(rsr.getContext()) + ")");
			
		
		rsr.datawriter.key("count").value(count).endObject();
		rsr.endDoc();
	}

	
	public void serveListing(RestServiceRequest rsr, boolean includeData, int offset, int limit) throws Exception {
		if (!def.getEnableListing())
			throw new RestPublishException(RestExceptionType.METHOD_NOT_ALLOWED, "List is not enabled for this service");
		if (offset >= 0 ^ limit >= 0)
			throw new RestPublishException(RestExceptionType.BAD_REQUEST, "'offset' and 'limit' parameters should both be provided and positive, or none of them");
		if (offset >= 0 && limit < 1)
			throw new RestPublishException(RestExceptionType.BAD_REQUEST, "'limit' should be positive and larget than zero");
		
		rsr.startDoc();
		rsr.datawriter.array();

		if (def.getEnableChangeTracking())
			serveListingFromIndex(rsr, includeData, offset, limit);
		else
			serveListingFromDB(rsr, includeData, offset, limit);

		rsr.datawriter.endArray();
		rsr.endDoc();
	}
	
	private void serveListingFromIndex(final RestServiceRequest rsr,
			final boolean includeData, int offset, int limit) throws CoreException {
		XPath<ObjectState> xp  = XPath.create(rsr.getContext(), ObjectState.class)
			.eq(ObjectState.MemberNames.ObjectState_ServiceObjectIndex, getChangeManager().getServiceObjectIndex())
			.eq(ObjectState.MemberNames.deleted, false)
			.eq(ObjectState.MemberNames._dirty, false)
			.addSortingAsc(ObjectState.MemberNames.key)
			.append(""); //MWE: <- does nothing, but makes sure offset & limit are supported. If this gives compile error, please upgrade community commons
			
		if (offset > -1)
			xp.offset(offset); //MWE: note that this only works on later versions of community!
		if (limit > 0)
			xp.limit(limit);
		
		xp.batch(RestServices.BATCHSIZE, new IBatchProcessor<ObjectState>() {

				@Override
				public void onItem(ObjectState item, long offset, long total)
						throws Exception {
					if (includeData)
						rsr.datawriter.value(new JSONObject(item.getjson()));
					else
						rsr.datawriter.value(getServiceUrl() + item.getkey());
				}
			});
	}

	private void serveListingFromDB(RestServiceRequest rsr, boolean includeData, int baseoffset, int limit) throws Exception {
		IRetrievalSchema schema = Core.createRetrievalSchema();
		boolean hasOffset = baseoffset >= 0;
		
		if (!includeData) {
			schema.addSortExpression(getKeyAttribute(), SortDirection.ASC);
			schema.addMetaPrimitiveName(getKeyAttribute());
		}
		
		int offset = hasOffset ? baseoffset : 0;

		String xpath = "//" + getSourceEntity() + getConstraint(rsr.getContext());
		List<IMendixObject> result = null;
		
		do {
			int amount = hasOffset && limit > 0 ? Math.min(baseoffset + limit - offset, RestServices.BATCHSIZE) : RestServices.BATCHSIZE;
			schema.setOffset(offset);
			schema.setAmount(amount);
			
			result = !includeData
					? Core.retrieveXPathQuery(rsr.getContext(), xpath, amount, offset, ImmutableMap.of(getKeyAttribute(), "ASC")) 
					: Core.retrieveXPathSchema(rsr.getContext(), xpath , schema, false);
		
			for(IMendixObject item : result) {
				if (!includeData) {
					if (!Utils.isValidKey(getKey(rsr.getContext(), item)))
						continue;
		
					rsr.datawriter.value(getObjecturl(rsr.getContext(), item));
				}
				else {
					IMendixObject view = convertSourceToView(rsr.getContext(), item);
					rsr.datawriter.value(JsonSerializer.writeMendixObjectToJson(rsr.getContext(), view));
				}
			}
			
			offset += result.size();
		}
		while(!result.isEmpty());
	}
	
	public void serveGet(RestServiceRequest rsr, String key) throws Exception {
		if (!def.getEnableGet())
			throw new RestPublishException(RestExceptionType.METHOD_NOT_ALLOWED, "GET is not enabled for this service");
		
		if(def.getEnableChangeTracking())
			serveGetFromIndex(rsr, key);
		else
			serveGetFromDB(rsr, key);
	}

	
	private void serveGetFromIndex(RestServiceRequest rsr, String key) throws Exception {
		ObjectState source = getObjectStateByKey(rsr.getContext(), key);
		if (source == null || source.getdeleted() || source.get_dirty()) 
			throw new RestPublishException(RestExceptionType.NOT_FOUND,	getName() + "/" + key);
		
		if (Utils.isNotEmpty(rsr.getETag()) && rsr.getETag().equals(source.getetag())) {
			rsr.setStatus(IMxRuntimeResponse.NOT_MODIFIED);
			rsr.close();
			return;
		}
		
		writeGetResult(rsr,key, new JSONObject(source.getjson()), source.getetag());
	}

	private void serveGetFromDB(RestServiceRequest rsr, String key) throws Exception {
		IMendixObject source = getObjectByKey(rsr.getContext(), key);
		if (source == null) 
			throw new RestPublishException(
					keyExists(rsr.getContext(), key) && !isWorldReadable()? RestExceptionType.UNAUTHORIZED : RestExceptionType.NOT_FOUND,
					getName() + "/" + key);
		
		IMendixObject view = convertSourceToView(rsr.getContext(), source);
		JSONObject result = JsonSerializer.writeMendixObjectToJson(rsr.getContext(), view);
				
		String jsonString = result.toString(4);
		String eTag = Utils.getMD5Hash(jsonString);
		
		writeGetResult(rsr, key, result, eTag);
		rsr.getContext().getSession().release(view.getId());
	}

	private void writeGetResult(RestServiceRequest rsr, String key, JSONObject result, String eTag) {
		if (eTag.equals(rsr.getETag())) {
			rsr.setStatus(IMxRuntimeResponse.NOT_MODIFIED);
			rsr.close();
			return;
		}
		
		rsr.response.setHeader(RestServices.ETAG_HEADER, eTag);
		rsr.startDoc();

		if (rsr.getContentType() == ContentType.HTML)
			rsr.write("<h1>").write(getName()).write("/").write(key).write("</h1>");

		rsr.datawriter.value(result);
		rsr.endDoc();
	}
	
	public void serveDelete(RestServiceRequest rsr, String key, String etag) throws Exception {
		if (!def.getEnableDelete())
			throw new RestPublishException(RestExceptionType.METHOD_NOT_ALLOWED, "List is not enabled for this service");

		IMendixObject source = getObjectByKey(rsr.getContext(), key);
		
		if (source == null) 
			throw new RestPublishException(keyExists(rsr.getContext(), key) && !isWorldReadable() ? RestExceptionType.UNAUTHORIZED : RestExceptionType.NOT_FOUND, getName() + "/" + key);

		verifyEtag(rsr.getContext(), key, source, etag);
		
		rsr.getContext().startTransaction();
		
		if (Utils.isNotEmpty(def.getOnDeleteMicroflow()))
			Core.execute(rsr.getContext(), def.getOnDeleteMicroflow(), source);
		else
			Core.delete(rsr.getContext(), source);
		
		rsr.setStatus(204); //no content
		rsr.close();
	}
	
	public void servePost(RestServiceRequest rsr, JSONObject data) throws Exception {
		if (!def.getEnableCreate())
			throw new RestPublishException(RestExceptionType.METHOD_NOT_ALLOWED, "Create (POST) is not enabled for this service");

		rsr.getContext().startTransaction();

		IMendixObject target = Core.instantiate(rsr.getContext(), getSourceEntity());
		
		updateObject(rsr.getContext(), target, data);
		
		Object keyValue = target.getValue(rsr.getContext(), getKeyAttribute());
		String key = keyValue == null ? null : String.valueOf(keyValue);
		
		if (!Utils.isValidKey(key))
			throw new RuntimeException("Failed to serve POST request: microflow '" + def.getOnPublishMicroflow() + "' should have created a new key");
			
		rsr.setStatus(201); //created
		rsr.response.setHeader(RestServices.ETAG_HEADER, getETag(rsr.getContext(), key, target));
		//question: write url, or write key?
		//rsr.write(getObjecturl(rsr.getContext(), target));
		rsr.write(key);
		rsr.close();
	}

	public void servePut(RestServiceRequest rsr, String key, JSONObject data, String etag) throws Exception {

		IContext context = rsr.getContext();
		context.startTransaction();
		
		IMendixObject target = getObjectByKey(context, key);
		
		if (!Utils.isValidKey(key))
			rsr.setStatus(404);
		else if (target == null) {
			if (keyExists(rsr.getContext(), key)){
				rsr.setStatus(400); //TODO: use http status, should be 401?
				rsr.close();
				return;
			}

			if (!def.getEnableCreate())
				throw new RestPublishException(RestExceptionType.METHOD_NOT_ALLOWED, "Create (PUT) is not enabled for this service");
			
			target = Core.instantiate(context, getSourceEntity());
			target.setValue(context, getKeyAttribute(), key);
			rsr.setStatus(201);
			
		}
		else {
			//already existing target
			if (!def.getEnableUpdate())
				throw new RestPublishException(RestExceptionType.METHOD_NOT_ALLOWED, "Update (PUT) is not enabled for this service");
			
			verifyEtag(rsr.getContext(), key, target, etag);
			rsr.setStatus(204);
		}
		
		updateObject(rsr.getContext(), target, data);
		rsr.response.setHeader(RestServices.ETAG_HEADER, getETag(rsr.getContext(), key, target));
		rsr.close();
	}
	
	private boolean keyExists(IContext context, String key) throws CoreException {
		return getObjectByKey(context, key) != null; //context is always sudo, so that should work fine
	}
	
	/**
	 * Returns an array with [viewArgName, viewArgType, targetArgName]. 
	 * TargetARgType is the sourceEntity of this microflow. 
	 * Or throws an exception if the microflow does not supplies these argements
	 * @return
	 */
	static String[] extractArgInfoForUpdateMicroflow(ServiceDefinition serviceDef) {
		Map<String, String> argtypes = Utils.getArgumentTypes(serviceDef.getOnUpdateMicroflow());
		
		if (argtypes.size() != 2)
			throw new RuntimeException("Expected exactly two arguments for microflow " + serviceDef.getOnUpdateMicroflow());
		
		//Determine argnames
		String viewArgName = null;
		String targetArgName = null;
		String viewArgType = null;
		for(Entry<String, String> e : argtypes.entrySet()) {
			if (e.getValue().equals(serviceDef.getSourceEntity()))
				targetArgName = e.getKey();
			else if (Core.getMetaObject(e.getValue()) != null) {
				viewArgName = e.getKey();
				viewArgType = e.getValue();
			}
		}
		
		if (targetArgName == null || viewArgName == null || Core.getMetaObject(viewArgType).isPersistable())
			throw new RuntimeException("Microflow '" + serviceDef.getOnUpdateMicroflow() + "' should have one argument of type " + serviceDef.getSourceEntity() + ", and one argument typed with an persistent entity");

		return new String[] { viewArgName, viewArgType, targetArgName };
	}

	private void updateObject(IContext context, IMendixObject target,
			JSONObject data) throws Exception, Exception {
		
		String[] argInfo = extractArgInfoForUpdateMicroflow(def);
		
		IMendixObject view = Core.instantiate(context, argInfo[1]);
		JsonDeserializer.readJsonDataIntoMendixObject(context, data, view, false);
		Core.commit(context, view);
		
		Core.execute(context, def.getOnUpdateMicroflow(), ImmutableMap.of(argInfo[2], (Object) target, argInfo[0], (Object) view));
	}

	private void verifyEtag(IContext context, String key, IMendixObject source, String etag) throws Exception {
		if (!this.def.getUseStrictVersioning())
			return;

		String currentETag = getETag(context, key, source);
		
		if (!currentETag.equals(etag))
			throw new RestPublishException(RestExceptionType.CONFLICTED, "Update conflict detected, expected change based on version '" + currentETag + "', but found '" + etag + "'");
	}

	private String getETag(IContext context, String key, IMendixObject source)
			throws CoreException, Exception, UnsupportedEncodingException {
		String currentETag;
		if (def.getEnableChangeTracking())
			currentETag = getObjectStateByKey(context, key).getetag();
		else {
			IMendixObject view = convertSourceToView(context, source);
			JSONObject result = JsonSerializer.writeMendixObjectToJson(context, view);
				
			String jsonString = result.toString(4);
			currentETag = Utils.getMD5Hash(jsonString);
		}
		return currentETag;
	}

	public IMetaObject getSourceMetaEntity() {
		if (this.sourceMetaEntity == null)
			this.sourceMetaEntity = Core.getMetaObject(getSourceEntity());
		return this.sourceMetaEntity;
	}
	
	public IMendixObject convertSourceToView(IContext context, IMendixObject source) throws CoreException {
		return (IMendixObject) Core.execute(context, def.getOnPublishMicroflow(), source);
	}

	public boolean identifierInConstraint(IContext c, IMendixIdentifier id) throws CoreException {
		if (this.getConstraint(c).isEmpty())
			return true;
		return Core.retrieveXPathQueryAggregate(c, "count(//" + getSourceEntity() + "[id='" + id.toLong() + "']" + this.getConstraint(c) + ")") == 1;
	}

	public String getObjecturl(IContext c, IMendixObject obj) {
		//Pre: inConstraint is checked!, obj is not null
		String key = getKey(c, obj);
		if (!Utils.isValidKey(key))
			throw new IllegalStateException("Invalid key for object " + obj.toString());
		return this.getServiceUrl() + Utils.urlEncode(key);
	}

	public String getKey(IContext c, IMendixObject obj) {
		return obj.getMember(c, getKeyAttribute()).parseValueToString(c);
	}

	public boolean isGetObjectEnabled() {
		return def.getEnableGet();
	}

	public boolean isWorldReadable() {
		return "*".equals(def.getAccessRole().trim());
	}

	public String getRequiredRole() {
		return def.getAccessRole().trim();
	}

	public void dispose() {
		this.changeManager.dispose();
	}

	
}

