package restservices.publish;


import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.Map.Entry;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.uri.UriTemplate;
import org.json.JSONException;
import org.json.JSONObject;

import restservices.RestServices;
import restservices.proxies.DataServiceDefinition;
import restservices.proxies.HttpMethod;
import restservices.proxies.RestServiceError;
import restservices.publish.RestPublishException.RestExceptionType;
import restservices.publish.RestServiceRequest.Function;
import restservices.util.Utils;

import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.externalinterface.connector.RequestHandler;
import com.mendix.m2ee.api.IMxRuntimeRequest;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.modules.webservices.WebserviceException;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.ISession;

import communitycommons.XPath;

public class RestServiceHandler extends RequestHandler{

	private static RestServiceHandler instance = null;
	private static boolean started = false;
	
	
	private static class HandlerRegistration {
		final UriTemplate template;
		final String roleOrMicroflow;
		final IRestServiceHandler handler;

		HandlerRegistration(UriTemplate template, String roleOrMicroflow, IRestServiceHandler handler) {
			this.template = template;
			this.roleOrMicroflow = roleOrMicroflow;
			this.handler = handler;
		}
	}
	
	private static ListMultimap<String, HandlerRegistration> services = ArrayListMultimap.create();

	public synchronized static void start(IContext context) throws Exception {
		if (instance == null) {
			RestServices.LOGPUBLISH.info("Starting RestServices module...");

			instance = new RestServiceHandler();
			Core.addRequestHandler(RestServices.PATH_REST, instance);
			started = true;
			loadConfig(context);

			registerService(HttpMethod.GET, "/", "*", new IRestServiceHandler() {

				@Override
				public void execute(RestServiceRequest rsr,
						Map<String, String> params) throws Exception {
					ServiceDescriber.serveServiceOverview(rsr); 
				}
				
			});
			
			RestServices.LOGPUBLISH.info("Starting RestServices module... DONE");
		}
	}

	private static void loadConfig(IContext context) throws CoreException {
		for (DataServiceDefinition def : XPath.create(context, DataServiceDefinition.class).all()) {
			loadConfig(def, false);
		}
	}

	public static void loadConfig(DataServiceDefinition def, boolean throwOnFailure) {
		if (!started)
			return;

		RestServices.LOGPUBLISH.info("Loading service " + def.getName()+ "...");
		String errors = null;
		try {
			ConsistencyChecker.check(def);
		}
		catch(Exception e) {
			errors = "Failed to run consistency checks: " + e.getMessage();
		}

		if (errors != null) {
			String msg = "Failed to load service '" + def.getName() + "': \n" + errors;
			RestServices.LOGPUBLISH.error(msg);
			if (throwOnFailure)
				throw new IllegalStateException(msg);
		}
		else {
			RestServices.LOGPUBLISH.info("Reloading definition of service '" + def.getName() + "'");
			DataService service = new DataService(def);
			service.register();
			
			RestServices.LOGPUBLISH.info("Loading service " + def.getName()+ "... DONE");
		}
	}
	
	public static void registerService(HttpMethod method, String templatePath, String roleOrMicroflow, IRestServiceHandler handler) {
		checkNotNull(method, "method");
		
		services.put(method.toString(), new HandlerRegistration(new UriTemplate(templatePath), roleOrMicroflow, handler));

		RestServices.LOGPUBLISH.info("Registered data service on '" + templatePath + "'");
	}
	
	private static void requestParamsToJsonMap(RestServiceRequest rsr, Map<String, String> params) {
		for (String param : rsr.request.getParameterMap().keySet())
			params.put(param, rsr.request.getParameter(param));
	}



