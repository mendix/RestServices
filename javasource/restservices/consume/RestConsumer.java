package restservices.consume;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.auth.AuthPolicy;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.cookie.CookiePolicy;
import org.apache.commons.httpclient.cookie.CookieSpec;
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
import org.apache.commons.httpclient.methods.multipart.StringPart;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import restservices.RestServices;
import restservices.proxies.Cookie;
import restservices.proxies.HttpMethod;
import restservices.proxies.ReferableObject;
import restservices.proxies.RequestResult;
import restservices.proxies.ResponseCode;
import restservices.proxies.RestServiceError;
import restservices.util.JsonDeserializer;
import restservices.util.JsonSerializer;
import restservices.util.UriTemplate;
import restservices.util.Utils;
import system.proxies.FileDocument;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaAssociation;
import com.mendix.systemwideinterfaces.core.meta.IMetaAssociation.AssociationType;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;

import communitycommons.StringUtils;

public class RestConsumer {
	private static ThreadLocal<HttpResponseData> lastConsumeError = new ThreadLocal<HttpResponseData>();
	
	private static MultiThreadedHttpConnectionManager connectionManager = new MultiThreadedHttpConnectionManager();
    static HttpClient client = new HttpClient(connectionManager);

	static {
		connectionManager.getParams().setMaxConnectionsPerHost(HostConfiguration.ANY_HOST_CONFIGURATION, 10);
	}
    
	public static class HttpResponseData{
		private int status;
		private String body = null;
		private String eTag;
		private String url;
		private String method;
		private Header[] headers;

		HttpResponseData(String method, String url, int status, String eTag, Header[] headers) {
			this.url = url;
			this.status = status;
			this.eTag = eTag;
			this.method = method;
			this.headers = headers;
		}
		
		public void setBody(String body) {
			this.body = body;
		}
		
		public boolean isOk() {
			return status == HttpStatus.SC_NOT_MODIFIED || (status >= 200 && status < 300);
		}
		
		public RequestResult asRequestResult(IContext context) {
			JSONObject jsonHeaders = getResponseHeadersAsJson();

			RequestResult rr = new RequestResult(context);
			rr.setRequestUrl(url);
			rr.setETag(getETag());
			rr.setRawResponseCode(status);
			rr.setResponseBody(getBody());
			rr.setResponseCode(asResponseCode());
			rr.set_ResponseHeaders(jsonHeaders.toString());
			
			if (
					status >= 400 && status < 600 
					&& jsonHeaders.has(RestServices.HEADER_CONTENTTYPE)
					&& jsonHeaders.getJSONArray(RestServices.HEADER_CONTENTTYPE).getString(0).contains("json")
			) {
				RestServiceError rse = new RestServiceError(context);
				try {
					JsonDeserializer.readJsonDataIntoMendixObject(context, new JSONObject(getBody()), rse.getMendixObject(), false);
					rr.setErrorDetails(rse);
				} catch (Exception e) {
					RestServices.LOGCONSUME.warn("Failed to parse error message to JSON: " + getBody());
				}
			}
			
			return rr;
		}

		public ResponseCode asResponseCode() {
			if (status == IMxRuntimeResponse.NOT_MODIFIED)
				return ResponseCode.NotModified;
			else if (status >= 400 || status <= 0) //-1 is used if making the connection failed
				return ResponseCode.Error;
			else 
				return ResponseCode.OK; //We consider all other responses 'OK-ish', even redirects and such.. Users can check the actual response code with the RawResponse field
		}
		
