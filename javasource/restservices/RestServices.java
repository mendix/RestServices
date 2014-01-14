package restservices;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;


import restservices.publish.PublishedService;

import com.mendix.core.Core;
import com.mendix.m2ee.log.ILogNode;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;

public class RestServices {
	
	public static int BATCHSIZE = 1000;

	public static ILogNode LOG = Core.getLogger("RestPublisher");
	
	public static final String VERSION = "1.0.0";
	
	static Map<String, PublishedService> services = new HashMap<String, PublishedService>();
	static Map<String, PublishedService> servicesByEntity = new HashMap<String, PublishedService>();
	
	public static PublishedService getServiceForEntity(String entityType) {
		if (servicesByEntity.containsKey(entityType))
			return servicesByEntity.get(entityType);
		
		//if not look into super entitites as well!
		IMetaObject meta = Core.getMetaObject(entityType);
		if (meta.getSuperObject() != null) {
			PublishedService superService = getServiceForEntity(meta.getSuperName());
			if (superService != null) {
				servicesByEntity.put(entityType, superService);
				return superService;
			}
		}
		servicesByEntity.put(entityType, null); //no service. Remember that
		return null;
	}



	public static final String UTF8 = "UTF-8";

	public static final String URL_ATTR = "_url";

	public static final String TEXTJSON = "text/json";

	public static final String STYLESHEET = 
	"body { font-family: Arial; font-size: 0.8em; padding: 20px 60px; } " +
	"td {padding: 5px 10px; border: 1px solid #e2e2e2; } " +
	"tr:nth-child(odd) {background-color: #e2e2e2; } " +
	"h1 {color: navy; }" +
	"hr {border-style:none; border-bottom: 1px dotted #aaa;}";

	public static final int MAXPOLLQUEUE_LENGTH = 10000;

	public static final int LONGPOLL_MAXDURATION = 50; //In seconds

	public static final String IFNONEMATCH_HEADER = "If-None-Match";

	public final static String HANDLERPATH = "rest/";

	public static final String ETAG_HEADER = "ETag";

	public static final String CONTENTTYPE_PARAM = "contenttype";

	public static final String ACCEPT_HEADER = "Accept";

	public static final String CURRENTUSER_TOKEN = "'[%CurrentUser%]'";

	public static final String END_OF_HTTPHEADER = "\r\n\r\n";

	public static final String PARAM_SINCE = "since";

	public static final String PARAM_FEED = "feed";

	public static final String PARAM_TIMEOUT = "timeout"; 
	
	public static void registerService(String name, PublishedService def) {
		PublishedService current = services.put(name,  def);
		
		if (current != null)
			current.dispose();
		
//		if (RestServices.servicesByEntity.containsKey(def.getSourceEntity()))
//			throw new RuntimeException(String.format("Invalid service definition in '%s': Another services for entity '%s' is already defined", name, def.getSourceEntity()));
		if (def.isGetObjectEnabled())
			RestServices.servicesByEntity.put(def.getSourceEntity(), def);
		else {
			//TODO: make sure it is unregistered
		}
	}

	public static PublishedService getService(String name) {
		return services.get(name);
	}

	public static Set<String> getServiceNames() {
		return services.keySet();
	}
}