	public static boolean executeHandler(final RestServiceRequest rsr, String method, String relpath, ISession existingSession) throws Exception {
		if (!services.containsKey(method))
			return false;
		
		final Map<String, String> params = Maps.newHashMap();
		for(final HandlerRegistration reg : services.get(method)) {
			if (reg.template.match(relpath, params)) {
				
				//Apply URL decoding on path parameters
				for(Entry<String, String> e : params.entrySet()) {
					e.setValue(Utils.urlDecode(e.getValue()));
				}
				
				//Mixin query parameters
				requestParamsToJsonMap(rsr, params);

				//Execute the reqeust
				if (rsr.authenticate(reg.roleOrMicroflow, existingSession)) {
					
					rsr.withTransaction(new Function<Boolean>() {

						@Override
						public Boolean apply() throws Exception {
							reg.handler.execute(rsr, params);
							return true;
						}

					});
					
					return true;
				} else {
					throw new RestPublishException(RestExceptionType.UNAUTHORIZED, "Unauthorized. Please provide valid credentials or set up a Mendix user session");
				}
			}
		}
		return false;
	}
	
	@Override
	public void processRequest(IMxRuntimeRequest req, IMxRuntimeResponse resp,
			String _) {

		long start = System.currentTimeMillis();

		HttpServletRequest request = (HttpServletRequest) req.getOriginalRequest();
		HttpServletResponse response = (HttpServletResponse) resp.getOriginalResponse();

		String method = request.getMethod();
		URL u;
		try {
			u = new URL(request.getRequestURL().toString());
		} catch (MalformedURLException e1) {
			throw new IllegalStateException(e1);
		}

		String relpath = u.getPath().substring(RestServices.PATH_REST.length() + 1);
		String requestStr =  method + " " + relpath;

		response.setCharacterEncoding(RestServices.UTF8);
		response.setHeader("Expires", "-1");

		if (RestServices.LOGPUBLISH.isDebugEnabled())
			RestServices.LOGPUBLISH.debug("incoming request: " + Utils.getRequestUrl(request));
	
		RestServiceRequest rsr = new RestServiceRequest(request, response, resp, relpath);
		try {
			ISession existingSession = getSessionFromRequest(req);
			
			boolean handled = executeHandler(rsr, method, relpath, existingSession);
			
			if (!handled)
				throw new RestPublishException(RestExceptionType.NOT_FOUND, "Unknown service at: '" + relpath + "'");
			
			if (RestServices.LOGPUBLISH.isDebugEnabled())
					RestServices.LOGPUBLISH.debug("Served " + requestStr + " in " + (System.currentTimeMillis() - start) + "ms.");
		}
		catch(RestPublishException rre) {
			RestServices.LOGPUBLISH.warn("Failed to serve " + requestStr + ": " + rre.getType() + " " + rre.getMessage());

			serveErrorPage(rsr, rre.getStatusCode(), rre.getType().toString() + ": " + requestStr + " " + rre.getMessage(), rre.getType().toString());
		}
		catch(JSONException je) {
			RestServices.LOGPUBLISH.warn("Failed to serve " + requestStr + ": Invalid JSON: " + je.getMessage());

			serveErrorPage(rsr, HttpStatus.SC_BAD_REQUEST, "JSON is incorrect. Please review the request data: " + je.getMessage(), "INVALID_JSON");
		}
		catch(Throwable e) {
			Throwable cause = ExceptionUtils.getRootCause(e);
			if (cause instanceof CustomRestServiceException) {
				CustomRestServiceException rse = (CustomRestServiceException) cause;
				RestServices.LOGPUBLISH.warn(String.format("Failed to serve %s: %d (code: %s): %s", requestStr, rse.getHttpStatus(), rse.getFaultCode(), rse.getMessage()));
				serveErrorPage(rsr, rse.getHttpStatus(), rse.getMessage(), rse.getFaultCode());
			}
			else if (cause instanceof WebserviceException) {
				RestServices.LOGPUBLISH.warn("Invalid request " + requestStr + ": " +cause.getMessage());
				serveErrorPage(rsr, HttpStatus.SC_BAD_REQUEST, cause.getMessage(), ((WebserviceException) cause).getFaultCode());
			}
			else {
				RestServices.LOGPUBLISH.error("Failed to serve " + requestStr + ": " +e.getMessage(), e);
				serveErrorPage(rsr, HttpStatus.SC_INTERNAL_SERVER_ERROR, "Failed to serve: " + requestStr + ": An internal server error occurred. Please check the application logs or contact a system administrator.", null);
			}
		}
		finally {
			rsr.dispose();
		}
	}

