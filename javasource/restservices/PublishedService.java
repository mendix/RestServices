package restservices;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.codec.digest.DigestUtils;
import org.codehaus.jackson.annotate.JsonProperty;
import org.json.JSONArray;
import org.json.JSONObject;

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

public class PublishedService {

	private static final long BATCHSIZE = 1000;

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
		
		IMendixObject view = convertSourceToView(rsr, results.get(0));
		JSONObject result = convertViewToJson(rsr.getContext(), view);
				
		String jsonString = result.toString(4);
		String eTag = DigestUtils.md5Hex(jsonString.getBytes(Constants.UTF8));
		
		if (eTag.equals(rsr.request.getHeader(Constants.IFNONEMATCH_HEADER))) {
			rsr.setStatus(IMxRuntimeResponse.NOT_MODIFIED);
			rsr.close();
			return;
		}
		rsr.response.setHeader(Constants.ETAG_HEADER, eTag);
		
		result.put(Constants.ID_ATTR, key);
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

	private IMendixObject convertSourceToView(RestServiceRequest rsr, IMendixObject source) throws CoreException {
		return (IMendixObject) Core.execute(rsr.getContext(), this.publishmicroflow, source);
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
		String key = obj.getMember(c, idattribute).parseValueToString(c);
		if (!RestServices.isValidKey(key))
			throw new IllegalStateException("Invalid key for object " + obj.toString());
		return this.getServiceUrl() + key;
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
}

