package restservices.publish;


import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.http.HttpStatus;
import org.json.JSONObject;

import restservices.RestServices;
import restservices.proxies.ServiceDefinition;
import restservices.publish.RestRequestException.RestExceptionType;

import com.google.common.collect.ImmutableMap;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.externalinterface.connector.RequestHandler;
import com.mendix.m2ee.api.IMxRuntimeRequest;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.ISession;

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
		for (ServiceDefinition def : XPath.create(context, ServiceDefinition.class).all())
			loadConfig(def);
	}
	
	public static void loadConfig(ServiceDefinition def) {
		if (!started)
			return;
		
		String errors = ConsistencyChecker.check(def);
		if (errors != null)
			RestServices.LOG.error("Failed to load service '" + def.getName() + "': \n" + errors);
		else {
			PublishedService service = new PublishedService(def);
			RestServices.registerService(service.getName(), service);
		}
	}

	@SuppressWarnings("null")
	@Override
	public void processRequest(IMxRuntimeRequest req, IMxRuntimeResponse resp,
			String path) {
		
		long start = System.currentTimeMillis();
		
		HttpServletRequest request = (HttpServletRequest) req.getOriginalRequest();
		HttpServletResponse response = (HttpServletResponse) resp.getOriginalResponse();

		String method = request.getMethod();
		String requestStr =  method + " " + path;
		String[] parts = path.isEmpty() ? new String[]{} : path.split("/");

		response.setCharacterEncoding(RestServices.UTF8);
		response.setHeader("Expires", "-1");

		RestServices.LOG.info("incoming request: " + requestStr);
	
		RestServiceRequest rsr = new RestServiceRequest(request, response);
		try {
			
			dispatch(parts, request, method, requestStr, rsr);
			
			if (rsr.getContext().isInTransaction())
				rsr.getContext().endTransaction();
			
			if (RestServices.LOG.isDebugEnabled())
				RestServices.LOG.debug("Served " + requestStr + " in " + (System.currentTimeMillis() - start) + "ms.");
		}
		catch(RestRequestException rre) {
			RestServices.LOG.warn("Failed to serve " + requestStr + " " + rre.getType() + " " + rre.getMessage());
			rollback(rsr);
			serveErrorPage(rsr, rre.getStatusCode(), rre.getType().toString() + ": " + requestStr, rre.getMessage());
		}
		catch(Throwable e) {
			RestServices.LOG.error("Failed to serve " + requestStr + " " +e.getMessage(), e);
			rollback(rsr);
			serveErrorPage(rsr, HttpStatus.INTERNAL_SERVER_ERROR_500, "Failed to serve: " + requestStr, "An internal server error occurred. Please contact a system administrator");
		}
		finally {
			rsr.dispose(); 
		}
	}

	private void rollback(RestServiceRequest rsr) {
		if (rsr.getContext().isInTransaction())
			rsr.getContext().rollbackTransAction();
	}

	private void serveErrorPage(RestServiceRequest rsr, int status, String title,
			String detail) {
		rsr.response.reset();
		rsr.response.setStatus(status);
		rsr.startDoc();
		
		switch(rsr.getContentType()) {
		case HTML:
			rsr.write("<h1>" + title + "</h1><p>" + detail + "</p><p>Status code:" + status + "</p>");
			break;
		case JSON:
		case XML:
			rsr.datawriter.value(new JSONObject(ImmutableMap.of("error", title, "status", status, "message", detail)).toString(4));
			break;
		}
		
		rsr.endDoc();
	}

	private void dispatch(String[] parts, HttpServletRequest request,
			String method, String requestStr,
			RestServiceRequest rsr) throws Exception, IOException,
			CoreException, RestRequestException {
		PublishedService service = null;
		if (parts.length > 0) {
			service = RestServices.getService(parts[0]);
			if (service == null) 
				throw new RestRequestException(RestExceptionType.NOT_FOUND, "Unknown service: '" + parts[0] + "'");
		}


		if (service != null && !rsr.authenticateService(service, (ISession) getSessionFromRequest(req)))
			return;

		
		if ("GET".equals(method) && parts.length == 0) 
			ServiceDescriber.serveServiceOverview(rsr);
		else if ("GET".equals(method) && parts.length == 1 ) 
			service.serveListing(rsr, "true".equals(request.getParameter("data")));
		else if ("GET".equals(method) && parts.length == 2) 
			service.serveGet(rsr, parts[1]);
		else if ("GET".equals(method) && parts.length == 3 && "changes".equals(parts[1])) {
			if ("list".equals(parts[2]))
				service.getChangeManager().serveChanges(rsr, false);
			else if ("feed".equals(parts[2]))
				service.getChangeManager().serveChanges(rsr, true);
			else
				throw new RestRequestException(RestExceptionType.NOT_FOUND, requestStr + " is not a valid change request. Please use 'changes/list' or 'changes/feed'");
		}
		else if ("POST".equals(method) && parts.length ==  1) {
			String body = IOUtils.toString(rsr.request.getInputStream());
			service.servePost(rsr, new JSONObject(body));
		}
		else if ("PUT" .equals(method) && parts.length == 2) {
			String body = IOUtils.toString(rsr.request.getInputStream());
			service.servePut(rsr, parts[1], new JSONObject(body), rsr.getETag());
		}
		else if ("DELETE".equals(method) && parts.length == 2)
			service.serveDelete(rsr, parts[1], rsr.getETag());
		else
			rsr.setStatus(501); //TODO: constant
	}

}