	private void serveErrorPage(RestServiceRequest rsr, int status, String error, String errorCode) {
		rsr.response.reset();
		rsr.response.setStatus(status);

		//reques authentication
		if (status == HttpStatus.SC_UNAUTHORIZED)
			rsr.response.addHeader(RestServices.HEADER_WWWAUTHENTICATE, "Basic realm=\"Rest Services\"");

		rsr.startDoc();

		switch(rsr.getResponseContentType()) {
		default:
		case HTML:
			rsr.write("<h1>" + error + "</h1>");
			if (errorCode != null)
				rsr.write("<p>Error code: " + errorCode + "</p>");
			rsr.write("<p>Http status code: " + status + "</p>");
			break;
		case JSON:
		case XML:
			JSONObject data = new JSONObject();
			data.put(RestServiceError.MemberNames.errorMessage.toString(), error);
			if (errorCode != null && !errorCode.isEmpty())
				data.put(RestServiceError.MemberNames.errorCode.toString(), errorCode);
			rsr.datawriter.value(data);
			break;
		}

		rsr.endDoc();
	}

	private void dispatchDataService(String method, String[] parts, RestServiceRequest rsr, DataService service) throws Exception, IOException,
			CoreException, RestPublishException {
		/* TODO: refactor 
		boolean handled = false;
		boolean isGet = "GET".equals(method);

		switch(parts.length) {
		case 1:
			if (isGet) {
				handled = true;
				if (rsr.request.getParameter(RestServices.PARAM_ABOUT) != null)
					new ServiceDescriber(rsr, service.def).serveServiceDescription();
				else if (rsr.request.getParameter(RestServices.PARAM_COUNT) != null)
					service.serveCount(rsr);
				else
					service.serveListing(rsr,
							"true".equals(rsr.getRequestParameter(RestServices.PARAM_DATA,"false")),
							Integer.valueOf(rsr.getRequestParameter(RestServices.PARAM_OFFSET, "-1")),
							Integer.valueOf(rsr.getRequestParameter(RestServices.PARAM_LIMIT, "-1")));
			}
			else if ("POST".equals(method)) {
				handled = true;
				JSONObject data;
				if (RestServices.CONTENTTYPE_FORMENCODED.equalsIgnoreCase(rsr.request.getContentType())) {
					data = new JSONObject();
					requestParamsToJsonMap(rsr, data);
				}
				else {
					String body = IOUtils.toString(rsr.request.getInputStream());
					data = new JSONObject(body);
				}
				service.servePost(rsr, data);
			}
			break;
		case 2:
			if (isGet) {
				handled = true;
				service.serveGet(rsr, Utils.urlDecode(parts[1]));
			}
			else if ("PUT" .equals(method)) {
				handled = true;
				String body = IOUtils.toString(rsr.request.getInputStream());
				service.servePut(rsr, Utils.urlDecode(parts[1]), new JSONObject(body), rsr.getETag());
			}
			else if ("DELETE".equals(method) && parts.length == 2) {
				handled = true;
				service.serveDelete(rsr, Utils.urlDecode(parts[1]), rsr.getETag());
			}
			break;
		case 3:
			if (isGet && "changes".equals(parts[1])) {
				handled = true;
				if ("list".equals(parts[2]))
					service.getChangeLogManager().serveChanges(rsr, false);
				else if ("feed".equals(parts[2]))
					service.getChangeLogManager().serveChanges(rsr, true);
				else
					throw new RestPublishException(RestExceptionType.NOT_FOUND, "changes/"  + parts[2] + " is not a valid change request. Please use 'changes/list' or 'changes/feed'");
			}
		}

		if (!handled)
			throw new RestPublishException(RestExceptionType.METHOD_NOT_ALLOWED, "Unsupported operation: " + method + " on " + rsr.request.getPathInfo());
			*/
	}

	public static boolean isStarted() {
		return started;
	}

	public static void clearServices() {
		services.clear();		
	}

}
