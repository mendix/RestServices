package restservices.publish;


import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.json.JSONObject;

import restservices.RestServices;
import restservices.proxies.ServiceDefinition;
import restservices.publish.RestServiceRequest.ContentType;

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
			String path) throws Exception {
		
		String[] parts = path.isEmpty() ? new String[]{} : path.split("/");
		HttpServletRequest request = (HttpServletRequest) req.getOriginalRequest();
		HttpServletResponse response = (HttpServletResponse) resp.getOriginalResponse();
		String method = request.getMethod();

		response.setCharacterEncoding(RestServices.UTF8);
		expireAlways(response);

		RestServices.LOG.info("incoming request: " + method + " " + path);
		
		PublishedService service = null;
		if (parts.length > 0) {
			service = RestServices.getService(parts[0]);
			if (service == null) {
				serve404(response);
				return;
			}
		}

		RestServiceRequest rsr = new RestServiceRequest(request, response); //TODO: what is the bool arg about?

		if (service != null && !rsr.authenticateService(service, (ISession) getSessionFromRequest(req)))
			return;
		
		try {
			if ("GET".equals(method) && parts.length == 0) 
				serveServiceOverview(rsr);
			else if ("GET".equals(method) && parts.length == 1 ) 
				service.serveListing(rsr, "true".equals(request.getParameter("data")));
			else if ("GET".equals(method) && parts.length == 2) {
				if ("changes".equals(parts[1]))
					service.getChangeManager().serveChanges(rsr);
				else
					service.serveGet(rsr, parts[1]);
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
		finally {
			//TODO: if in transaction, complete or rollback
			rsr.dispose(); 
		}
	}

	private void expireAlways(HttpServletResponse response) {
		response.setHeader("Expires", "-1");
	}

	private void serve404(HttpServletResponse response) { //TODO: require reason message
		response.setStatus(IMxRuntimeResponse.NOT_FOUND);
	}

	public void serveServiceOverview(RestServiceRequest rsr) {
		if (rsr.getContentType() == ContentType.XML) {
			rsr.startXMLDoc();
			rsr.write("<RestServices>");
		}
		else if (rsr.getContentType() == ContentType.HTML) {
			rsr.startHTMLDoc();
			rsr.write("<h1>RestServices</h1>");
		}

		rsr.datawriter.object()
			.key("RestServices").value(RestServices.VERSION)
			.key("services").array();
		
		for (String service : RestServices.getServiceNames())
			RestServices.getService(service).serveServiceDescription(rsr);
		
		rsr.datawriter.endArray().endObject();
		
		if (rsr.getContentType() == ContentType.XML) 
			rsr.write("</RestServices>");
		else if (rsr.getContentType() == ContentType.HTML) 
			rsr.endHTMLDoc();

		rsr.close();
	}
}
