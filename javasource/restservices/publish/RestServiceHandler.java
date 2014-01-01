package restservices.publish;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.json.JSONObject;

import restservices.RestServices;
import restservices.publish.RestServiceRequest.ContentType;
import restservices.util.Utils;
import scala.xml.persistent.SetStorage;

import com.mendix.core.Core;
import com.mendix.externalinterface.connector.RequestHandler;
import com.mendix.m2ee.api.IMxRuntimeRequest;
import com.mendix.m2ee.api.IMxRuntimeResponse;

public class RestServiceHandler extends RequestHandler{

	private static RestServiceHandler instance = null;
	
	public static void start() throws Exception {
		if (instance == null) {
			instance = new RestServiceHandler();
			instance.loadConfig();
			Core.addRequestHandler(RestServices.HANDLERPATH, instance);
		}
	}

	private void loadConfig() throws JsonParseException, JsonMappingException, IOException {
		//Read definitions of publish services
		for(File configfile : new File(Utils.getResourceFilePath() + "Published").listFiles(new FilenameFilter() {
			 public boolean accept(File dir, String name) {
			        return name.toLowerCase().endsWith(".json");
			    }	
		})) {
			//TODO: might not work with cloud security since jackson uses reflection
			PublishedService def = Utils.getJsonMapper().readValue(configfile, PublishedService.class);
			def.consistencyCheck();
			RestServices.registerService(def.getName(), def);
		}
	}
	
	@Override
	public void processRequest(IMxRuntimeRequest req, IMxRuntimeResponse resp,
			String path) throws Exception {
		
		String[] parts = path.isEmpty() ? new String[]{} : path.split("/");
		Request request = (Request) req.getOriginalRequest();
		Response response = (Response) resp.getOriginalResponse();
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
		
		RestServiceRequest rsr = new RestServiceRequest(request, response);

		if ("GET".equals(method) && parts.length == 0) {
			serveServiceOverview(rsr);
		}
		else if ("GET".equals(method) && parts.length == 1) {
			checkReadAccess(request, response);
			//TODO: check if listing is enabled
			service.serveListing(rsr);
		}
		else if ("GET".equals(method) && parts.length == 2) {
			checkReadAccess(request, response);
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

	private void expireAlways(Response response) {
		response.setHeader("Expires", "-1");
	}

	private void checkReadAccess(Request request, Response response) {
		//TODO:
	}

	private void serve404(Response response) { //TODO: require reason message
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
