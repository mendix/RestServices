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
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpResponse;
import org.codehaus.jackson.annotate.JsonProperty;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import restservices.proxies.Primitive;
import restservices.proxies.RestObject;
import restservices.proxies.RestPrimitiveType;
import restservices.proxies.RestReference;

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

public class Consumer {
	
	private static HttpClient client = new HttpClient();//TODO: not thread safe!
	
	/**
	 * Retreives a url. Returns if statuscode is 200 OK, 304 NOT MODIFIED or 404 NOT FOUND. Exception otherwise. 
	 */
	public static Pair<Integer, String> retrieveJsonUrl(String url, String etag) throws Exception {
		GetMethod get = new GetMethod(url);
		get.setRequestHeader(Constants.ACCEPT_HEADER, Constants.TEXTJSON);
		
		if (etag != null && !etag.isEmpty())
			get.setRequestHeader(Constants.IFNONEMATCH_HEADER, etag);
		
		try {
			int status = client.executeMethod(get);
			String body = get.getResponseBodyAsString();
			
			if (status == IMxRuntimeResponse.NOT_MODIFIED)
				return Pair.of(status, null);
			if (status == IMxRuntimeResponse.NOT_FOUND)
				return Pair.of(status, null);
			if (status != IMxRuntimeResponse.OK)
				throw new Exception("Request didn't respond with status 200 OK: " + status + "\n\n" + body);
			
			return Pair.of(status, body);
		}
		finally {
			get.releaseConnection();
		}
	}
/*	
	public static void getAllAsync(IContext context, String serviceurl, String microflowName) throws Exception {
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
*/
	/*
	public static void getObject(IContext context, String url, IMendixObject target) throws JSONException, Exception {
		JSONObject object = new JSONObject(retrieveJsonUrl(url));
		ObjectCache cache = new ObjectCache();
		cache.putObject(url, target);
		readJsonIntoMendixObject(context, object, target, cache);
	}
	*/
	
	/*
	public static IMendixObject getObject(IContext context, String url, String targetType, ObjectCache cache) throws JSONException, Exception {
		JSONObject object = new JSONObject(retrieveJsonUrl(url));
		IMendixObject target = Core.instantiate(context, targetType);
		cache.putObject(url, target);
		readJsonIntoMendixObject(context, object, target, cache);
		return target;
	}
	*/

	static void readJsonIntoMendixObject(IContext context, JSONObject object, IMendixObject target, ObjectCache cache) throws JSONException, Exception {
		Iterator<String> it = object.keys();
		while(it.hasNext()) {
			String attr = it.next();
			String assocName = target.getMetaObject().getModuleName() + "." + attr;
			IMendixObjectMember<?> member = target.getMember(context, attr);
			
			if (target.hasMember(assocName)) {
				String otherSideType = target.getMetaObject().getMetaAssociationParent(assocName).getChild().getName();
				
				//Reference
				if (member instanceof MendixObjectReference) {
					if (!object.isNull(attr)) 
						((MendixObjectReference)member).setValue(context, readJsonIntoMendixObject(context, object.get(attr), otherSideType, cache));
				}
				//ReferenceSet
				else {
					JSONArray children = object.getJSONArray(attr);
					List<IMendixIdentifier> ids = new ArrayList<IMendixIdentifier>();
					
					for(int i = 0; i < children.length(); i++) {
						IMendixIdentifier child = readJsonIntoMendixObject(context, object.get(attr), otherSideType, cache);
						if (child != null)
							ids.add(child);
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

	private static IMendixIdentifier readJsonIntoMendixObject(IContext context,
			Object jsonValue, String targetType, ObjectCache cache) throws JSONException, Exception {
		//TODO: use cache
		IMendixObject res = Core.instantiate(context, targetType);
		
		//Primitive
		if (Core.isSubClassOf(Primitive.entityName, targetType)) {
			Primitive prim = Primitive.initialize(context, res);
			if (jsonValue instanceof String) {
				prim.setStringValue((String) jsonValue);
				prim.setPrimitiveType(RestPrimitiveType.String);
			}
			else if (jsonValue instanceof Boolean) {
				prim.setBooleanValue((Boolean) jsonValue);
				prim.setPrimitiveType(RestPrimitiveType._Boolean);
			}
			else if (jsonValue instanceof Long || jsonValue instanceof Double || jsonValue instanceof Integer || jsonValue instanceof Float) {
				prim.setPrimitiveType(RestPrimitiveType.Number);
				prim.setNumberValue(Double.parseDouble(jsonValue.toString()));
			}
			else
				throw new RuntimeException("Unable to convert value of type '" + jsonValue.getClass().getName()+ "' to rest primitive: " + jsonValue.toString());
		}
		
		//Reference
		else if (Core.isSubClassOf(RestReference.entityName, targetType)) {
			if (!(jsonValue instanceof String))
				throw new RuntimeException("Expected json string value to create reference to '" + targetType + "'");
			res.setValue(context, Constants.URL_ATTR, jsonValue);
		}
		
		else if (Core.isSubClassOf(RestObject.entityName, targetType)) {
			if (!(jsonValue instanceof String))
				throw new RuntimeException("Expected json string value to create reference to '" + targetType + "'");
			res = cache.getObject(context, (String) jsonValue, targetType);
		}
		else {
			if (!(jsonValue instanceof JSONObject))
				throw new RuntimeException("Expected json object value to create reference to '" + targetType + "'");
			readJsonIntoMendixObject(context, (JSONObject) jsonValue, res, cache);
		}
		Core.commit(context, res);
		return res.getId();
	
	}

	private static Object jsonAttributeToPrimitive(PrimitiveType type,	JSONObject object, String attr) throws Exception {
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
