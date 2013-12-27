package restservices.consume;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.lang3.tuple.Pair;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonParser;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import restservices.RestServices;
import restservices.RestServices;
import restservices.proxies.Primitive;
import restservices.proxies.RestObject;
import restservices.proxies.RestPrimitiveType;
import restservices.proxies.RestReference;
import restservices.util.Utils;

import com.mendix.core.Core;
import com.mendix.core.objectmanagement.member.MendixObjectReference;
import com.mendix.core.objectmanagement.member.MendixObjectReferenceSet;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.IMendixObjectMember;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive.PrimitiveType;

public class RestConsumer {
	
	/*TODO: MultiThreadedHttpConnectionManager connectionManager = 
      		new MultiThreadedHttpConnectionManager();
      	HttpClient client = new HttpClient(connectionManager);*/
	
	static HttpClient client = new HttpClient();
	
	/**
	 * Retreives a url. Returns if statuscode is 200 OK, 304 NOT MODIFIED or 404 NOT FOUND. Exception otherwise. 
	 */
	public static Pair<Integer, String> retrieveJsonUrl(String url, String etag) throws Exception {
		if (RestServices.LOG.isDebugEnabled())
			RestServices.LOG.debug("Fetching '" + url + "' etag: " + etag + "..");
		
		GetMethod get = new GetMethod(url);
		get.setRequestHeader(RestServices.ACCEPT_HEADER, RestServices.TEXTJSON);
		
		if (etag != null && !etag.isEmpty())
			get.setRequestHeader(RestServices.IFNONEMATCH_HEADER, etag);
		
		try {
			int status = client.executeMethod(get);
			String body = get.getResponseBodyAsString();
		
			if (RestServices.LOG.isDebugEnabled())
				RestServices.LOG.debug("Fetched '" + url + "'. Status: " + status + "\n\n" + body);
			
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
	
	public static void registerCredentials(String urlBasePath, String username, String password) throws MalformedURLException
	{
		client.getParams().setAuthenticationPreemptive(true);
		Credentials defaultcreds = new UsernamePasswordCredentials(username, password);
		URL url = new URL(urlBasePath);
		client.getState().setCredentials(new AuthScope(url.getHost(), url.getPort(), AuthScope.ANY_REALM), defaultcreds);
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
	
	static void syncCollection(String collectionUrl, String onUpdateMF, String onDeleteMF) throws Exception {
		GetMethod get = new GetMethod(collectionUrl);
		get.setRequestHeader(RestServices.ACCEPT_HEADER, RestServices.TEXTJSON);
		
		int status = client.executeMethod(get);
		if (status != IMxRuntimeResponse.OK)
			throw new RuntimeException("Failed to setup stream to " + collectionUrl +  ", status: " + status);
		
		InputStream in = get.getResponseBodyAsStream();
		try {
			JSONTokener jt = new JSONTokener(in);
			JSONObject instr;
			while(null != (instr = new JSONObject(jt))) {
				IContext c = Core.createSystemContext();
				
				//TODO: store revision

				if (instr.getBoolean("deleted")) {
					Core.execute(c, onDeleteMF, instr.getString("key"));
				}
				else {
					IDataType type = Utils.getFirstArgumentType(onUpdateMF);
					if (!type.isMendixObject())
						throw new RuntimeException("First argument should be an Entity! " + onUpdateMF);

					IMendixObject target = Core.instantiate(c, type.getObjectType());
					readJsonObjectIntoMendixObject(c, instr.getJSONObject("data"), target, new ObjectCache());
					Core.commit(c, target);
					Core.execute(c, onUpdateMF, target);
				}
			}
		}
		finally {
			in.close();
		}
		
	}

	public static void readJsonObjectIntoMendixObject(IContext context, JSONObject object, IMendixObject target, ObjectCache cache) throws JSONException, Exception {
		Iterator<String> it = object.keys();
		while(it.hasNext()) {
			String attr = it.next();
			String targetattr =  null;;
			//String assocName = target.getMetaObject().getModuleName() + "." + attr;
			
			if (target.hasMember(attr)) 
				targetattr = attr;
			else if (target.hasMember(target.getMetaObject().getModuleName() + "." + attr)) 
				targetattr = target.getMetaObject().getModuleName() + "." + attr;
			if (target.hasMember("_" + attr)) //To support attributes with names which are reserved names in Mendix 
				targetattr = "_" + attr;
			else if (target.hasMember(target.getMetaObject().getModuleName() + "._" + attr)) 
				targetattr = target.getMetaObject().getModuleName() + "._" + attr;
			
			if (targetattr == null) {
				if (RestServices.LOG.isDebugEnabled())
					RestServices.LOG.debug("Skipping attribute '" + attr + "', not found in targettype: '" + target.getType() + "'");
				continue;
			}
			
			IMendixObjectMember<?> member = target.getMember(context, targetattr);
			
			if (member.isVirtual())
				continue;
			
			//Reference
			else if (member instanceof MendixObjectReference) {
				String otherSideType = target.getMetaObject().getMetaAssociationParent(targetattr).getChild().getName();
				if (!object.isNull(attr)) 
					((MendixObjectReference)member).setValue(context, readJsonValueIntoMendixObject(context, object.get(attr), otherSideType, cache));
			}
			
			//ReferenceSet
			else if (member instanceof MendixObjectReferenceSet){
				String otherSideType = target.getMetaObject().getMetaAssociationParent(targetattr).getChild().getName();
				JSONArray children = object.getJSONArray(attr);
				List<IMendixIdentifier> ids = new ArrayList<IMendixIdentifier>();
				
				for(int i = 0; i < children.length(); i++) {
					IMendixIdentifier child = readJsonValueIntoMendixObject(context, children.get(i), otherSideType, cache);
					if (child != null)
						ids.add(child);
				}
				
				((MendixObjectReferenceSet)member).setValue(context, ids);
			}
			
			//Primitive member
			else if (target.hasMember(targetattr)){
				PrimitiveType attrtype = target.getMetaObject().getMetaPrimitive(targetattr).getType();
				if (attrtype != PrimitiveType.AutoNumber)
					target.setValue(context, targetattr, jsonAttributeToPrimitive(attrtype, object, attr));
			}
		}
		Core.commit(context, target);
	}

	private static IMendixIdentifier readJsonValueIntoMendixObject(IContext context,
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
			res.setValue(context, RestServices.URL_ATTR, jsonValue);
		}
		
		else if (Core.isSubClassOf(RestObject.entityName, targetType)) {
			if (!(jsonValue instanceof String))
				throw new RuntimeException("Expected json string value to create reference to '" + targetType + "'");
			res = cache.getObject(context, (String) jsonValue, targetType);
		}
		else {
			if (!(jsonValue instanceof JSONObject))
				throw new RuntimeException("Expected json object value to create reference to '" + targetType + "'");
			readJsonObjectIntoMendixObject(context, (JSONObject) jsonValue, res, cache);
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
