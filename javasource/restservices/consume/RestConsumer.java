package restservices.consume;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.DeleteMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.lang3.tuple.Pair;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import restservices.RestServices;
import restservices.proxies.RequestResult;
import restservices.proxies.RestObject;
import restservices.publish.JsonSerializer;
import restservices.util.Utils;

import com.mendix.core.Core;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.IMendixObject;

public class RestConsumer {
	
	/*TODO: MultiThreadedHttpConnectionManager connectionManager = 
      		new MultiThreadedHttpConnectionManager();
      	HttpClient client = new HttpClient(connectionManager);*/
	
	static HttpClient client = new HttpClient();
	
	/**
	 * Retreives a url. Returns if statuscode is 200 OK, 304 NOT MODIFIED or 404 NOT FOUND. Exception otherwise. 
	 */
	public static Pair<Integer, String> doRequest(String method, String url, String etag, String body) throws Exception {
		if (RestServices.LOG.isDebugEnabled())
			RestServices.LOG.debug("Fetching '" + url + "' etag: " + etag + "..");
		
		HttpMethodBase request;
		
		if ("GET".equals(method))
			request = new GetMethod(url);
		else if ("DELETE".equals(method))
			request = new DeleteMethod(url);
		else if ("POST".equals(method)) {
			request = new PostMethod(url);
			((PostMethod)request).setRequestBody(body);
		}
		else if ("PUT".equals(method)) {
			request = new PutMethod(url);
			((PostMethod)request).setRequestBody(body);
		}
		else 
			throw new IllegalStateException("Unsupported method: " + method);
		
		
		request.setRequestHeader(RestServices.ACCEPT_HEADER, RestServices.TEXTJSON);
		
		if (etag != null && !etag.isEmpty())
			request.setRequestHeader(RestServices.IFNONEMATCH_HEADER, etag);
		
		try {
			int status = client.executeMethod(request);
			String respbody = request.getResponseBodyAsString();
		
			if (RestServices.LOG.isDebugEnabled())
				RestServices.LOG.debug("Fetched '" + url + "'. Status: " + status + "\n\n" + respbody);
			
			if (status == IMxRuntimeResponse.NOT_MODIFIED)
				return Pair.of(status, null);
			if (status == IMxRuntimeResponse.NOT_FOUND)
				return Pair.of(status, null);
			if (status != IMxRuntimeResponse.OK)
				throw new Exception("Request didn't respond with status 200 OK: " + status + "\n\n" + body);
			
			return Pair.of(status, respbody);
		}
		finally {
			request.releaseConnection();
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
			while(true) {
				instr = new JSONObject(jt);
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
					JsonDeserializer.readJsonObjectIntoMendixObject(c, instr.getJSONObject("data"), target, new ObjectCache(true));
					Core.commit(c, target);
					Core.execute(c, onUpdateMF, target);
				}
			}
		}
		finally {
			in.close();
		}
		
	}

	public static void registerCredentials(String urlBasePath, String username, String password) throws MalformedURLException
	{
		client.getParams().setAuthenticationPreemptive(true);
		Credentials defaultcreds = new UsernamePasswordCredentials(username, password);
		URL url = new URL(urlBasePath);
		client.getState().setCredentials(new AuthScope(url.getHost(), url.getPort(), AuthScope.ANY_REALM), defaultcreds);
	}

	public static void getCollection(IContext context, String collectionUrl, List<IMendixObject> resultList, IMendixObject firstResult) throws Exception
	{
		if (resultList.size() > 0)
			throw new RuntimeException("Expected stub collection to have size 0");
		
		Pair<Integer, String> result = RestConsumer.doRequest("GET", collectionUrl, null, null);
		if (result.getLeft() != IMxRuntimeResponse.OK)
			throw new RuntimeException("Expected status OK on " + collectionUrl);
		
		ObjectCache cache = new ObjectCache(true);
		JSONArray ar = new JSONArray(result.getRight()); //TODO: stream would be faster!
		
		for(int i = 0; i < ar.length(); i++) {
			IMendixObject item;
			
			if (i == 0)
				item = firstResult;
			else
				item = Core.instantiate(context, firstResult.getType());
			
			JsonDeserializer.readJsonObjectIntoMendixObject(context, ar.getJSONObject(i), item, cache);
			resultList.add(item);
		}
	}

	public static RequestResult getObject(IContext context, String url, IMendixObject target) throws Exception {
		return getObject(context, url, target, new ObjectCache(true));
	}

	public static RequestResult getObject(IContext context, String url, IMendixObject target, ObjectCache cache) throws Exception {
		//pre
		if (context == null)
			throw new IllegalArgumentException("Context should not be null");
		if (target == null)
			throw new IllegalArgumentException("Target should not be null");
		
		if (!Utils.isUrl(url))
			throw new IllegalArgumentException("Target should have a valid URL attribute");
		
		String etag = null;
		//analyze input
		if (Core.isSubClassOf(RestObject.entityName, target.getType())) {
			String key = Utils.getKeyFromUrl(url);
			target.setValue(context, RestServices.ID_ATTR, key);
			etag = target.getValue(context, RestServices.ETAG_ATTR);
		}
	
		//fetch
		Pair<Integer, String> result = RestConsumer.doRequest("GET", url, etag, null);
		
		if (result.getLeft() == IMxRuntimeResponse.NOT_MODIFIED)
			return RequestResult.NotModified;
		else if (result.getLeft() == IMxRuntimeResponse.NOT_FOUND)
			return RequestResult.NotFound;
		
		//parse
		cache.putObject(url, target);
		JsonDeserializer.readJsonObjectIntoMendixObject(context, new JSONObject(result.getRight()), target, cache);
	
		return RequestResult.OK;
	}
	
	public static RequestResult deleteObject(String url, String etag) throws Exception {
		return parseResponseCode(doRequest("DELETE", url, etag, null).getLeft());
	}
	
	public static String postObject(IContext context, String url, IMendixObject source) throws Exception {
		JSONObject data = JsonSerializer.convertMendixObjectToJson(context, source);
		
		Pair<Integer, String> result = doRequest("POST", url, null, data.toString(4));
		if (parseResponseCode(result.getLeft()) == RequestResult.OK)
			return result.getRight();
		throw new RuntimeException("Unexpected response code " + result.getLeft() + " on " + url);
	}
	
	public static RequestResult putObject(IContext context, String url, IMendixObject source, String etag) throws Exception{
		JSONObject data = JsonSerializer.convertMendixObjectToJson(context, source);
		
		Pair<Integer, String> result = doRequest("PUT", url, etag, data.toString(4));
		return parseResponseCode(result.getLeft());
	}

	public static RequestResult parseResponseCode(int respCode) {
		switch (respCode) {
		case IMxRuntimeResponse.NOT_MODIFIED: return RequestResult.NotModified; 
		case IMxRuntimeResponse.NOT_FOUND: return RequestResult.NotFound; 
		case 201: //created
		case 204: //no content
		case IMxRuntimeResponse.OK: 
			return RequestResult.OK;
		default: throw new IllegalArgumentException("Non default response: " + respCode);
		}
	}
	
}
