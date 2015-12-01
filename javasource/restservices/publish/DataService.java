package restservices.publish;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;

import restservices.RestServices;
import restservices.proxies.ChangeItem;
import restservices.proxies.DataServiceDefinition;
import restservices.proxies.HttpMethod;
import restservices.publish.RestPublishException.RestExceptionType;
import restservices.publish.RestServiceHandler.HandlerRegistration;
import restservices.publish.RestServiceRequest.ResponseType;
import restservices.util.ICloseable;
import restservices.util.JsonDeserializer;
import restservices.util.JsonSerializer;
import restservices.util.Utils;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.connectionbus.requests.IRetrievalSchema;
import com.mendix.systemwideinterfaces.connectionbus.requests.ISortExpression.SortDirection;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;

import communitycommons.XPath;
import communitycommons.XPath.IBatchProcessor;

public class DataService {

	private static Map<Long, DataService> servicesByGuid = Maps.newHashMap();

	DataServiceDefinition def;

	public DataService(DataServiceDefinition def) {
		this.def = def;
		try {
			changeLogManager = new ChangeLogManager(this);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public String getConstraint(IContext context) {
		String constraint = def.getSourceConstraint() == null ? "" : def.getSourceConstraint();
		
		if (constraint.contains(RestServices.CURRENTUSER_TOKEN))
			constraint = constraint.replace(RestServices.CURRENTUSER_TOKEN, "'" + context.getSession().getUser().getMendixObject().getId() + "'");

		return constraint;
	}
	
	private IMetaObject sourceMetaEntity;

	private ChangeLogManager changeLogManager;

	private List<RestServiceHandler.HandlerRegistration> serviceHandlers = Lists.newArrayList();

	private ICloseable metaServiceHandler;
	
	public ChangeLogManager getChangeLogManager() {
		return changeLogManager;
	}

	public String getRelativeUrl() {
		return Utils.removeLeadingAndTrailingSlash(def.getName());
	}

	public String getSourceEntity() {
		return def.getSourceEntity();
	}
	
	public String getKeyAttribute() {
		return def.getSourceKeyAttribute();
	}

	public String getServiceUrl() {
		return RestServices.getAbsoluteUrl(getRelativeUrl());
	}

	private IMendixObject getObjectByKey(IContext context,
			String key) throws CoreException {
		try {
			String xpath = XPath.create(context, getSourceEntity()).eq(getKeyAttribute(), key).getXPath() + this.getConstraint(context);
			List<IMendixObject> results = Core.retrieveXPathQuery(context, xpath, 1, 0, ImmutableMap.of("id", "ASC"));
			return results.size() == 0 ? null : results.get(0);
		}
		catch(Throwable e) {
			if (e.getClass().getSimpleName().equals("CoreRuntimeException")) { //Somehow the exception is not properly catched. Other classloader?
				RestServices.LOGPUBLISH.warn("Failed to retrieve " + getRelativeUrl() + "/" + key + ". Assuming that the key is invalid. 404 will be returned", e);
				return null;
			}
			throw new RuntimeException(e);
		}
	}

	private ChangeItem getObjectStateByKey(IContext context, String key) throws CoreException {
		return XPath.create(context, ChangeItem.class)
				.eq(ChangeItem.MemberNames.Key,key)
				.eq(ChangeItem.MemberNames.ChangeItem_ChangeLog, getChangeLogManager().getChangeLog())
				.first();
	}	

	public void serveCount(RestServiceRequest rsr) throws CoreException, RestPublishException {
		if (!def.getEnableListing())
			throw new RestPublishException(RestExceptionType.METHOD_NOT_ALLOWED, "List is not enabled for this service");

		rsr.startDoc();
		rsr.datawriter.object();
		
		long count;
		
		if (def.getEnableChangeLog()) {
			count = XPath.create(rsr.getContext(), ChangeItem.class)
					.eq(ChangeItem.MemberNames.ChangeItem_ChangeLog, getChangeLogManager().getChangeLog())
					.eq(ChangeItem.MemberNames.IsDeleted, false)
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
		
		if (rsr.getResponseContentType() == ResponseType.HTML)
			rsr.write("<h1>" + getRelativeUrl() + "</h1>");
		
		rsr.datawriter.array();

		if (def.getEnableChangeLog())
			serveListingFromIndex(rsr, includeData, offset, limit);
		else
			serveListingFromDB(rsr, includeData, offset, limit);

		rsr.datawriter.endArray();
		rsr.endDoc();
	}
	
	private void serveListingFromIndex(final RestServiceRequest rsr,
			final boolean includeData, int offset, int limit) throws CoreException {
		XPath<ChangeItem> xp  = XPath.create(rsr.getContext(), ChangeItem.class)
			.eq(ChangeItem.MemberNames.ChangeItem_ChangeLog, getChangeLogManager().getChangeLog())
			.eq(ChangeItem.MemberNames.IsDeleted, false)
			.eq(ChangeItem.MemberNames._IsDirty, false)
			.addSortingAsc(ChangeItem.MemberNames.Key);
			
		if (offset > -1)
			xp.offset(offset); //MWE: note that the combination of offset/limit and batch only works in community commons 4.3.2 or higher!
		if (limit > 0)
			xp.limit(limit);
		
		xp.batch(RestServices.BATCHSIZE, new IBatchProcessor<ChangeItem>() {

				@Override
				public void onItem(ChangeItem item, long offset, long total)
						throws Exception {
					if (includeData)
						rsr.datawriter.value(new JSONObject(item.getJson()));
					else
						rsr.datawriter.value(getServiceUrl() + item.getKey());
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
					rsr.datawriter.value(serializeToJson(rsr.getContext(), item));
				}
			}
			
			offset += result.size();
		}
		while(!result.isEmpty());
	}
	
	public void serveGet(RestServiceRequest rsr, String key) throws Exception {
		if (!def.getEnableGet())
			throw new RestPublishException(RestExceptionType.METHOD_NOT_ALLOWED, "GET is not enabled for this service");
		
		if(def.getEnableChangeLog())
			serveGetFromIndex(rsr, key);
		else
			serveGetFromDB(rsr, key);
	}

	
	private void serveGetFromIndex(RestServiceRequest rsr, String key) throws Exception {
		ChangeItem source = getObjectStateByKey(rsr.getContext(), key);
		if (source == null || source.getIsDeleted() || source.get_IsDirty()) 
			throw new RestPublishException(RestExceptionType.NOT_FOUND,	getRelativeUrl() + "/" + key);
		
		if (Utils.isNotEmpty(rsr.getETag()) && rsr.getETag().equals(source.getEtag())) {
			rsr.setStatus(IMxRuntimeResponse.NOT_MODIFIED);
			rsr.close();
			return;
		}
		
		writeGetResult(rsr,key, new JSONObject(source.getJson()), source.getEtag());
	}

	private void serveGetFromDB(RestServiceRequest rsr, String key) throws Exception {
		IMendixObject source = getObjectByKey(rsr.getContext(), key);
		if (source == null) 
			throw new RestPublishException(
					keyExists(rsr.getContext(), key) && !isWorldReadable()? RestExceptionType.UNAUTHORIZED : RestExceptionType.NOT_FOUND,
							getRelativeUrl() + "/" + key);
		
		JSONObject result = serializeToJson(rsr.getContext(), source);
				
		String jsonString = result.toString(4);
		String eTag = Utils.getMD5Hash(jsonString);
		
		writeGetResult(rsr, key, result, eTag);
	}

	private void writeGetResult(RestServiceRequest rsr, String key, JSONObject result, String eTag) {
		if (eTag.equals(rsr.getETag())) {
			rsr.setStatus(IMxRuntimeResponse.NOT_MODIFIED);
			rsr.close();
			return;
		}
		
		rsr.response.setHeader(RestServices.HEADER_ETAG, eTag);
		rsr.startDoc();

		if (rsr.getResponseContentType() == ResponseType.HTML)
			rsr.write("<h1>").write(getRelativeUrl()).write("/").write(key).write("</h1>");

		rsr.datawriter.value(result);
		rsr.endDoc();
	}
	
	public void serveDelete(RestServiceRequest rsr, String key, String etag) throws Exception {
		if (!def.getEnableDelete())
			throw new RestPublishException(RestExceptionType.METHOD_NOT_ALLOWED, "List is not enabled for this service");

		IMendixObject source = getObjectByKey(rsr.getContext(), key);
		
		if (source == null) 
			throw new RestPublishException(keyExists(rsr.getContext(), key) && !isWorldReadable() ? RestExceptionType.UNAUTHORIZED : RestExceptionType.NOT_FOUND, getRelativeUrl() + "/" + key);

		verifyEtag(rsr.getContext(), key, source, etag);
		
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

		IMendixObject target = Core.instantiate(rsr.getContext(), getSourceEntity());
		
		updateObject(rsr.getContext(), target, data);
		
		Object keyValue = target.getValue(rsr.getContext(), getKeyAttribute());
		String key = keyValue == null ? null : String.valueOf(keyValue);
		
		if (!Utils.isValidKey(key))
			throw new RuntimeException("Failed to serve POST request: microflow '" + def.getOnPublishMicroflow() + "' should have created a new key");
			
		rsr.setStatus(201); //created
		
		String eTag = getETag(rsr.getContext(), key, target);
		if (eTag != null)
			rsr.response.setHeader(RestServices.HEADER_ETAG, eTag);

		rsr.datawriter.object().key(getKeyAttribute()).value(key).endObject();
		
		rsr.close();
	}

	public void servePut(RestServiceRequest rsr, String key, JSONObject data, String etag) throws Exception {

		IContext context = rsr.getContext();
		
		IMendixObject target = getObjectByKey(context, key);
		
		if (!Utils.isValidKey(key))
			rsr.setStatus(HttpStatus.SC_NOT_FOUND);
		else if (target == null) {
			if (keyExists(rsr.getContext(), key)){
				//key exists, but this user cannot access it. 
				rsr.setStatus(HttpStatus.SC_FORBIDDEN);
				rsr.close();
				return;
			}

			if (!def.getEnableCreate())
				throw new RestPublishException(RestExceptionType.METHOD_NOT_ALLOWED, "Create (PUT) is not enabled for this service");
			
			target = Core.instantiate(context, getSourceEntity());
			target.setValue(context, getKeyAttribute(), key);
			rsr.setStatus(HttpStatus.SC_CREATED);
		}
		else {
			//already existing target
			if (!def.getEnableUpdate())
				throw new RestPublishException(RestExceptionType.METHOD_NOT_ALLOWED, "Update (PUT) is not enabled for this service");
			
			verifyEtag(rsr.getContext(), key, target, etag);
			rsr.setStatus(204);
		}
		
		updateObject(rsr.getContext(), target, data);
		
		String eTag = getETag(rsr.getContext(), key, target);
		if (eTag != null)
			rsr.response.setHeader(RestServices.HEADER_ETAG, eTag);
		
		rsr.close();
	}
	
	private boolean keyExists(IContext context, String key) throws CoreException {
		return getObjectByKey(context.isSudo() ? context : context.createSudoClone(), key) != null; 
	}
	
	/**
	 * Returns an array with [viewArgName, viewArgType, targetArgName]. 
	 * TargetARgType is the sourceEntity of this microflow. 
	 * Or throws an exception if the microflow does not supplies these argements
	 * @return
	 */
	static String[] extractArgInfoForUpdateMicroflow(DataServiceDefinition serviceDef) {
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
		
		if (currentETag == null || !currentETag.equals(etag))
			throw new RestPublishException(RestExceptionType.CONFLICTED, "Update conflict detected, expected change based on version '" + currentETag + "', but found '" + etag + "'");
	}

	private String getETag(final IContext context, String key, IMendixObject source)
			throws CoreException, Exception, UnsupportedEncodingException {
		String currentETag = null;
		if (def.getEnableChangeLog()) {
			ChangeItem objectState = getObjectStateByKey(context, key); 
			if (objectState != null)
				currentETag = objectState.getEtag();
		}
		else {
			JSONObject result = serializeToJson(context, source);
				
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
		IMendixObject res = (IMendixObject) Core.execute(context, def.getOnPublishMicroflow(), source);
		if (res == null)
			throw new IllegalStateException("Exception during serialization: " + def.getOnPublishMicroflow() + " microflow didn't return an object");
		return res;
	}
	
	JSONObject serializeToJson(final IContext context, IMendixObject source) throws CoreException, Exception {
		IMendixObject view = convertSourceToView(context, source);
		return JsonSerializer.writeMendixObjectToJson(context, view, true);
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

	public String getRequiredRoleOrMicroflow() {
		return def.getAccessRole().trim();
	}

	public void register() {
		unregister();
		
		if (def.getEnableGet()) 
			RestServices.registerServiceByEntity(def.getSourceEntity(), this);
		
		servicesByGuid.put(def.getMendixObject().getId().toLong(), this);
		metaServiceHandler = RestServiceHandler.registerServiceHandlerMetaUrl(getRelativeUrl());
		
		registerHandlers();
	}

	public void unregister() {
		this.changeLogManager.dispose();

		for(HandlerRegistration handler : serviceHandlers) {
			handler.close();
		}
		serviceHandlers.clear();

		if (def != null) {
			servicesByGuid.remove(def.getMendixObject().getId().toLong());
			RestServices.unregisterServiceByEntity(def.getSourceEntity(), this);
		}

		if (metaServiceHandler != null) {
			metaServiceHandler.close();
		}
	}

	private void registerHandlers() {
		String base = Utils.appendSlashToUrl(getRelativeUrl());
		String baseWithKey = base + "{" + getKeyAttribute() + "}";

		serviceHandlers.add(RestServiceHandler.registerServiceHandler(HttpMethod.GET, base, getRequiredRoleOrMicroflow(), new IRestServiceHandler() {

			@Override
			public void execute(RestServiceRequest rsr,
					Map<String, String> params) throws Exception {
				if (rsr.request.getParameter(RestServices.PARAM_ABOUT) != null)
					new ServiceDescriber(rsr, def).serveServiceDescription();
				else if (rsr.request.getParameter(RestServices.PARAM_COUNT) != null)
					serveCount(rsr);
				else
					serveListing(rsr,
							"true".equals(rsr.getRequestParameter(RestServices.PARAM_DATA,"false")),
							Integer.valueOf(rsr.getRequestParameter(RestServices.PARAM_OFFSET, "-1")),
							Integer.valueOf(rsr.getRequestParameter(RestServices.PARAM_LIMIT, "-1")));
			}
		}));
		
		// Create object
		serviceHandlers.add(RestServiceHandler.registerServiceHandler(HttpMethod.POST, base, getRequiredRoleOrMicroflow(), new IRestServiceHandler() {

			@Override
			public void execute(RestServiceRequest rsr,
					Map<String, String> params) throws Exception {
				JSONObject data;
				if (RestServices.CONTENTTYPE_FORMENCODED.equalsIgnoreCase(rsr.request.getContentType())) {
					data = new JSONObject();
					RestServiceHandler.paramMapToJsonObject(params, data);
				}
				else {
					String body = IOUtils.toString(rsr.request.getInputStream());
					data = new JSONObject(body);
				}
				servePost(rsr, data);
			}
		}));
		
		// Get Object
		serviceHandlers.add(RestServiceHandler.registerServiceHandler(HttpMethod.GET, baseWithKey, getRequiredRoleOrMicroflow(), new IRestServiceHandler() {

			@Override
			public void execute(RestServiceRequest rsr,
					Map<String, String> params) throws Exception {
				serveGet(rsr, params.get(getKeyAttribute()));
			}
		}));
		
		// Update Object
		serviceHandlers.add(RestServiceHandler.registerServiceHandler(HttpMethod.PUT, baseWithKey, getRequiredRoleOrMicroflow(), new IRestServiceHandler() {

			@Override
			public void execute(RestServiceRequest rsr,
					Map<String, String> params) throws Exception {
				String body = IOUtils.toString(rsr.request.getInputStream());
				servePut(rsr, params.get(getKeyAttribute()), new JSONObject(body), rsr.getETag());
			}
		}));
		
		// Delete Object
		serviceHandlers.add(RestServiceHandler.registerServiceHandler(HttpMethod.DELETE, baseWithKey, getRequiredRoleOrMicroflow(), new IRestServiceHandler() {

			@Override
			public void execute(RestServiceRequest rsr,
					Map<String, String> params) throws Exception {
				serveDelete(rsr, params.get(getKeyAttribute()), rsr.getETag());				
			}
		}));
		
		// Changes list
		serviceHandlers.add(RestServiceHandler.registerServiceHandler(HttpMethod.GET, base + "changes/list", getRequiredRoleOrMicroflow(), new IRestServiceHandler() {

			@Override
			public void execute(RestServiceRequest rsr,
					Map<String, String> params) throws Exception {
				getChangeLogManager().serveChanges(rsr, false);
			}
		}));

		// Changes feed
		serviceHandlers.add(RestServiceHandler.registerServiceHandler(HttpMethod.GET, base + "changes/feed", getRequiredRoleOrMicroflow(), new IRestServiceHandler() {

			@Override
			public void execute(RestServiceRequest rsr,
					Map<String, String> params) throws Exception {
				getChangeLogManager().serveChanges(rsr, true);				
			}
		}));
	}

	public static DataService getServiceByDefinition(DataServiceDefinition def) {
		Preconditions.checkNotNull(def);
		return servicesByGuid.get(def.getMendixObject().getId().toLong());
	}
}

