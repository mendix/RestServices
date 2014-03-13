package restservices.publish;


import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.json.JSONObject;

import restservices.RestServices;
import restservices.proxies.ServiceDefinition;
import restservices.publish.RestPublishException.RestExceptionType;
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
	
	public static void start(IContext context) throws Exception {
		if (instance == null) {
			instance = new RestServiceHandler();
			Core.addRequestHandler(RestServices.HANDLERPATH, instance);
			started = true;
			loadConfig(context);
		}
	}

	private static void loadConfig(IContext context) throws CoreException {
		for (ServiceDefinition def : XPath.create(context, ServiceDefinition.class).all()) {
			loadConfig(def, false);
		}
	}
	
	public static void loadConfig(ServiceDefinition def, boolean throwOnFailure) {
		if (!started)
			return;
		
		String errors = null;
		try {
			ConsistencyChecker.check(def);
		}
		catch(Exception e) {
			errors = "Failed to run consistency checks: " + e.getMessage();
		}
		
		if (errors != null) {
			String msg = "Failed to load service '" + def.getName() + "': \n" + errors;
			RestServices.LOG.error(msg);
			if (throwOnFailure)
				throw new IllegalStateException(msg);
		}
		else {
			RestServices.LOG.info("Reloading definition of service '" + def.getName() + "'");
			PublishedService service = new PublishedService(def);
			RestServices.registerService(service.getName(), service);
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
		path = u.getPath().substring(1 + RestServices.HANDLERPATH.length()); //Path which is passed to this request is already decode and therefor useless...
		
		String[] parts = path.isEmpty() ? new String[]{} : path.split("/");

		response.setCharacterEncoding(RestServices.UTF8);
		response.setHeader("Expires", "-1");

		RestServices.LOG.info("incoming request: " + requestStr);
	
		RestServiceRequest rsr = new RestServiceRequest(request, response);
		try {
			PublishedService service = null;
			PublishedMicroflow mf = null;
			if (parts.length > 0) {
				service = RestServices.getService(parts[0]);
				mf = RestServices.getPublishedMicroflow(parts[0]);
				if (service == null && mf == null) 
					throw new RestPublishException(RestExceptionType.NOT_FOUND, "Unknown service: '" + parts[0] + "'");
			}

			if (service != null && !isMetaDataRequest(method, parts, rsr) && !rsr.authenticate(service.getRequiredRole(), getSessionFromRequest(req))){
				throw new RestPublishException(RestExceptionType.UNAUTHORIZED, "Unauthorized. Please provide valid credentials or set up a Mendix user session");
			}
			else if (mf != null && !rsr.authenticate(mf.getRequiredRole(), getSessionFromRequest(req))) {
				throw new RestPublishException(RestExceptionType.UNAUTHORIZED, "Unauthorized. Please provide valid credentials or set up a Mendix user session");
			}
			
			if (mf != null) {
				if (isMetaDataRequest(method, parts, rsr))
					mf.serveDescription(rsr);
				else
					mf.execute(rsr);
			}
			else
				dispatch(method, parts, rsr, service);
			
			if (rsr.getContext() != null && rsr.getContext().isInTransaction())
				rsr.getContext().endTransaction();
			
			if (RestServices.LOG.isDebugEnabled())
				RestServices.LOG.debug("Served " + requestStr + " in " + (System.currentTimeMillis() - start) + "ms.");
		}
		catch(RestPublishException rre) {
			RestServices.LOG.warn("Failed to serve " + requestStr + " " + rre.getType() + " " + rre.getMessage());
			rollback(rsr);
			
			serveErrorPage(rsr, rre.getStatusCode(), rre.getType().toString() + ": " + requestStr, rre.getMessage());
		}
		catch(Throwable e) {
			rollback(rsr);
			Throwable cause = ExceptionUtils.getRootCause(e);
			if (cause instanceof WebserviceException) {
				RestServices.LOG.warn("Invalid request " + requestStr + ": " +cause.getMessage());
				serveErrorPage(rsr, HttpStatus.SC_BAD_REQUEST, "Invalid request data at: " + requestStr, cause.getMessage());
			}
			else { 
				RestServices.LOG.error("Failed to serve " + requestStr + ": " +e.getMessage(), e);
				serveErrorPage(rsr, HttpStatus.SC_INTERNAL_SERVER_ERROR, "Failed to serve: " + requestStr, "An internal server error occurred. Please contact a system administrator");
			}
		}
		finally {
			rsr.dispose(); 
		}
	}

	private boolean isMetaDataRequest(String method, String[] parts, RestServiceRequest rsr) {
		return "GET".equals(method) && parts.length == 1 && rsr.request.getParameter(RestServices.PARAM_ABOUT) != null;
	}

	private void rollback(RestServiceRequest rsr) {
		if (rsr != null && rsr.getContext() != null && rsr.getContext().isInTransaction())
			rsr.getContext().rollbackTransAction();
	}

	private void serveErrorPage(RestServiceRequest rsr, int status, String title,
			String detail) {
		rsr.response.reset();
		rsr.response.setStatus(status);
		
		//reques authentication
		if (status == HttpStatus.SC_UNAUTHORIZED)
			rsr.response.addHeader(RestServices.HEADER_WWWAUTHENTICATE, "Basic realm=\"Rest Services\"");
		
		rsr.startDoc();
		
		switch(rsr.getContentType()) {
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

	private void dispatch(String method, String[] parts, RestServiceRequest rsr, PublishedService service) throws Exception, IOException,
			CoreException, RestPublishException {
		boolean handled = false;
		boolean isGet = "GET".equals(method);
		
		switch(parts.length) {
		case 0:
			if (isGet) {
				handled = true;
				ServiceDescriber.serveServiceOverview(rsr);
			}
			break;
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
				String body = IOUtils.toString(rsr.request.getInputStream());
				//TODO: support form encoded as wel!
				service.servePost(rsr, new JSONObject(body));
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
					service.getChangeManager().serveChanges(rsr, false);
				else if ("feed".equals(parts[2]))
					service.getChangeManager().serveChanges(rsr, true);
				else
					throw new RestPublishException(RestExceptionType.NOT_FOUND, "changes/"  + parts[2] + " is not a valid change request. Please use 'changes/list' or 'changes/feed'");
			}
		}

		if (!handled)
			throw new RestPublishException(RestExceptionType.METHOD_NOT_ALLOWED, "Unsupported operation: " + method + " on " + rsr.request.getPathInfo());
	}

}
