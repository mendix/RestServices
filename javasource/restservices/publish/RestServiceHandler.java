package restservices.publish;


import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Lists.newCopyOnWriteArrayList;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.json.JSONException;
import org.json.JSONObject;

import restservices.RestServices;
import restservices.consume.RestConsumeException;
import restservices.consume.RestConsumer;
import restservices.proxies.DataServiceDefinition;
import restservices.proxies.HttpMethod;
import restservices.proxies.RestServiceError;
import restservices.publish.RestPublishException.RestExceptionType;
import restservices.util.Function;
import restservices.util.ICloseable;
import restservices.util.UriTemplate;
import restservices.util.Utils;

import com.google.common.collect.Maps;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.externalinterface.connector.RequestHandler;
import com.mendix.integration.WebserviceException;
import com.mendix.m2ee.api.IMxRuntimeRequest;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.ISession;
import communitycommons.XPath;

public class RestServiceHandler extends RequestHandler{

	private static RestServiceHandler instance = null;
	private static boolean started = false;
	
	static class HandlerRegistration implements ICloseable {
		final String method;
		final UriTemplate template;
		final String roleOrMicroflow;
		final IRestServiceHandler handler;

		HandlerRegistration(String method, UriTemplate template, String roleOrMicroflow, IRestServiceHandler handler) {
			this.method = method;
			this.template = template;
			this.roleOrMicroflow = roleOrMicroflow;
			this.handler = handler;
		}
		
		@Override
		public String toString() {
			return ReflectionToStringBuilder.toString(this, ToStringStyle.SHORT_PREFIX_STYLE);
		}

		@Override
		public void close() {
			services.remove(this);			
		}
	}
	
	private static List<HandlerRegistration> services = newCopyOnWriteArrayList();
	private static List<String> metaServiceUrls = newCopyOnWriteArrayList();

	static {
		registerServiceOverviewHandler();
	}
	
	public synchronized static void start(IContext context) throws Exception {
		if (instance == null) {
			RestServices.LOGPUBLISH.info("Starting RestServices module...");

			instance = new RestServiceHandler();
			
			boolean isSandbox = Core.getConfiguration().getApplicationRootUrl().contains(".mendixcloud.com") && Core.getConfiguration().isInDevelopment();
			if (isSandbox)
				startSandboxCompatibilityMode();
			else
				Core.addRequestHandler(RestServices.PATH_REST, instance);
			
			started = true;

			loadConfig(context);
			
			RestServices.LOGPUBLISH.info("Starting RestServices module... DONE");
		}
	}

	/**
	 * startSandboxCompatibilityMode is introduced to circumvent the fact that custom request handlers
	 * are not available in sandbox apps. In that case the 'ws-doc' requesthandler is reclaimed by the
	 * Rest services module as soon the app is started. Obviously this is a nasty workaround and this
	 * should only be used for demo / testing purposes but never be exposed in real live apps. 
	 */
	private static void startSandboxCompatibilityMode() {
		RestServices.PATH_REST = "ws-doc/";
		final RestServiceHandler self = instance;
		
		new Thread() {
			@Override
			public void run() {
				boolean started = false;
				while(!started) {
					try {
						Thread.sleep(1000);
						RestConsumer.getObject(Core.createSystemContext(), Core.getConfiguration().getApplicationRootUrl() + "/ws/", null);
						started = true;
					} catch (RestConsumeException e) {
						started = e.getResponseData().getStatus() != HttpStatus.SC_BAD_GATEWAY;
					}
					catch (Exception e) {
						RestServices.LOGPUBLISH.warn("Error when trying to start sandbox mode: " + e.getMessage(), e);
						break;
					}
				} 
				Core.addRequestHandler(RestServices.PATH_REST, self);
				RestServices.LOGPUBLISH.warn("The RestServices module has been started on basepath 'ws-doc/' for sandbox compatibility. Please use the alternative basepath for demo & testing purposes only, and do not share this path as integration endpoint");
			}
		}.start();
	}

