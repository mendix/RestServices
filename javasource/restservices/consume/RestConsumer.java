package restservices.consume;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.DeleteMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import restservices.RestServices;
import restservices.proxies.ReferableObject;
import restservices.proxies.RequestResult;
import restservices.proxies.ResponseCode;
import restservices.util.JsonDeserializer;
import restservices.util.JsonSerializer;
import restservices.util.Utils;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import communitycommons.StringUtils;

public class RestConsumer {
	
	private static MultiThreadedHttpConnectionManager connectionManager = new MultiThreadedHttpConnectionManager();
    static	HttpClient client = new HttpClient(connectionManager);
    
	//TODO: connectionpool
	
	
	//TODO: use this class in exception handling as well
	public static class HttpResponseData{
		private int status;
		private String body;
		private String eTag;
		private String url;
		private String method;

		HttpResponseData(String method, String url, int status, String body, String eTag) {
			this.url = url;
			this.status = status;
			this.body = body;
			this.eTag = eTag;
			this.method = method;
		}
		
		public boolean isOk() {
			switch(status) {
				case 200:
				case 201:
				case 204:
				case 304:
					return true;
				default:
					return false;
			}
		}
		
		public RequestResult asRequestResult(IContext context) {
			RequestResult rr = new RequestResult(context);
			rr.setETag(getETag());
			rr.setRawResponseCode(status);
			rr.setResponseBody(getBody());
			rr.setResponseCode(asResponseCode());
			return rr;
		}
		
		public ResponseCode asResponseCode() {
			switch (status) {
				case IMxRuntimeResponse.NOT_MODIFIED: return ResponseCode.NotModified; 
				case 201: //created
				case 204: //no content
				case IMxRuntimeResponse.OK: 
					return ResponseCode.OK;
				default: 
					throw new IllegalArgumentException("Unexpected response code in " + this.toString());
			}
		}
		
		@Override public String toString() {
			return String.format("[HTTP Request: %s '%s' --> Response status: %d %s, ETag: %s, body: '%s']", 
					method, url, 
					status, HttpStatus.getStatusText(status), 
					eTag, 
					RestServices.LOG.isDebugEnabled() || status != 200  ? body : "(omitted)");
		}

		public String getETag() {
			return eTag;
		}

		public int getStatus() {
			return status;
		}

		public String getBody() {
			return body;
		}
	}
	
	private static ThreadLocal<Map<String, String>> nextHeaders = new ThreadLocal<Map<String, String>>();
	
	public static void addHeaderToNextRequest(String header, String value) {
		Map<String, String> headers = nextHeaders.get();
		
		if (headers == null) {
			headers = new HashMap<String, String>();
			nextHeaders.set(headers);
		}
		
		headers.put(header, value);
	}
	
	private static void includeHeaders(HttpMethodBase request) {
		Map<String, String> headers = nextHeaders.get();
		if (headers != null) {
			for(Entry<String,String> e : headers.entrySet())
				request.addRequestHeader(e.getKey(), e.getValue());
			headers.clear();
		}
	}

	public static HttpResponseData doRequest(final String method, String url, String etag) throws Exception {
		return doRequest(method, url, etag, (Predicate<HttpMethodBase>) null);
	}
	
	public static HttpResponseData doRequest(final String method, String url, String etag, final String body) throws Exception {
		return doRequest(method, url, etag, new Predicate<HttpMethodBase>() {

			@Override
			public boolean apply(HttpMethodBase request) {
				StringRequestEntity re;
				try {
					re = new StringRequestEntity(body, RestServices.TEXTJSON, RestServices.UTF8);
				} catch (UnsupportedEncodingException e) {
					throw new RuntimeException(e);
				}
				if ("POST".equals(method))
					((PostMethod)request).setRequestEntity(re);
				else if ("PUT".equals(method))
					((PutMethod)request).setRequestEntity(re);
				return true;
			}
			
		});
	}

	/**
	 * Retreives a url. Returns if statuscode is 200 OK, 304 NOT MODIFIED or 404 NOT FOUND. Exception otherwise. 
	 */
	public static HttpResponseData doRequest(String method, String url, String etag, Predicate<HttpMethodBase> beforeSubmitHandler) throws Exception {
		if (RestServices.LOG.isDebugEnabled())
			RestServices.LOG.debug("Fetching '" + url + "' etag: " + etag + "..");
		
		HttpMethodBase request;
		
		if ("GET".equals(method))
			request = new GetMethod(url);
		else if ("DELETE".equals(method))
			request = new DeleteMethod(url);
		else if ("POST".equals(method)) 
			request = new PostMethod(url);
		else if ("PUT".equals(method)) 
			request = new PutMethod(url);
		else 
			throw new IllegalStateException("Unsupported method: " + method);
		
		
		request.setRequestHeader(RestServices.ACCEPT_HEADER, RestServices.TEXTJSON);
		if (etag != null && !etag.isEmpty())
			request.setRequestHeader(RestServices.IFNONEMATCH_HEADER, etag);

		includeHeaders(request);
		
		if (beforeSubmitHandler != null)
			beforeSubmitHandler.apply(request);
		
		try {
			int status = client.executeMethod(request);
			Header responseEtag = request.getResponseHeader(RestServices.ETAG_HEADER);
			
			HttpResponseData response = new HttpResponseData(method, url, status, 
					request.getResponseBodyAsString(),
					responseEtag == null ? null : responseEtag.getValue());
			
			RestServices.LOG.info(response);
			
			return response;
		}
		finally {
			request.releaseConnection();
		}
	}
	
