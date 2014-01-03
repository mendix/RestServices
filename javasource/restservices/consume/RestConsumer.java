package restservices.consume;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;

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
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import restservices.RestServices;
import restservices.proxies.GetResult;
import restservices.proxies.RequestResult;
import restservices.publish.JsonSerializer;
import restservices.util.Utils;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
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
	
	public static void readJsonObjectStream(String url, Predicate<Object> onObject) throws Exception, IOException {
		GetMethod request = new GetMethod(url);
		request.setRequestHeader(RestServices.ACCEPT_HEADER, RestServices.TEXTJSON);
		try {
			int status = client.executeMethod(request);
		
			if (status != 200)
				throw new IllegalStateException("Expected status 200, found " + status + " on " + url);
			
			JSONTokener x = new JSONTokener(request.getResponseBodyAsStream());
			//Based on: https://github.com/douglascrockford/JSON-java/blob/master/JSONArray.java
			if (x.nextClean() != '[') 
	            throw x.syntaxError("A JSONArray text must start with '['");
            for (;;) {
            	switch(x.nextClean()) {
	            	case ',':
	            		continue;
	            	case ']':
	            		return;
	            	default:
	                    onObject.apply(x.nextValue());
               }
            }
		}
		finally {
			request.releaseConnection();
		}
	}
	

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

	private static void getCollectionHelper(final IContext context, String collectionUrl, final Function<IContext, IMendixObject> objectFactory, final Function<IMendixObject, Boolean> callback) throws Exception
	{
		RestConsumer.readJsonObjectStream(collectionUrl, new Function<JSONObject, Boolean>() {

			@Override
			public Boolean apply(JSONObject data) {
				IMendixObject item = objectFactory.apply(context);
				try {
					JsonDeserializer.readJsonObjectIntoMendixObject(context, data, item, new ObjectCache(true));
				} catch (Exception e) {
					throw new RuntimeException();
				}
				callback.apply(item);
				return true;
			}
		});
	}
	
	public static void getCollection(final IContext context, String collectionUrl, final List<IMendixObject> resultList, final IMendixObject firstResult) throws Exception {
		if (resultList.size() > 0)
			throw new RuntimeException("Expected stub collection to have size 0");
		
		getCollectionHelper(context, collectionUrl, new Function<IContext, IMendixObject>() {
			
			@Override
			public IMendixObject apply(IContext arg0) {
				if (resultList.size() == 0)
					return firstResult;
				return Core.instantiate(context, firstResult.getType());
			}
		}, new Function<IMendixObject, Boolean>() {

			@Override
			public Boolean apply(IMendixObject obj) {
				resultList.add(obj);
				return true;
			}
		
		});
	}
	
	public static void getCollectionAsync(String collectionUrl, final String callbackMicroflow) throws Exception {
		//args check
		Map<String, String> argTypes = Utils.getArgumentTypes(callbackMicroflow);
		if (argTypes.size() != 1)
			throw new IllegalArgumentException("Microflow '" + callbackMicroflow + "' should have exactly one argument");
		final String entityType = argTypes.values().iterator().next();

		if (Core.getMetaObject(entityType) == null)
			throw new IllegalArgumentException("Microflow '" + callbackMicroflow + "' should expect an entity as argument");
		
		final IContext context = Core.createSystemContext();
		
		getCollectionHelper(context, collectionUrl, new Function<IContext, IMendixObject>() {
			@Override
			public IMendixObject apply(IContext arg0) {
				return Core.instantiate(arg0, entityType);
			}
		}, new Function<IMendixObject, Boolean>() {

			@Override
			public Boolean apply(IMendixObject item) {
				try {
					Core.execute(context, callbackMicroflow, item);
				} catch (CoreException e) {
					throw new RuntimeException(e);
				}
				return true;
			}
		});
	}
	
	public static RequestResult getObject(IContext context, String url, String eTag, IMendixObject target) throws Exception {
		//pre
		if (context == null)
			throw new IllegalArgumentException("Context should not be null");
		if (target == null)
			throw new IllegalArgumentException("Target should not be null");
		
		if (!Utils.isUrl(url))
			throw new IllegalArgumentException("Target should have a valid URL attribute");
		
		GetResult gr = new GetResult(context);
		
		//fetch
		Pair<Integer, String> result = RestConsumer.doRequest("GET", url, eTag, null);
		
		if (result.getLeft() == IMxRuntimeResponse.NOT_MODIFIED) {
			gr.setETag(eTag);
			gr.setResult(RequestResult.NotModified);
		}
		else if (result.getLeft() != IMxRuntimeResponse.OK) 
			throw new Exception("Failed to fetch '" + url + "', status: " + result.getLeft() + "\n\n" + result.getRight());
		
		//parse
		JsonDeserializer.readJsonDataIntoMendixObject(context, new JSONTokener(result.getRight()).nextValue(), target, true);
	
		return gr;
	}
	
	public static RequestResult deleteObject(String url, String etag) throws Exception {
		//TODO: make not-found result in not-modified, since that is conceptually the same result
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