	private static void registerServiceOverviewHandler() {
		registerServiceHandler(HttpMethod.GET, "/", "*", new IRestServiceHandler() {

			@Override
			public void execute(RestServiceRequest rsr,
					Map<String, String> params) throws Exception {
				ServiceDescriber.serveServiceOverview(rsr); 
			}
			
		});
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
			DataService service = DataService.getServiceByDefinition(def);
			if (service != null) {
				service.unregister();
			}
			
			RestServices.LOGPUBLISH.info("Reloading definition of service '" + def.getName() + "'");
			service = new DataService(def);
			service.register();
			
			RestServices.LOGPUBLISH.info("Loading service " + def.getName()+ "... DONE");
		}
	}
	
	public static HandlerRegistration registerServiceHandler(HttpMethod method, String templatePath, String roleOrMicroflow, IRestServiceHandler handler) {
		checkNotNull(method, "method");
		
		HandlerRegistration handlerRegistration = new HandlerRegistration(method.toString(), new UriTemplate(templatePath), roleOrMicroflow, handler);
		services.add(handlerRegistration);

		RestServices.LOGPUBLISH.info("Registered data service on '" + method + " " + templatePath + "'");
		return handlerRegistration;
	}
	
	private static void requestParamsToJsonMap(RestServiceRequest rsr, Map<String, String> params) {
		for (String param : rsr.request.getParameterMap().keySet())
			params.put(param, rsr.request.getParameter(param));
	}
	
	public static void paramMapToJsonObject(Map<String, String> params, JSONObject data) {
		for(Entry<String, String> pathValue : params.entrySet())
			data.put(pathValue.getKey(), pathValue.getValue());		
	}

	private static void executeHandler(final RestServiceRequest rsr, String method, String relpath, ISession existingSession) throws Exception {
		boolean pathExists = false;

		final Map<String, String> params = Maps.newHashMap();
		for (final HandlerRegistration reg : services) {
			if (reg.template.match(relpath, params)) {
				if (reg.method.equals(method)) {
					// Mixin query parameters
					requestParamsToJsonMap(rsr, params);

					// Execute the reqeust
					if (rsr.authenticate(reg.roleOrMicroflow, existingSession)) {

						rsr.withTransaction(new Function<Boolean>() {

							@Override
							public Boolean apply() throws Exception {
								reg.handler.execute(rsr, params);
								return true;
							}

						});

						return;
					} else {
						throw new RestPublishException(RestExceptionType.UNAUTHORIZED, "Unauthorized. Please provide valid credentials or set up a Mendix user session");
					}
				} else {
					pathExists = true;
				}
			}
		}

		if (pathExists) {
			throw new RestPublishException(RestExceptionType.METHOD_NOT_ALLOWED, "Method not allowed for service at: '" + relpath + "'");
		} else {
			throw new RestPublishException(RestExceptionType.NOT_FOUND, "Unknown service at: '" + relpath + "'");
		}
	}

	@Override
	public void processRequest(IMxRuntimeRequest req, IMxRuntimeResponse resp,
			String _) {

		long start = System.currentTimeMillis();
		
		HttpServletRequest request = req.getHttpServletRequest();
		HttpServletResponse response = resp.getHttpServletResponse();

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
			
			executeHandler(rsr, method, relpath, existingSession);
			
			if (RestServices.LOGPUBLISH.isDebugEnabled())
					RestServices.LOGPUBLISH.debug("Served " + requestStr + " in " + (System.currentTimeMillis() - start) + "ms.");
		}
		catch(RestPublishException rre) {
			handleRestPublishException(requestStr, rsr, rre);
		}
		catch(JSONException je) {
			handleJsonException(requestStr, rsr, je);
		}
		catch(Throwable e) {
			Throwable cause = ExceptionUtils.getRootCause(e);
			if (cause instanceof RestPublishException)
				handleRestPublishException(requestStr, rsr, (RestPublishException) cause);
			else if (cause instanceof JSONException)
				handleJsonException(requestStr, rsr, (JSONException) cause);
			if (cause instanceof CustomRestServiceException) {
				CustomRestServiceException rse = (CustomRestServiceException) cause;
				RestServices.LOGPUBLISH.warn(String.format("Failed to serve %s: %d (code: %s): %s", requestStr, rse.getHttpStatus(), rse.getDetail(), rse.getMessage()));
				serveErrorPage(rsr, rse.getHttpStatus(), rse.getMessage(), rse.getDetail());
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

	public void handleJsonException(String requestStr, RestServiceRequest rsr,
			JSONException je) {
		RestServices.LOGPUBLISH.warn("Failed to serve " + requestStr + ": Invalid JSON: " + je.getMessage());

		serveErrorPage(rsr, HttpStatus.SC_BAD_REQUEST, "JSON is incorrect. Please review the request data: " + je.getMessage(), "INVALID_JSON");
	}

	public void handleRestPublishException(String requestStr,
			RestServiceRequest rsr, RestPublishException rre) {
		RestServices.LOGPUBLISH.warn("Failed to serve " + requestStr + ": " + rre.getType() + " " + rre.getMessage());

		serveErrorPage(rsr, rre.getStatusCode(), rre.getType().toString() + ": " + requestStr + " " + rre.getMessage(), rre.getType().toString());
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

	public static boolean isStarted() {
		return started;
	}

	public static void clearServices() {
		services.clear();	
		registerServiceOverviewHandler();
	}

	public static ICloseable registerServiceHandlerMetaUrl(final String serviceBaseUrl) {
		checkArgument(isNotEmpty(serviceBaseUrl));
		metaServiceUrls.add(serviceBaseUrl);
		
		return new ICloseable() {

			@Override
			public void close() {
				metaServiceUrls.remove(serviceBaseUrl);
			}
		};
	}
	
	public static List<String> getServiceBaseUrls() {
		return metaServiceUrls; 
	}
	
}
