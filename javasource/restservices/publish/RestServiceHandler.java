package restservices.publish;


import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.json.JSONException;
import org.json.JSONObject;

import restservices.RestServices;
import restservices.proxies.DataServiceDefinition;
import restservices.publish.RestPublishException.RestExceptionType;
import restservices.publish.RestServiceRequest.Function;
import restservices.util.Utils;

import com.google.common.collect.ImmutableMap;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.externalinterface.connector.RequestHandler;
import com.mendix.m2ee.api.IMxRuntimeRequest;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.modules.webservices.WebserviceException;
import com.mendix.systemwideinterfaces.core.IContext;

import communitycommons.XPath;

public class RestServiceHandler extends RequestHandler{

	private static RestServiceHandler instance = null;
	private static boolean started = false;

	public synchronized static void start(IContext context) throws Exception {
		if (instance == null) {
			RestServices.LOGPUBLISH.info("Starting RestServices module...");

			instance = new RestServiceHandler();
			Core.addRequestHandler(RestServices.PATH_REST, instance);
			started = true;
			loadConfig(context);

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
			RestServices.registerService(service.getName(), service);
			RestServices.LOGPUBLISH.info("Loading service " + def.getName()+ "... DONE");
		}
	}

	@Override
	public void processRequest(IMxRuntimeRequest req, IMxRuntimeResponse resp,
			String path) {

		long start = System.currentTimeMillis();

		HttpServletRequest request = (HttpServletRequest) req.getOriginalRequest();
		HttpServletResponse response = (HttpServletResponse) resp.getOriginalResponse();

		String method = request.getMethod();
		String requestStr =  method + " " + path;
		URL u;
		try {
			u = new URL(request.getRequestURL().toString());
		} catch (MalformedURLException e1) {
			throw new IllegalStateException(e1);
		}

		String[] basePath = u.getPath().split("/");
		String[] parts = Arrays.copyOfRange(basePath, 2, basePath.length);

		response.setCharacterEncoding(RestServices.UTF8);
		response.setHeader("Expires", "-1");

		if (RestServices.LOGPUBLISH.isDebugEnabled())
			RestServices.LOGPUBLISH.debug("incoming request: " + Utils.getRequestUrl(request));
	
		RestServiceRequest rsr = new RestServiceRequest(request, response, resp);
		try {
			//service overview requiest
			if ("GET".equals(method) && parts.length == 0) {
				ServiceDescriber.serveServiceOverview(rsr);
			}

			else {
				//Find the service being invoked
				DataService dataService = null;
				MicroflowService mfService = null;

				if (parts.length > 0) {
					parts[0] = parts[0].toLowerCase();
					dataService = RestServices.getService(parts[0]);
					mfService = RestServices.getPublishedMicroflow(parts[0]);
					if (dataService == null && mfService == null) 
						throw new RestPublishException(RestExceptionType.NOT_FOUND, "Unknown service: '" + parts[0] + "'");
				}

				//Find request meta data
				boolean isMeta = isMetaDataRequest(method, parts, rsr);
				String authRole = dataService != null ? dataService.getRequiredRoleOrMicroflow() : mfService.getRequiredRoleOrMicroflow();

				//authenticate
				if (!isMeta && (mfService != null || dataService != null)) {
					//authenticate sets up session as side-effect
					if (!rsr.authenticate(authRole, getSessionFromRequest(req)))
						throw new RestPublishException(RestExceptionType.UNAUTHORIZED, "Unauthorized. Please provide valid credentials or set up a Mendix user session");
				}

				executeRequest(method, parts, rsr, dataService, mfService);

				if (RestServices.LOGPUBLISH.isDebugEnabled())
					RestServices.LOGPUBLISH.debug("Served " + requestStr + " in " + (System.currentTimeMillis() - start) + "ms.");
			}
		}
		catch(RestPublishException rre) {
			RestServices.LOGPUBLISH.warn("Failed to serve " + requestStr + " " + rre.getType() + " " + rre.getMessage());

			serveErrorPage(rsr, rre.getStatusCode(), rre.getType().toString() + ": " + requestStr, rre.getMessage());
		}
		catch(JSONException je) {
			RestServices.LOGPUBLISH.warn("Failed to serve " + requestStr + ": Invalid JSON: " + je.getMessage());

			serveErrorPage(rsr, HttpStatus.SC_BAD_REQUEST, "JSON is incorrect. Please review the request data.", je.getMessage());
		}
		catch(Throwable e) {
			Throwable cause = ExceptionUtils.getRootCause(e);
			if (cause instanceof WebserviceException) {
				RestServices.LOGPUBLISH.warn("Invalid request " + requestStr + ": " +cause.getMessage());
				serveErrorPage(rsr, HttpStatus.SC_BAD_REQUEST, "Invalid request data at: " + requestStr, cause.getMessage());
			}
			else {
				RestServices.LOGPUBLISH.error("Failed to serve " + requestStr + ": " +e.getMessage(), e);
				serveErrorPage(rsr, HttpStatus.SC_INTERNAL_SERVER_ERROR, "Failed to serve: " + requestStr, "An internal server error occurred. Please check the application logs or contact a system administrator.");
			}
		}
		finally {
			rsr.dispose();
		}
	}

	private void executeRequest(final String method, final String[] parts,
			final RestServiceRequest rsr, final DataService service,
			final MicroflowService mf) throws Exception {

		rsr.withTransaction(new Function<Boolean>() {

			@Override
			public Boolean apply() throws Exception {
				if (service == null) {
					if (isMetaDataRequest(method, parts, rsr))
						mf.serveDescription(rsr);
					else
						mf.execute(rsr);
				}
				else
					dispatchDataService(method, parts, rsr, service);

				return true;
			}

		});
	}

	private boolean isMetaDataRequest(String method, String[] parts, RestServiceRequest rsr) {
		return "GET".equals(method) && parts.length == 1 && rsr.request.getParameter(RestServices.PARAM_ABOUT) != null;
	}

	public static void requestParamsToJsonMap(RestServiceRequest rsr, JSONObject target) {
		for (String param : rsr.request.getParameterMap().keySet())
			target.put(param, rsr.request.getParameter(param));
	}

	private void serveErrorPage(RestServiceRequest rsr, int status, String title,
			String detail) {
		rsr.response.reset();
		rsr.response.setStatus(status);

		//reques authentication
		if (status == HttpStatus.SC_UNAUTHORIZED)
			rsr.response.addHeader(RestServices.HEADER_WWWAUTHENTICATE, "Basic realm=\"Rest Services\"");

		rsr.startDoc();

		switch(rsr.getResponseContentType()) {
		default:
		case HTML:
			rsr.write("<h1>" + title + "</h1><p>" + detail + "</p><p>Status code:" + status + "</p>");
			break;
		case JSON:
		case XML:
			rsr.datawriter.value(new JSONObject(ImmutableMap.of("error", (Object) title, "status", status, "message", detail)));
			break;
		}

		rsr.endDoc();
	}

	private void dispatchDataService(String method, String[] parts, RestServiceRequest rsr, DataService service) throws Exception, IOException,
			CoreException, RestPublishException {
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
	}

}