	public static void readJsonObjectStream(String url, Predicate<Object> onObject) throws Exception, IOException {
		GetMethod request = new GetMethod(url);
		request.setRequestHeader(RestServices.ACCEPT_HEADER, RestServices.TEXTJSON);
		
		includeHeaders(request);
		
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
	            		break;
	            	case ']':
	            		return;
	            	case '{':
	            		x.back();
	            		onObject.apply(new JSONObject(x));
	            		break;
	            	case '[':
	            		x.back();
	            		onObject.apply(new JSONArray(x));
	            		break;
	            	default:
	            		x.back();
	                    onObject.apply(x.nextValue());
               }
            }
		}
		finally {
			request.releaseConnection();
		}
	}
	

	static void syncCollection(String collectionUrl, String onUpdateMF, String onDeleteMF) throws Exception {
		IDataType type = Utils.getFirstArgumentType(onUpdateMF);
		if (!type.isMendixObject())
			throw new RuntimeException("First argument should be an Entity! " + onUpdateMF);

		GetMethod get = new GetMethod(collectionUrl);
		get.setRequestHeader(RestServices.ACCEPT_HEADER, RestServices.TEXTJSON);

		try {
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
						IMendixObject target = Core.instantiate(c, type.getObjectType());
						JsonDeserializer.readJsonDataIntoMendixObject(c, instr.getJSONObject("data"), target, true);
						Core.execute(c, onUpdateMF, target);
					}
				}
			}
			finally {
				in.close();
			}
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

	private static void getCollectionHelper(final IContext context, String collectionUrl, final Function<IContext, IMendixObject> objectFactory, final Function<IMendixObject, Boolean> callback) throws Exception
	{
		RestConsumer.readJsonObjectStream(collectionUrl, new Predicate<Object>() {

			@Override
			public boolean apply(Object data) {
				IMendixObject item = objectFactory.apply(context);
				try {
					JsonDeserializer.readJsonDataIntoMendixObject(context, data, item, true);
				} catch (Exception e) {
					throw new RuntimeException(e);
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
		
		//fetch
		HttpResponseData response = RestConsumer.doRequest("GET", url, eTag);
		
		if (!response.isOk()) 
			//TODO: use consume exception
			throw new Exception("Request didn't respond with status 200 or 304: " + response);

		if (response.getStatus() != IMxRuntimeResponse.NOT_MODIFIED)  
			JsonDeserializer.readJsonDataIntoMendixObject(context, new JSONTokener(response.getBody()).nextValue(), target, true);
	
		if (Core.isSubClassOf(ReferableObject.entityName, target.getType())) {
			target.setValue(context, ReferableObject.MemberNames.URL.toString(), url);
			target.setValue(context, ReferableObject.MemberNames.ETag.toString(), response.getETag());
		}
		
		return response.asRequestResult(context);
	}
	
	public static RequestResult deleteObject(IContext context, String url, String etag) throws Exception {
		HttpResponseData response = doRequest("DELETE", url, etag);
		
		
		if (!response.isOk() && response.getStatus() != HttpStatus.SC_NOT_FOUND)
			throw new Exception("Request didn't respond with status 2** or 304: " + response);
		
		RequestResult rr = response.asRequestResult(context);
		if (response.getStatus() == HttpStatus.SC_NOT_FOUND)
			rr.setResponseCode(ResponseCode.NotModified); //not found means that delete didn't modify anything
		
		return rr;
	}
	
	public static RequestResult postObject(IContext context, String url, IMendixObject source, boolean asFormData) throws Exception {
		final JSONObject data = JsonSerializer.writeMendixObjectToJson(context, source);
		
		HttpResponseData response = !asFormData 
				? doRequest("POST", url, null, data.toString(4))
				: doRequest("POST", url, null, new Predicate<HttpMethodBase>() {

					@Override
					public boolean apply(HttpMethodBase request) {
						request.setRequestHeader("Content-Type", "application/x-www-form-urlencoded"); //TODO: fix
						for(String key : JSONObject.getNames(data)) {
							Object value = data.get(key);
							if (value != null && !(value instanceof JSONObject) && !(value instanceof JSONArray))
								((PostMethod)request).addParameter(key, String.valueOf(value));
						}
						return true;
					}
				});
		
		if (!response.isOk())
			throw new Exception("Request didn't respond with status 2** or 304: " + response);

		return response.asRequestResult(context);
	}
	
	public static RequestResult putObject(IContext context, String url, IMendixObject source, String etag) throws Exception{
		JSONObject data = JsonSerializer.writeMendixObjectToJson(context, source);
		
		HttpResponseData response = doRequest("PUT", url, etag, data.toString(4));

		if (!response.isOk())
			throw new Exception("Request didn't respond with status 2** or 304: " + response);

		return response.asRequestResult(context);
	}

	public static void addCredentialsToNextRequest(String username,
			String password) {
		addHeaderToNextRequest(RestServices.HEADER_AUTHORIZATION, RestServices.BASIC_AUTHENTICATION + " " + StringUtils.base64Encode(username + ":" + password));
	}
	
}
