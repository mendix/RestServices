package restservices.publish;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.json.JSONObject;

import restservices.RestServices;
import restservices.proxies.ServiceDefinition;
import restservices.util.JsonDeserializer;
import restservices.util.JsonSerializer;
import restservices.util.Utils;

import com.google.common.collect.ImmutableMap;
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

public class PublishedService {

	private ServiceDefinition def;

	public PublishedService(ServiceDefinition def) {
		this.def = def;
	}

	public String getConstraint() {
		return def.getSourceConstraint() == null ? "" : def.getSourceConstraint();
		//TODO: replace current user
	}
	
	private IMetaObject sourceMetaEntity;

	private ChangeManager changeManager = new ChangeManager(this);
	
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

	//TODO: add include data parameter
	public void serveListing(RestServiceRequest rsr, boolean includeData) throws Exception {
		IRetrievalSchema schema = Core.createRetrievalSchema();
		schema.addSortExpression(getKeyAttribute(), SortDirection.ASC);
		schema.addMetaPrimitiveName(getKeyAttribute());
		schema.setAmount(RestServices.BATCHSIZE);

		switch(rsr.getContentType()) {
		case HTML:
			rsr.startHTMLDoc();
			break;
		case XML:
			rsr.startXMLDoc();
			rsr.write("<items>");
			break;
		case JSON:
			break;
		}
		
		//TODO: optimize if change tracking is enabled
		long offset = 0;
		List<IMendixObject> result;
		
		rsr.datawriter.array();
		do {
			schema.setOffset(offset);
			result = Core.retrieveXPathSchema(rsr.getContext(), "//" + getSourceEntity() + getConstraint(), schema, false);
		
			for(IMendixObject item : result) {
				if (!includeData) {
					String key = item.getMember(rsr.getContext(), getKeyAttribute()).parseValueToString(rsr.getContext());
					if (!Utils.isValidKey(key))
						continue;
		
					String url = this.getServiceUrl() + key; //TODO: url param encode key?
					rsr.datawriter.value(url);
				}
				else {
					IMendixObject view = convertSourceToView(rsr.getContext(), item);
					rsr.datawriter.value(JsonSerializer.writeMendixObjectToJson(rsr.getContext(), view));
				}
			}
			
			offset += RestServices.BATCHSIZE;
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
			break;
		}
		
		rsr.close();
	}

	public String getServiceUrl() {
		return Core.getConfiguration().getApplicationRootUrl() + "rest/" + getName() + "/";
	}

	public void serveGet(RestServiceRequest rsr, String key) throws Exception {
		IMendixObject source = getObjectByKey(rsr.getContext(), key);
		if (source == null) {
			rsr.setStatus(keyExists(key)? 401 : IMxRuntimeResponse.NOT_FOUND);
			rsr.close();
			return;
		}
		
		//TODO: optimize if change tracking is enabled
		IMendixObject view = convertSourceToView(rsr.getContext(), source);
		JSONObject result = JsonSerializer.writeMendixObjectToJson(rsr.getContext(), view);
				
		String jsonString = result.toString(4);
		String eTag = Utils.getMD5Hash(jsonString);
		
		if (eTag.equals(rsr.getETag())) {
			rsr.setStatus(IMxRuntimeResponse.NOT_MODIFIED);
			rsr.close();
			return;
		}
		rsr.response.setHeader(RestServices.ETAG_HEADER, eTag);
		
		switch(rsr.getContentType()) {
		case JSON:
			rsr.write(jsonString);
			break;
		case HTML:
			rsr.startHTMLDoc();
			rsr.write("<h1>").write(getName()).write("/").write(key).write("</h1>");
			rsr.datawriter.value(result);
			rsr.endHTMLDoc();
			break;
		case XML:
			rsr.startXMLDoc(); 
			rsr.write("<" + getName() + ">");
			rsr.datawriter.value(result);
			rsr.write("</" + getName() + ">");
			break;
		}
		
		rsr.close();
		rsr.getContext().getSession().release(view.getId());
	}

	private IMendixObject getObjectByKey(IContext context,
			String key) throws CoreException {
		String xpath = XPath.create(context, getSourceEntity()).eq(getKeyAttribute(), key).getXPath() + this.getConstraint();
		List<IMendixObject> results = Core.retrieveXPathQuery(context, xpath, 1, 0, ImmutableMap.of("id", "ASC"));
		return results.size() == 0 ? null : results.get(0);
	}
	
	public void serveDelete(RestServiceRequest rsr, String key, String etag) throws Exception {
		//TODO: check delete api enabled?
		
		IMendixObject source = getObjectByKey(rsr.getContext(), key);
		
		if (source == null) {
			rsr.setStatus(keyExists(key) ? IMxRuntimeResponse.UNAUTHORIZED : IMxRuntimeResponse.NOT_FOUND);
			return;
		}

		verifyEtag(rsr.getContext(), source, etag);
		
		if (Utils.isNotEmpty(def.getOnDeleteMicroflow()))
			Core.execute(rsr.getContext(), def.getOnDeleteMicroflow(), source);
		else
			Core.delete(rsr.getContext(), source);
		
		rsr.setStatus(204); //no content
	}
	