		@Override public String toString() {
			return String.format("[HTTP Request: %s '%s' --> Response status: %d %s, ETag: %s, body: '%s']", 
					method, url, 
					status, status < 0 ? "CONNECTION FAILED" : HttpStatus.getStatusText(status), 
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
		
		private JSONObject getResponseHeadersAsJson() {
			JSONObject res = new JSONObject();
			if (headers != null) for(Header header : headers) {
				if (!res.has(header.getName()))
					res.put(header.getName(), new JSONArray());
				res.getJSONArray(header.getName()).put(header.getValue());
			}
			return res;
		}
	}
	
	static ThreadLocal<Map<String, String>> nextHeaders = new ThreadLocal<Map<String, String>>();
	
	private static Map<String, String> prepareNextHeadersMap() {
		Map<String, String> headers = nextHeaders.get();
		
		if (headers == null) {
			headers = new HashMap<String, String>();
			nextHeaders.set(headers);
		}
		return headers;
	}

	public static synchronized void setGlobalRequestSettings(Long maxConcurrentRequests, Long timeout) {
		if (timeout != null) {
			client.getParams().setConnectionManagerTimeout(timeout);
			client.getParams().setSoTimeout(timeout.intValue());
		}

		if (maxConcurrentRequests != null) {
			connectionManager.getParams().setMaxConnectionsPerHost(HostConfiguration.ANY_HOST_CONFIGURATION, maxConcurrentRequests.intValue());
			connectionManager.getParams().setMaxTotalConnections(maxConcurrentRequests.intValue() * 2);
		}
	}
	
	public static void addHeaderToNextRequest(String header, String value) {
		prepareNextHeadersMap().put(header, value);
	}
	
	public static void addCookieToNextRequest(Cookie cookie) {
		if (cookie == null || cookie.getName().isEmpty())
			throw new IllegalArgumentException();
		
		Map<String, String> headers = prepareNextHeadersMap();
		
		org.apache.commons.httpclient.Cookie rq = new org.apache.commons.httpclient.Cookie("", cookie.getName(), cookie.getValue());
		String cookiestr = CookiePolicy.getDefaultSpec().formatCookie(rq);
		
		if (!headers.containsKey("Cookie"))
			headers.put("Cookie", cookiestr);
		else
			headers.put("Cookie", headers.get("Cookie") + "; " + cookiestr);
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
	private static HttpResponseData doRequest(String method, String url, Map<String, String> requestHeaders,
			Map<String, String> params, RequestEntity requestEntity, Predicate<InputStream> onSuccess) throws HttpException, IOException {
		if (RestServices.LOGCONSUME.isDebugEnabled())
			RestServices.LOGCONSUME.debug("Fetching '" + url + "'..");
		
		HttpMethodBase request = null;

		try {
			if (params != null && !"POST".equals(method)) {
				//append params to url. Do *not* use request.setQueryString; that will override any args already in there
				for(Entry<String, String> e : params.entrySet())
					url = Utils.appendParamToUrl(url, e.getKey(), e.getValue());
			}
			
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
			
			request.getParams().setCookiePolicy(CookiePolicy.IGNORE_COOKIES);
			request.setRequestHeader(RestServices.HEADER_ACCEPT, RestServices.CONTENTTYPE_APPLICATIONJSON);
			
			if (requestHeaders != null) for(Entry<String, String> e : requestHeaders.entrySet())
				request.addRequestHeader(e.getKey(), e.getValue());
			includeHeaders(request);
			
			if (params != null && request instanceof PostMethod) 
				((PostMethod)request).addParameters(mapToNameValuePairs(params));
			
			if (request instanceof PostMethod && requestEntity != null)
				((PostMethod)request).setRequestEntity(requestEntity);
			else if (request instanceof PutMethod && requestEntity != null)
				((PutMethod)request).setRequestEntity(requestEntity);
		
			int status = client.executeMethod(request);
			Header responseEtag = request.getResponseHeader(RestServices.HEADER_ETAG);
			
			HttpResponseData response = new HttpResponseData(method, url, status, responseEtag == null ? null : responseEtag.getValue(), request.getResponseHeaders());
			InputStream instream = request.getResponseBodyAsStream(); 
			if (onSuccess != null && status >= 200 && status < 300 && instream != null) //NO CONENT doesnt yield a stream..
				onSuccess.apply(instream);
			else if (instream != null)
				response.setBody(IOUtils.toString(instream));
			
			if (RestServices.LOGCONSUME.isDebugEnabled())
			{
				RestServices.LOGCONSUME.debug(response);
			}	
			
			return response;
		}

		catch(Exception e) {
			HttpResponseData response = new HttpResponseData(method, url, -1, null, null);
			response.setBody(e.getClass().getName() + ": " + e.getMessage());
			RestServices.LOGCONSUME.error("Failed to connect to " + url + ": " + e.getMessage(), e);
			return response;
		}
		
		finally {
			if (request != null)
				request.releaseConnection();
		}
	}
	
	private static NameValuePair[] mapToNameValuePairs(Map<String, String> params) {
		NameValuePair[] res = new NameValuePair[params.size()];
		int i = 0;
		for(Entry<String, String> e : params.entrySet()) {
			res[i] = new NameValuePair(e.getKey(), e.getValue());
			i++;
		}
		return res;
	}
	
	public static void readJsonObjectStream(String url, final Predicate<Object> onObject) throws Exception, IOException {
		lastConsumeError.set(null);
		HttpResponseData response = doRequest("GET", url, null, null, null, new Predicate<InputStream>() {

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
		
		if (response.getStatus() != HttpStatus.SC_OK) {
			lastConsumeError.set(response);
			throw  new RestConsumeException(response.getStatus(), "Failed to start request stream on '" + url + "', expected status to be 200 OK");
		}
	}

	public static void registerCredentials(String urlBasePath, String username, String password) throws MalformedURLException
	{
		client.getParams().setAuthenticationPreemptive(true);
		Credentials defaultcreds = new UsernamePasswordCredentials(username, password);
		URL url = new URL(urlBasePath);
		client.getState().setCredentials(new AuthScope(url.getHost(), url.getPort(), AuthScope.ANY_REALM), defaultcreds);
	}
	
	public static void registerNTCredentials(String urlBasePath, String username, String password, String domain) throws MalformedURLException
	{
		client.getParams().setAuthenticationPreemptive(true);
		URL url = new URL(urlBasePath);
		Core.getLogger("NTLM").info(url.getHost());
		Credentials defaultcreds = new NTCredentials(username, password, url.getHost(), domain);
		
		AuthPolicy.registerAuthScheme(AuthPolicy.NTLM, restservices.util.JCIFS_NTLMScheme.class);
		
		List<String> authpref = new ArrayList<String>();
		authpref.add(AuthPolicy.NTLM);
		
		client.getParams().setParameter("http.auth.target-scheme-pref", authpref);
		client.getState().setCredentials(new AuthScope(AuthScope.ANY), defaultcreds);
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
		if (resultList == null || resultList.size() > 0)
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
	
	public static RequestResult request(final IContext context, HttpMethod method, String url, 
			final IMendixObject source, final IMendixObject target, final boolean asFormData) throws Exception {
		lastConsumeError.set(null);
		
		if (context == null)
			throw new IllegalArgumentException("Context should not be null");
		
		if (method == null)
			method = HttpMethod.GET;
		
		final boolean isFileSource = source != null && Core.isSubClassOf(FileDocument.entityName, source.getType()); 
		final boolean isFileTarget = target != null && Core.isSubClassOf(FileDocument.entityName, target.getType()); 
		final boolean hasFileParts = source != null && hasFileParts(source.getMetaObject());
		
		if ((isFileSource || hasFileParts) && !(method == HttpMethod.POST || method == HttpMethod.PUT))
			 throw new IllegalArgumentException("Files can only be send with method is POST or PUT");

		Map<String, String> requestHeaders = new HashMap<String, String>();
		Map<String, String> params = new HashMap<String, String>();
		RequestEntity requestEntity = null;
		
		final JSONObject data = source == null ? null : JsonSerializer.writeMendixObjectToJson(context, source, false);
		
		boolean appendDataToUrl = source != null && (asFormData || method == HttpMethod.GET || method == HttpMethod.DELETE);
		url = updateUrlPathComponentsWithParams(url, appendDataToUrl, isFileSource, data, params);
			
		//Setup request entity for file
		if (!asFormData && isFileSource) {
			requestEntity = new InputStreamRequestEntity(Core.getFileDocumentContent(context, source));
		}
		else if (source != null && asFormData && (isFileSource || hasFileParts)) {
			requestEntity = buildMultiPartEntity(context, source, params);
		}
		else if (asFormData && !isFileSource)
			requestHeaders.put(RestServices.HEADER_CONTENTTYPE, RestServices.CONTENTTYPE_FORMENCODED);
		else if (data != null && data.length() != 0) {				
			requestEntity = new StringRequestEntity(data.toString(4), RestServices.CONTENTTYPE_APPLICATIONJSON, RestServices.UTF8);
			if (RestServices.LOGCONSUME.isDebugEnabled()) {
				RestServices.LOGCONSUME.debug("[Body JSON Data] " + data.toString());
			}
		}
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
		if (!response.isOk()) {
			lastConsumeError.set(response);
			throw new RestConsumeException(response);
		}
		
		response.setBody(bodyBuffer.toString());
		if (target != null && Core.isSubClassOf(ReferableObject.entityName, target.getType())) {
			target.setValue(context, ReferableObject.MemberNames.URL.toString(), url);
			target.setValue(context, ReferableObject.MemberNames.ETag.toString(), response.getETag());
		}
		
		return response.asRequestResult(context);
	}

	private static String updateUrlPathComponentsWithParams(String url, boolean appendDataToUrl, final boolean isFileSource, final JSONObject data, Map<String, String> params) {
		//substitute template variable in the uri, and make sure they are not send along as body / params data
		UriTemplate uriTemplate = new UriTemplate(url);
		
		Map<String, String> keyMapping = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
		if (data != null) for (Iterator<String> iterator = data.keys(); iterator.hasNext();) {
			String key = iterator.next();
			keyMapping.put(key, key);
		}
		
		Map<String, String> values = new HashMap<String, String>();
		if (data != null) for(String templateVar : uriTemplate.getTemplateVariables()) {
			if (keyMapping.containsKey(templateVar)) {
				String realkey = (String) keyMapping.get(templateVar);
				Object value = data.get(realkey);
				if (!(value instanceof JSONObject) && !(value instanceof JSONArray)) {
					data.remove(realkey);
					values.put(templateVar, value == null || value == JSONObject.NULL ? "" : String.valueOf(value));
				}
			}
		}
		
		url = uriTemplate.createURI(values);

		//register params, if its a GET request or formData format is used
		if (data != null && data.length() > 0 && appendDataToUrl) {
			for(String key : JSONObject.getNames(data)) {
				if (isFileSource && isFileDocAttr(key)) 
					continue; //Do not pick up default filedoc attrs!
				if (Utils.isSystemAttribute(key))
					continue;
				Object value = data.get(key);
				if (value != null && !(value instanceof JSONObject) && !(value instanceof JSONArray))
					params.put(key, String.valueOf(value));
			}
			
		}
		return url;
	}

	private static RequestEntity buildMultiPartEntity(final IContext context,
			final IMendixObject source, Map<String, String> params)
			throws IOException, CoreException {
		// MWE: don't set contenttype to CONTENTTYPE_MULTIPART; this will be
		// done by the request entity and add the boundaries
		List<Part> parts = Lists.newArrayList();

		// This object self could be a filedocument
		if (Core.isSubClassOf(FileDocument.entityName, source.getType())) {
			String partName = getFileDocumentFileName(context, source);
			if (partName == null || partName.isEmpty())
				throw new IllegalArgumentException("The filename of a System.FileDocument in a multipart request should reflect the part name and cannot be empty");
			addFilePart(context, partName, source, parts);
		}
		// .. or one of its children could be a filedocument. This way multiple
		// file parts, or specifically named file parts can be send
		for (String name : getAssociationsReferingToFileDocs(source
				.getMetaObject())) {
			IMendixIdentifier subObject = (IMendixIdentifier) source.getValue(
					context, name);
			params.remove(Utils.getShortMemberName(name));
			if (subObject != null) {
				addFilePart(context, Utils.getShortMemberName(name),
						Core.retrieveId(context, subObject), parts);
			}
		}

		// serialize any other members as 'normal' key value pairs
		for (Entry<String, String> e : params.entrySet()) {
			parts.add(new StringPart(e.getKey(), e.getValue(),
					RestServices.UTF8));
		}

		params.clear();
		return new MultipartRequestEntity(parts.toArray(new Part[0]),
				new HttpMethodParams());
	}

	private static Set<String> getAssociationsReferingToFileDocs(
			IMetaObject meta) {
		Set<String> names = new HashSet<String>();
		for (IMetaAssociation assoc : meta.getMetaAssociationsParent()) {
			if (assoc.getType() == AssociationType.REFERENCE && Core.isSubClassOf(FileDocument.entityName, assoc.getChild().getName()))
				names.add(assoc.getName());
		}
		return names;
	}

	private static boolean hasFileParts(IMetaObject metaObject) {
		return getAssociationsReferingToFileDocs(metaObject).size() > 0;
	}

	private static void addFilePart(final IContext context, String partname,
			final IMendixObject source, List<Part> parts) throws IOException {
		ByteArrayPartSource p = new ByteArrayPartSource(
				getFileDocumentFileName(context, source),
				IOUtils.toByteArray(Core.getFileDocumentContent(context, source)));
		parts.add(new FilePart(partname, p));
	}

	private static String getFileDocumentFileName(final IContext context,
			final IMendixObject source) {
		return (String) source.getValue(context, FileDocument.MemberNames.Name.toString());
	}
	   
	private static boolean isFileDocAttr(String key) {
		try {
			FileDocument.MemberNames.valueOf(key); 
			return true;
		}
		catch (IllegalArgumentException e) {
			//Ok, this is a non filedoc attr
			return false;
		}
	}

	public static void addCredentialsToNextRequest(String username, String password) {
		addHeaderToNextRequest(RestServices.HEADER_AUTHORIZATION, RestServices.BASIC_AUTHENTICATION + " " + StringUtils.base64Encode(username + ":" + password));
	}

	public static RequestResult deleteObject(IContext context, String resourceUrl,
			String optEtag) throws Exception {
		useETagInNextRequest(optEtag);
		return request(context, HttpMethod.DELETE, resourceUrl, null, null, false);
	}

	public static RequestResult getObject(IContext context, String url,
			String optEtag, IMendixObject target) throws Exception {
		useETagInNextRequest(optEtag);
		return request(context, HttpMethod.GET, url, null, target, false);
	}
	
	public static RequestResult getObject(IContext context, String url, IMendixObject target) throws Exception {
		return request(context, HttpMethod.GET, url, null, target, false);
	}
	
	public static RequestResult getObject(IContext context, String url, IMendixObject source, IMendixObject target) throws Exception {
		return request(context, HttpMethod.GET, url, source, target, false);
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
	
	public static RequestResult postObject(IContext context, String collectionUrl,
			IMendixObject dataObject, IMendixObject targetObject) throws Exception {
		return request(context, HttpMethod.POST, collectionUrl, dataObject, targetObject, false);
	}
	
	public static void useETagInNextRequest(String eTag) {
		if (eTag != null)
			addHeaderToNextRequest(RestServices.HEADER_IFNONEMATCH, eTag);
	}
	
	public static String getResponseHeaderFromRequestResult(
			RequestResult requestResult, String headerName) {
		if (requestResult == null)
			throw new IllegalArgumentException("No request result provided");
		
		JSONObject headers = new JSONObject(requestResult.get_ResponseHeaders());
		if (headers.has(headerName))
			return headers.getJSONArray(headerName).getString(0); 
		return null;
	}

	public static List<Cookie> getResponseCookiesFromRequestResult(IContext context, RequestResult requestResult) throws MalformedURLException {
		if (requestResult == null)
			throw new IllegalArgumentException("No request result provided");
		
		List<Cookie> res = new ArrayList<Cookie>();
		JSONObject headers = new JSONObject(requestResult.get_ResponseHeaders());
		
		URL requestUrl = new URL(requestResult.getRequestUrl());
		CookieSpec spec = CookiePolicy.getDefaultSpec();
		
		if (headers.has("Set-Cookie")) {
			JSONArray cookies = headers.getJSONArray("Set-Cookie");
			for(int i = 0; i < cookies.length(); i++) {
				try {
					org.apache.commons.httpclient.Cookie[] innercookies = spec.parse(requestUrl.getHost(), requestUrl.getPort(), requestUrl.getPath(), "https".equals(requestUrl.getProtocol()), cookies.getString(i));
					for(org.apache.commons.httpclient.Cookie innercookie : innercookies) {
						Cookie cookie = new Cookie(context);
						cookie.setName(innercookie.getName());
						cookie.setValue(innercookie.getValue());
						cookie.setDomain(innercookie.getDomain());
						cookie.setPath(innercookie.getPath());
						cookie.setMaxAgeSeconds(innercookie.getExpiryDate() == null ? -1 : Math.round((innercookie.getExpiryDate().getTime() - System.currentTimeMillis()) / 1000L));
						res.add(cookie);
					}
				} catch (Exception e) {
					RestServices.LOGCONSUME.warn("Failed to parse cookie: " + e.getMessage(), e);
				}
			}
		}
		
		return res;
	}
	
	public static RequestResult getLastConsumeError(IContext context) {
		HttpResponseData res = lastConsumeError.get();
		if (res == null)
			return null;
		lastConsumeError.set(null);
		return res.asRequestResult(context);
	}
}
