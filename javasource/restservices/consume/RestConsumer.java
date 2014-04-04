package restservices.consume;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.DeleteMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.InputStreamRequestEntity;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.httpclient.methods.multipart.ByteArrayPartSource;
import org.apache.commons.httpclient.methods.multipart.FilePart;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import restservices.RestServices;
import restservices.proxies.HttpMethod;
import restservices.proxies.ReferableObject;
import restservices.proxies.RequestResult;
import restservices.proxies.ResponseCode;
import restservices.util.JsonDeserializer;
import restservices.util.JsonSerializer;
import restservices.util.Utils;
import system.proxies.FileDocument;

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
    
	public static class HttpResponseData{
		private int status;
		private String body = null;
		private String eTag;
		private String url;
		private String method;

		HttpResponseData(String method, String url, int status, String eTag) {
			this.url = url;
			this.status = status;
			this.eTag = eTag;
			this.method = method;
		}
		
		public void setBody(String body) {
			this.body = body;
		}
		
		public boolean isOk() {
			return status == HttpStatus.SC_NOT_MODIFIED || (status >= 200 && status < 300);
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
					RestServices.LOGCONSUME.isDebugEnabled() || status != 200  ? body : "(omitted)");
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
	
	static ThreadLocal<Map<String, String>> nextHeaders = new ThreadLocal<Map<String, String>>();
	
	public static void addHeaderToNextRequest(String header, String value) {
		Map<String, String> headers = nextHeaders.get();
		
		if (headers == null) {
			headers = new HashMap<String, String>();
			nextHeaders.set(headers);
		}
		
		headers.put(header, value);
	}
	
	static void includeHeaders(HttpMethodBase request) {
		Map<String, String> headers = nextHeaders.get();
		nextHeaders.set(null);
		includeHeaders(request, headers);
	}

	static void includeHeaders(HttpMethodBase request, Map<String, String> headers) {
		if (headers != null) {
			for(Entry<String,String> e : headers.entrySet())
				request.addRequestHeader(e.getKey(), e.getValue());
		}
	}

	/**
	 * Retreives a url. Returns if statuscode is 200 OK, 304 NOT MODIFIED or 404 NOT FOUND. Exception otherwise. 
	 * @throws IOException 
	 * @throws HttpException 
	 */
	private static HttpResponseData doRequest(String method, String url,
			Map<String, String> requestHeaders, HttpMethodParams params,
			RequestEntity requestEntity, Predicate<InputStream> onSuccess) throws HttpException, IOException {
		if (RestServices.LOGCONSUME.isDebugEnabled())
			RestServices.LOGCONSUME.debug("Fetching '" + url + "'..");
		
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

		if (requestHeaders != null) for(Entry<String, String> e : requestHeaders.entrySet())
			request.addRequestHeader(e.getKey(), e.getValue());
		includeHeaders(request);
		
		if (params != null)
			request.setParams(params);
		
		if (request instanceof PostMethod && requestEntity != null)
			((PostMethod)request).setRequestEntity(requestEntity);
		else if (request instanceof PutMethod && requestEntity != null)
			((PutMethod)request).setRequestEntity(requestEntity);
		
		try {
			int status = client.executeMethod(request);
			Header responseEtag = request.getResponseHeader(RestServices.ETAG_HEADER);
			
			HttpResponseData response = new HttpResponseData(method, url, status, responseEtag == null ? null : responseEtag.getValue());
			InputStream instream = request.getResponseBodyAsStream(); 
			if (onSuccess != null && status >= 200 && status < 300 && instream != null) //NO CONENT doesnt yield a stream..
				onSuccess.apply(instream);
			else if (instream != null)
				response.setBody(IOUtils.toString(instream));
				
			RestServices.LOGCONSUME.info(response);
			
			return response;
		}
		finally {
			request.releaseConnection();
		}
	}
	
	public static void readJsonObjectStream(String url, final Predicate<Object> onObject) throws Exception, IOException {
		HttpResponseData response = doRequest("GET", url, null,null, null, new Predicate<InputStream>() {

			@Override
			public boolean apply(InputStream stream) {
				JSONTokener x = new JSONTokener(stream);
				//Based on: https://github.com/douglascrockford/JSON-java/blob/master/JSONArray.java
				if (x.nextClean() != '[') 
		            throw x.syntaxError("A JSONArray text must start with '['");
	            for (;;) {
	            	switch(x.nextClean()) {
		            	case ',':
		            		break;
		            	case ']':
		            		return true;
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
		});
		
		if (response.getStatus() != HttpStatus.SC_OK)
			throw new RestConsumeException(response.getStatus(), "Failed to start request stream on '" + url + "', expected status to be 200 OK");
	}
	

	static void syncCollection(String collectionUrl, String onUpdateMF, String onDeleteMF) throws Exception {
		IDataType type = Utils.getFirstArgumentType(onUpdateMF);
		if (!type.isMendixObject())
			throw new RuntimeException("First argument should be an Entity! " + onUpdateMF);

		GetMethod get = new GetMethod(collectionUrl);
		get.setRequestHeader(RestServices.ACCEPT_HEADER, RestServices.TEXTJSON);

		try {
			int status = client.executeMethod(get);
			if (status != HttpStatus.SC_OK)
				throw new RestConsumeException(status, "Failed to start request stream on " + collectionUrl);
			
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
	

	public static RequestResult request(final IContext context, HttpMethod method, final String url, 
			final IMendixObject source, final IMendixObject target, final boolean asFormData) throws Exception {
		if (context == null)
			throw new IllegalArgumentException("Context should not be null");
		
		if (!Utils.isUrl(url))
			throw new IllegalArgumentException("Requested resource seems to be an invalid URL: " + url);

		if (method == null)
			method = HttpMethod.GET;
		
		final boolean isFileSource = source != null && Core.isSubClassOf(FileDocument.entityName, source.getType()); 
		final boolean isFileTarget = target != null && Core.isSubClassOf(FileDocument.entityName, target.getType()); 

		if (isFileSource && !(method == HttpMethod.POST || method == HttpMethod.PUT))
			throw new IllegalArgumentException("Files can only be send with method is POST or PUT");

		Map<String, String> requestHeaders = new HashMap<String, String>();
		HttpMethodParams params = new HttpMethodParams();
		RequestEntity requestEntity = null;
		
		final JSONObject data = source == null ? null : JsonSerializer.writeMendixObjectToJson(context, source);
		
		//register params, if its a GET request or formData format is used
		if (source != null && (asFormData || method == HttpMethod.GET || method == HttpMethod.DELETE)) {
			for(String key : JSONObject.getNames(data)) {
				if (isFileSource && FileDocument.MemberNames.valueOf(key) != null) //Do not pick up default filedoc attrs!
					continue; 
				
				Object value = data.get(key);
				if (value != null && !(value instanceof JSONObject) && !(value instanceof JSONArray))
					params.setParameter(key, String.valueOf(value));
			}
			
		}
			
		//Setup request entity for file
		if (!asFormData && isFileSource) {
			requestEntity = new InputStreamRequestEntity(Core.getFileDocumentContent(context, source));
		}
		else if (source != null && asFormData && isFileSource) {
			requestHeaders.put(RestServices.HEADER_CONTENTTYPE, "multipart/form-data");
			
			String fileName = (String) source.getValue(context, FileDocument.MemberNames.Name.toString()); 
			
			ByteArrayPartSource p = new ByteArrayPartSource(fileName, IOUtils.toByteArray(Core.getFileDocumentContent(context, source)));
			requestEntity = new MultipartRequestEntity(new Part[] { new FilePart(fileName, p) }, params);
		}
		else if (asFormData && !isFileSource)
			requestHeaders.put(RestServices.HEADER_CONTENTTYPE, "application/x-www-form-urlencoded");
		else if (data != null)
			requestEntity = new StringRequestEntity(data.toString(4), RestServices.TEXTJSON, RestServices.UTF8);
		
		final StringBuilder bodyBuffer = new StringBuilder();
		HttpResponseData response = doRequest(method.toString(), url, requestHeaders, params, requestEntity, new Predicate<InputStream>() {

			@Override
			public boolean apply(InputStream stream) {
				try {
					if (isFileTarget)
						Core.storeFileDocumentContent(context, target, stream);
					else {
						String body = IOUtils.toString(stream);
						bodyBuffer.append(body);
						if (target != null){
							if (!body.matches("^\\s*\\{[\\s\\S]*"))
								throw new IllegalArgumentException("Response body does not seem to be a valid JSON Object. A JSON object starts with '{' but found: " + body);
							JsonDeserializer.readJsonDataIntoMendixObject(context, new JSONTokener(body).nextValue(), target, true);
						}
					}
					return true;
				}
				catch(Exception e) {
					throw new RuntimeException(e);
				}
			}
		});

		//wrap up
		if (!response.isOk())
			throw new RestConsumeException(response);
		
		response.setBody(bodyBuffer.toString());
		if (target != null && Core.isSubClassOf(ReferableObject.entityName, target.getType())) {
			target.setValue(context, ReferableObject.MemberNames.URL.toString(), url);
			target.setValue(context, ReferableObject.MemberNames.ETag.toString(), response.getETag());
		}
		
		return response.asRequestResult(context);
	}
	
	public static void addCredentialsToNextRequest(String username,
			String password) {
		addHeaderToNextRequest(RestServices.HEADER_AUTHORIZATION, RestServices.BASIC_AUTHENTICATION + " " + StringUtils.base64Encode(username + ":" + password));
	}

	public static RequestResult deleteObject(IContext context, String resourceUrl,
			String optEtag) throws Exception {
		useETagInNextRequest(optEtag);
		return request(context, HttpMethod.DELETE, resourceUrl, null, null, false);
	}

	public static RequestResult getObject(IContext context, String url,
			String optEtag, IMendixObject stub) throws Exception {
		useETagInNextRequest(optEtag);
		return request(context, HttpMethod.GET, url, null, stub, false);
	}

	public static RequestResult putObject(IContext context, String url,
			IMendixObject dataObject, String optEtag) throws Exception {
		useETagInNextRequest(optEtag);
		return request(context, HttpMethod.PUT, url, dataObject, null, false);
	}

	public static RequestResult postObject(IContext context, String collectionUrl,
			IMendixObject dataObject, Boolean asFormData) throws Exception {
		return request(context, HttpMethod.POST, collectionUrl, dataObject, null, asFormData);
	}
	
	public static void useETagInNextRequest(String eTag) {
		if (eTag != null)
			addHeaderToNextRequest(RestServices.IFNONEMATCH_HEADER, eTag);
	}

	
}