	public void servePost(RestServiceRequest rsr, JSONObject data) throws Exception {
		//TODO: if enabled
		IMendixObject target = Core.instantiate(rsr.getContext(), getSourceEntity());
		
		updateObject(rsr.getContext(), target, data);
		
		String key = (String) target.getValue(rsr.getContext(), getKeyAttribute());
		if (!Utils.isValidKey(key))
			throw new RuntimeException("Failed to serve POST request: microflow '" + def.getOnPublishMicroflow() + "' should have created a new key");
			
		rsr.setStatus(201); //created
		rsr.write(getObjecturl(rsr.getContext(), target));
		
		rsr.close();
	}

	public void servePut(RestServiceRequest rsr, String key, JSONObject data, String etag) throws Exception {
		IContext context = rsr.getContext();
		IMendixObject target = getObjectByKey(context, key);
		
		if (!Utils.isValidKey(key))
			rsr.setStatus(404);
		else if (target == null) {
			if (keyExists(key)){
				rsr.setStatus(400);
				rsr.close();
				return;
			}
			
			target = Core.instantiate(context, getSourceEntity());
			target.setValue(context, getKeyAttribute(), key);
			rsr.setStatus(201);
			
		}
		else {
			//already existing target
			verifyEtag(rsr.getContext(), target, etag);
			rsr.setStatus(204);
		}
		
		updateObject(rsr.getContext(), target, data);
		rsr.close();
	}
	
	private boolean keyExists(String key) throws CoreException {
		return getObjectByKey(Core.createSystemContext(), key) != null;
	}

	private void updateObject(IContext context, IMendixObject target,
			JSONObject data) throws Exception, Exception {
		Map<String, String> argtypes = Utils.getArgumentTypes(def.getOnUpdateMicroflow());
		
		if (argtypes.size() != 2)
			throw new RuntimeException("Expected exactly two arguments for microflow " + def.getOnUpdateMicroflow());
		
		//Determine argnames
		String viewArgName = null;
		String targetArgName = null;
		String viewArgType = null;
		for(Entry<String, String> e : argtypes.entrySet()) {
			if (e.getValue().equals(target.getType()))
				targetArgName = e.getKey();
			else if (Core.getMetaObject(e.getValue()) != null) {
				viewArgName = e.getKey();
				viewArgType = e.getValue();
			}
		}
		
		if (targetArgName == null || viewArgName == null || Core.getMetaObject(viewArgType).isPersistable())
			throw new RuntimeException("Microflow '" + def.getOnUpdateMicroflow() + "' should have one argument of type " + target.getType() + ", and one argument typed with an persistent entity");
		
		IMendixObject view = Core.instantiate(context, viewArgType);
		JsonDeserializer.readJsonDataIntoMendixObject(context, data, view, false);
		Core.commit(context, view);
		
		Core.execute(context, def.getOnUpdateMicroflow(), ImmutableMap.of(targetArgName, (Object) target, viewArgName, (Object) view));
	}

	private void verifyEtag(IContext context, IMendixObject source, String etag) throws Exception {
		if (!this.def.getUseStrictVersioning())
			return;
		
		//TODO: optimize if change tracking enabled
		
		IMendixObject view = convertSourceToView(context, source);
		JSONObject result = JsonSerializer.writeMendixObjectToJson(context, view);
				
		String jsonString = result.toString(4);
		String eTag = Utils.getMD5Hash(jsonString);
		if (!eTag.equals(etag))
			throw new RuntimeException("Update conflict detected, expected change based on version '" + eTag + "', but found '" + etag + "'");
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
		if (this.getConstraint().isEmpty())
			return true;
		return Core.retrieveXPathQueryAggregate(c, "count(//" + getSourceEntity() + "[id='" + id.toLong() + "']" + this.getConstraint()) == 1;
	}

	public String getObjecturl(IContext c, IMendixObject obj) {
		//Pre: inConstraint is checked!, obj is not null
		String key = getKey(c, obj);
		if (!Utils.isValidKey(key))
			throw new IllegalStateException("Invalid key for object " + obj.toString());
		return this.getServiceUrl() + key;
	}

	public String getKey(IContext c, IMendixObject obj) {
		return obj.getMember(c, getKeyAttribute()).parseValueToString(c);
	}

	//TODO: replace with something recursive
	public Map<String, String> getPublishedMembers() {
		Map<String, String> res = new HashMap<String, String>();
/* TODO: determine published meta entity
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
*/
		return res;
	}
	
	public void serveServiceDescription(RestServiceRequest rsr) {
		rsr.datawriter.object()
			.key("name").value(getName())
			.key("url").value(getServiceUrl())
			//TODO: export description
			.key("attributes").object();
		
		for(Entry<String, String> e : getPublishedMembers().entrySet()) 
			rsr.datawriter.key(e.getKey()).value(e.getValue());
		
		rsr.datawriter.endObject().endObject();
	}

	void debug(String msg) {
		if (RestServices.LOG.isDebugEnabled())
			RestServices.LOG.debug(msg);
	}

	public boolean isGetObjectEnabled() {
		return def.getEnableGet();
	}

}

