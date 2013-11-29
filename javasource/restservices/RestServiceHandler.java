package restservices;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

import com.mendix.core.Core;
import com.mendix.externalinterface.connector.RequestHandler;
import com.mendix.m2ee.api.IMxRuntimeRequest;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.m2ee.log.ILogNode;

public class RestServiceHandler extends RequestHandler{

	private static final ILogNode LOG = Core.getLogger("RestPublisher");
	private static RestServiceHandler instance = null;
	private static Map<String, PublishedServiceDefinition> services = new HashMap<String, PublishedServiceDefinition>();
	private static Map<String, PublishedServiceDefinition> servicesByEntity = new HashMap<String, PublishedServiceDefinition>();
	
	public static void start() throws Exception {
		if (instance == null) {
			instance = new RestServiceHandler();
			instance.loadConfig();
			Core.addRequestHandler(RestServices.HANDLERPATH, instance);
		}
	}

	private void loadConfig() throws JsonParseException, JsonMappingException, IOException {
		//Read definitions of publish services
		for(File configfile : new File(RestServices.getResourceFilePath() + "Published").listFiles(new FilenameFilter() {
			 public boolean accept(File dir, String name) {
			        return name.toLowerCase().endsWith(".json");
			    }	
		})) {
			//TODO: might not work with cloud security since jackson uses reflection
			PublishedServiceDefinition def = RestServices.getJsonMapper().readValue(configfile, PublishedServiceDefinition.class);
			def.consistencyCheck();
			services.put(def.getName(), def);
			if (servicesByEntity.containsKey(def.getSourceEntity()))
				throw new RuntimeException(String.format("Invalid service definition in '%s': Another services for entity '%s' is already defined", configfile.getName(), def.getSourceEntity()));
			servicesByEntity.put(def.getSourceEntity(), def);
		}
	}
	
	@Override
	public void processRequest(IMxRuntimeRequest req, IMxRuntimeResponse resp,
			String path) throws Exception {
		
		String[] parts = path.split("/");
		Request request = (Request) req.getOriginalRequest();
		Response response = (Response) resp.getOriginalResponse();
		response.setCharacterEncoding("UTF-8");

		LOG.info("incoming request: " + request.getMethod() + " " + path);
		
		if (parts.length == 0)
			serve404(response);
		
		PublishedServiceDefinition service = services.get(parts[0]);
		if (service == null) {
			serve404(response);
			return;
		}
		
		RestServiceRequest rsr = new RestServiceRequest(service, request, response);
		
		if ("GET".equals(request.getMethod()) && parts.length == 1) {
			checkReadAccess(request, response);
			//TODO: check if listing is enabled
			expireAlways(response);
			service.serveListing(rsr);
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

	private void serve404(Response response) {
		response.setStatus(IMxRuntimeResponse.NOT_FOUND);
	}
}
