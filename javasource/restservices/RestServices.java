package restservices;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;











import restservices.publish.MicroflowService;
import restservices.publish.DataService;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.mendix.core.Core;
import com.mendix.m2ee.log.ILogNode;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;

import communitycommons.XPath;

public class RestServices {
	
	/**
	 * Version of the RestServices module
	 */
	public static final String VERSION = "1.2.0";

	/**
	 * Amount of objects that are processed by the module at the same time.
	 * Larger number yields better performance but increases memory consumptions. 
	 * 
	 * Defaults to 1000.
	 */
	public static int BATCHSIZE = 1000;
	public static final int MAXPOLLQUEUE_LENGTH = 10000;
	public static final int LONGPOLL_MAXDURATION = 50; //In seconds

	public static ILogNode LOGPUBLISH = Core.getLogger("RestPublish");
	public static ILogNode LOGCONSUME = Core.getLogger("RestConsume");
	public static ILogNode LOGUTIL = Core.getLogger("RestUtil");
	
	public static final String END_OF_HTTPHEADER = "\r\n\r\n";
	public static final String UTF8 = "UTF-8";
	public static final String BASIC_AUTHENTICATION = "Basic";
	public static final String CURRENTUSER_TOKEN = "'" + XPath.CurrentUser + "'";
	public static final String STYLESHEET =
			"body { font-family: Arial; font-size: 0.8em; padding: 0px 60px; margin: 0px; }"+
			"h1 { margin: 0 -60px 20px; background-color: #5c3566; font-size: em; padding: 20px 60px; color: white; box-shadow: 3px 3px 2px #666;text-transform:uppercase }"+
			"table { border-spacing: 0px; margin-top:-4px } "+
			"td:first-child { border-right: 1px dotted #ccc; font-weight: bold; text-align: right; color: #666;text-transform:uppercase;font-size:0.8em;padding-top:6px}"+
			"td:first-child, th:first-child { background-color: #f9f9f9; }"+
			"td { padding: 4px 8px; vertical-align: top; min-width: 100px; }"+
			"a, a:active, a:visited { color: #5c3566 }"+
			"h2 { margin-top: 40px; color: white; background-color:#333; border-radius:2px; padding: 8px 16px;}"+
			"h2 small { font-size: 0.5em; } h2 a { color: white !important; text-decoration: none; }";

	public static final String CONTENTTYPE_APPLICATIONJSON = "application/json";
	public static final String CONTENTTYPE_FORMENCODED = "application/x-www-form-urlencoded";
	public static final String CONTENTTYPE_MULTIPART = "multipart/form-data";
	public static final String CONTENTTYPE_OCTET = "application/octet-stream";

	public static final String HEADER_ETAG = "ETag";
	public static final String HEADER_IFNONEMATCH = "If-None-Match";
	public static final String HEADER_ACCEPT = "Accept";
	public static final String HEADER_AUTHORIZATION = "Authorization";
	public static final String HEADER_CONTENTTYPE = "Content-Type";
	public static final String HEADER_WWWAUTHENTICATE = "WWW-Authenticate";
	
	public static String PATH_REST = "rest/";
	public static final String PATH_LIST = "list";
	public static final String PATH_FEED = "feed";
	public static final String PATH_CHANGES = "changes";
	
	public static final String PARAM_CONTENTTYPE = "contenttype";
	public static final String PARAM_SINCE = "since";
	public static final String PARAM_TIMEOUT = "timeout";
	public static final String PARAM_ABOUT = "about";
	public static final String PARAM_COUNT = "count" ;
	public static final String PARAM_DATA = "data";
	public static final String PARAM_OFFSET = "offset"; 
	public static final String PARAM_LIMIT = "limit"; 

	public static final String CHANGE_DATA = "data";
	public static final String CHANGE_KEY = "key";
	public static final String CHANGE_DELETED = "deleted";
	public static final String CHANGE_SEQNR = "seq";
	public static final String CHANGE_ETAG = "etag";
	public static final String CHANGE_URL = "url";


	static Map<String, DataService> services = new HashMap<String, DataService>();
	static Map<String, DataService> servicesByEntity = new HashMap<String, DataService>();
	static Map<String, MicroflowService> microflowServices = new HashMap<String, MicroflowService>();
	static ListMultimap<String, MicroflowService> microflowServicesByVerb = ArrayListMultimap.create();

	public static DataService getServiceForEntity(String entityType) {
		if (servicesByEntity.containsKey(entityType))
			return servicesByEntity.get(entityType);
		
		//if not look into super entitites as well!
		IMetaObject meta = Core.getMetaObject(entityType);
		if (meta.getSuperObject() != null) {
			DataService superService = getServiceForEntity(meta.getSuperName());
			if (superService != null) {
				servicesByEntity.put(entityType, superService);
				return superService;
			}
		}
		servicesByEntity.put(entityType, null); //no service. Remember that
		return null;
	}
	
	public static void registerService(String name, DataService def) {
		DataService current = services.put(name,  def);
		
		if (current != null)
			current.dispose();
		
		if (def.isGetObjectEnabled())
			servicesByEntity.put(def.getSourceEntity(), def);
		else {
			current = servicesByEntity.get(def.getSourceEntity());
			if (current != null && current.getName().equals(def.getName())) 
				servicesByEntity.remove(def.getSourceEntity());
		}

		LOGPUBLISH.info("Registered data service '" + def.getName() + "'");
	}

	public static DataService getService(String name) {
		return services.get(name);
	}

	public static Set<String> getServiceNames() {
		Set<String> names = new HashSet<String>(services.keySet());
		names.addAll(microflowServices.keySet());
		return names;
	}
	
	public static String getBaseUrl() {
		return Core.getConfiguration().getApplicationRootUrl() + PATH_REST;
	}

	public static String getServiceUrl(String name) {
		return getBaseUrl() + name + (microflowServices.containsKey(name) ? "" : "/");
	}

	public static void registerPublishedMicroflow(MicroflowService microflowService) {
		if(microflowService.getPathTemplate() != null) {
			if(microflowService.getVerb() == null)
				LOGPUBLISH.error("Microflow service '" + microflowService.getName() + "' has a template but no verb defined.");
			else
				microflowServicesByVerb.put(microflowService.getVerb(), microflowService);
		}
		else
			microflowServices.put(microflowService.getName(), microflowService);
		
		LOGPUBLISH.info("Registered microflow service '" + microflowService.getName() + "'");
	}
	
	public static MicroflowService getPublishedMicroflow(String name) {
		return microflowServices.get(name);
	}

	public static MicroflowService getPublishedMicroflow(String httpMethod, String path) {
		List<MicroflowService> services = microflowServicesByVerb.get(httpMethod);
		
		if (services == null)
			return null;
		
		for (MicroflowService microflowService : services) {
			if(microflowService.getPathTemplate().match(path, new ArrayList<String>()))
				return microflowService;
		}
		
		return null;
	}
}
