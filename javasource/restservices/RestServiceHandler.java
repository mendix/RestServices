package restservices;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;

import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

import restservices.RestServiceRequest.ContentType;

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
			Core.addRequestHandler(Constants.HANDLERPATH, instance);
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
			RestServices.services.put(def.getName(), def);
			if (RestServices.servicesByEntity.containsKey(def.getSourceEntity()))
				throw new RuntimeException(String.format("Invalid service definition in '%s': Another services for entity '%s' is already defined", configfile.getName(), def.getSourceEntity()));
			RestServices.servicesByEntity.put(def.getSourceEntity(), def);
		}
	}
	
	@Override
	public void processRequest(IMxRuntimeRequest req, IMxRuntimeResponse resp,
			String path) throws Exception {
		
		String[] parts = path.isEmpty() ? new String[]{} : path.split("/");
		Request request = (Request) req.getOriginalRequest();
		Response response = (Response) resp.getOriginalResponse();

		response.setCharacterEncoding(Constants.UTF8);
		expireAlways(response);

		RestServices.LOG.info("incoming request: " + request.getMethod() + " " + path);
		
		PublishedService service = null;
		if (parts.length > 0) {
			service = RestServices.services.get(parts[0]);
			if (service == null) {
				serve404(response);
				return;
			}
		}
		
		RestServiceRequest rsr = new RestServiceRequest(request, response);

		if ("GET".equals(request.getMethod()) && parts.length == 0) {
			serveServiceOverview(rsr);
		}
		else if ("GET".equals(request.getMethod()) && parts.length == 1) {
			checkReadAccess(request, response);
			//TODO: check if listing is enabled
			service.serveListing(rsr);
		}
		else if ("GET".equals(request.getMethod()) && parts.length == 2) {
			checkReadAccess(request, response);
			service.serveGet(rsr, parts[1]);
		}
		else
			serve404(response);
		
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
		switch(rsr.getContentType()) {
		case JSON:
			rsr.jsonwriter.object();
			rsr.jsonwriter
				.key("RestServices").value(RestServices.VERSION)
				.key("services").array();
			break;
		case XML:
			rsr.startXMLDoc();
			rsr.write("<RestServices><version>").write(RestServices.VERSION).write("</version><services>");
			break;
		case HTML:
			rsr.startHTMLDoc();
			rsr.write("<h1>RestServices</h1><br />Version: ").write(RestServices.VERSION).write("<h2>Available services</h2>");
		}
		
		for (PublishedService service : RestServices.services.values())
			service.serveServiceDescription(rsr);
		
		switch(rsr.getContentType()) {
		case JSON:
			rsr.jsonwriter.endArray().endObject();
			break;
		case XML:
			rsr.write("</services></RestServices>");
			break;
		case HTML:
			rsr.endHTMLDoc();
				
		}
		rsr.close();
	}
}
