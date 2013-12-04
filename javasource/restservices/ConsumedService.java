package restservices;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.http.HttpResponse;
import org.codehaus.jackson.annotate.JsonProperty;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.common.collect.ImmutableMap;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.core.objectmanagement.member.MendixObjectReference;
import com.mendix.core.objectmanagement.member.MendixObjectReferenceSet;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.IMendixObjectMember;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive.PrimitiveType;

public class ConsumedService {
	@JsonProperty
	private String serviceurl;
	
	HttpClient client;
	
	public ConsumedService() {
		client = new HttpClient();//TODO: not thread safe!
	}
	
	String retrieveJsonUrl(String url) throws Exception {
		GetMethod get = new GetMethod(url);
		get.setRequestHeader(Constants.ACCEPT_HEADER, Constants.TEXTJSON);
		
		try {
			int status = client.executeMethod(get);
			String body = get.getResponseBodyAsString();
			
			if (status != IMxRuntimeResponse.OK)
				throw new Exception("Request didn't respond with status 200 OK: " + status + "\n\n" + body);
			return body;
		}
		finally {
			get.releaseConnection();
		}
	}
	
	public void getAllAsync(IContext context, String microflowName) throws Exception {
		Map<String, IDataType> params = Core.getInputParameters(microflowName);
		if (params == null)
			throw new Exception("Unknown microflow: " + microflowName);
		if (params.size() != 1)
			throw new Exception("Microflow '" + microflowName + "' should have exactly one argument");
		
		String entityType = params.entrySet().iterator().next().getValue().getObjectType();
		String paramName = params.entrySet().iterator().next().getKey();
		
		if (entityType == null || Core.getMetaObject(entityType).isPersistable())
			throw new Exception("First argument of microflow '" + microflowName + "' should be a transient entity");
		
		JSONArray ids = new JSONArray(retrieveJsonUrl(serviceurl)); 
		for(int i = 0; i < ids.length(); i++) {
			IMendixObject view = getObject(context, ids.getString(i), entityType, null); //No object storing between requests, we expect many objects!
			Core.execute(context, microflowName, ImmutableMap.of(paramName, (Object) view));
			context.getSession().release(view.getId());
		}
	}
	
	public void getObject(IContext context, String url, IMendixObject target) throws JSONException, Exception {
		JSONObject object = new JSONObject(retrieveJsonUrl(url));
		ObjectCache cache = new ObjectCache();
		cache.putObject(url, target);
		readJsonIntoMendixObject(context, object, target, cache);
	}
	
	public IMendixObject getObject(IContext context, String url, String targetType, ObjectCache cache) throws JSONException, Exception {
		JSONObject object = new JSONObject(retrieveJsonUrl(url));
		IMendixObject target = Core.instantiate(context, targetType);
		cache.putObject(url, target);
		readJsonIntoMendixObject(context, object, target, cache);
		return target;
	}


	private void readJsonIntoMendixObject(IContext context, JSONObject object, IMendixObject target, ObjectCache cache) throws JSONException, Exception {
		Iterator<String> it = object.keys();
		while(it.hasNext()) {
			String attr = it.next();
			String assocName = target.getMetaObject().getModuleName() + "." + attr;
			IMendixObjectMember<?> member = target.getMember(context, attr);
			
			if (target.hasMember(assocName)) {
				String otherSideType = target.getMetaObject().getMetaAssociationParent(assocName).getChild().getName();
				
				//Reference
				if (member instanceof MendixObjectReference) {
					if (!object.isNull(attr)) {
						IMendixObject child = cache.getObject(context, object.getString(attr), otherSideType);
						((MendixObjectReference)member).setValue(context, child.getId());
					}
				}
				//ReferenceSet
				else {
					JSONArray children = object.getJSONArray(attr);
					List<IMendixIdentifier> ids = new ArrayList<IMendixIdentifier>();
					
					for(int i = 0; i < children.length(); i++) {
						IMendixObject child = cache.getObject(context, children.getString(i), otherSideType);
						if (child != null)
							ids.add(child.getId());
					}
					
					((MendixObjectReferenceSet)member).setValue(context, ids);
				}
			}
			
			//Primitive member
			else if (target.hasMember(attr)){
				PrimitiveType attrtype = target.getMetaObject().getMetaPrimitive(attr).getType();
				if (attrtype != PrimitiveType.AutoNumber)
					target.setValue(context, attr, jsonAttributeToPrimitive(attrtype, object, attr));
			}
			else if (RestServices.LOG.isDebugEnabled())
				RestServices.LOG.debug("Skipping attribute '" + attr + "', not found in targettype: '" + target.getType() + "'");
		}
		Core.commit(context, target);
	}

	private Object jsonAttributeToPrimitive(PrimitiveType type,	JSONObject object, String attr) throws Exception {
		switch(type) {
		case Currency:
		case Float:
			return object.getDouble(attr);
		case Boolean:
			return object.getBoolean(attr);
		case DateTime: 
			if (object.isNull(attr))
				return null;
			return new Date(object.getLong(attr));
		case Enum:
		case HashString:
		case String:
			return object.getString(attr);
		case AutoNumber:
		case Integer:
		case Long:
			return object.getLong(attr);
		case Binary:
		default:
			//TODO: use RestException everywhere
			throw new Exception("Unsupported attribute type '" + type + "' in attribute '" + attr + "'");
		}	
	}
}
